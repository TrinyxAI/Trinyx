// Pure, browser-free helpers for the render sidecar. Extracted from server.js so the
// validation + concurrency logic can be unit-tested (test/lib.test.js) without launching
// Chromium. server.js wires these into Express + Playwright.

const DEFAULT_VIEWPORT = { width: 1280, height: 800 };
const DEFAULT_TIMEOUT_MS = 8000;
const MAX_TIMEOUT_MS = 30000;

// Named page sizes Playwright's page.pdf() understands (compared case-insensitively).
const PDF_FORMATS = new Set([
  'letter', 'legal', 'tabloid', 'ledger', 'a0', 'a1', 'a2', 'a3', 'a4', 'a5', 'a6',
]);
// Playwright's page.screenshot() only supports png + jpeg (NOT webp), so we advertise exactly
// those - validating a type Chromium can't render would only surface as a 502 at render time.
const SCREENSHOT_TYPES = new Set(['png', 'jpeg']);
const WAIT_UNTIL = new Set(['load', 'domcontentloaded', 'networkidle', 'commit']);

// ---- video -----------------------------------------------------------------
// Social-media-ready capture presets. Dimensions are even on both axes (H.264
// yuv420p requires even width/height) and 1:1 with the recording viewport so
// nothing is scaled.
const VIDEO_PRESETS = {
  vertical: { width: 1080, height: 1920 },   // TikTok / Reels / Shorts
  horizontal: { width: 1920, height: 1080 }, // YouTube / X landscape
  square: { width: 1080, height: 1080 },     // feed posts
};
const VIDEO_FORMATS = new Set(['mp4', 'webm']);
const DEFAULT_VIDEO_DURATION_MS = 30000;
// Hard ceiling on recording time - a video render holds a concurrency slot for
// its whole duration, so the cap protects the pool (server.js can lower it via
// env, never raise it above this).
const MAX_VIDEO_DURATION_MS = 120000;
const DEFAULT_VIDEO_END_PADDING_MS = 400;
const MAX_VIDEO_END_PADDING_MS = 3000;
// The page signals "my animation is over" by setting this global to true.
const DEFAULT_VIDEO_DONE_FLAG = '__DONE__';
const DONE_FLAG_PATTERN = /^[A-Za-z_$][A-Za-z0-9_$]*$/;
const DEFAULT_VIDEO_FPS = 30;
// 'live' records in real time (Playwright recordVideo, ~25fps, frames drop under load);
// 'smooth' renders OFFLINE frame by frame under a virtual clock - every frame is perfect
// regardless of machine load, at the cost of render time (~60-120ms per frame).
const VIDEO_MODES = new Set(['live', 'smooth']);
// Hard ceiling on smooth-mode frames (fps x seconds). 3600 = 120s@30fps or 60s@60fps.
const MAX_TOTAL_FRAMES = 3600;
const SMOOTH_JPEG_QUALITY = 92;
// Wall-clock budget for a whole smooth render. It buys FRAMES, not seconds of clip:
// delivered_seconds = (budget / ms_per_frame) / fps. A 1080x1920 page with two srcdoc
// iframes of animated SVG measures ~330ms/frame on prod, so 180000 bought only 540
// frames = 9s at 60fps and silently truncated every 20s clip. 450000 fits the common
// worst case (20s at 60fps = 1200 frames x 330ms = 396s); it does NOT fit the absolute
// MAX_TOTAL_FRAMES ceiling for such a page, which stays wall-bound and reports
// truncated:true. Raising this changes NO pixel: under the virtual clock frame N is a
// function of its index alone, so a longer budget only lets the loop reach frames it
// used to drop.
const DEFAULT_SMOOTH_WALL_TIMEOUT_MS = 450000;
// Response headers carrying the smooth-render outcome. A truncated clip is a VALID, merely
// shorter mp4 answered 200, so these are the only way a caller can tell "the page ended the
// clip" from "we ran out of budget". The orchestrator matches these exact strings
// (InterfaceScreenshotServiceImpl); renaming one silently restores the invisible-truncation
// bug, so both sides pin the literals in their tests.
const RENDER_HEADER_TRUNCATED = 'X-Render-Truncated';
const RENDER_HEADER_FRAMES = 'X-Render-Frames';

/**
 * Map a smooth-render outcome ({frames, truncated}) to its response headers. Pure so the
 * exact wire values are unit-testable without booting Express or Chromium; server.js only
 * spreads the result onto the response.
 */
function buildRenderOutcomeHeaders(outcome) {
  const o = outcome || {};
  return {
    [RENDER_HEADER_FRAMES]: String(Number(o.frames) || 0),
    [RENDER_HEADER_TRUNCATED]: o.truncated ? 'true' : 'false',
  };
}
// Chromium viewports cannot be arbitrarily large; SetDeviceMetricsOverride caps
// out well above this, but 2160 covers every social format including 4K-side.
const MAX_VIDEO_DIMENSION = 2160;

function clampTimeout(value) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) {
    return DEFAULT_TIMEOUT_MS;
  }
  return Math.min(value, MAX_TIMEOUT_MS);
}

function resolveViewport(viewport) {
  if (viewport && typeof viewport === 'object') {
    const w = Number(viewport.width);
    const h = Number(viewport.height);
    return {
      width: Number.isFinite(w) && w > 0 ? Math.floor(w) : DEFAULT_VIEWPORT.width,
      height: Number.isFinite(h) && h > 0 ? Math.floor(h) : DEFAULT_VIEWPORT.height,
    };
  }
  return { ...DEFAULT_VIEWPORT };
}

function resolveWaitUntil(waitFor, fallback) {
  const fb = fallback || 'networkidle';
  return (typeof waitFor === 'string' && WAIT_UNTIL.has(waitFor)) ? waitFor : fb;
}

/**
 * Validate + normalise a /internal/render/screenshot body.
 * Returns { ok:true, value:{...} } or { ok:false, error:'...' }.
 * Backward compatible: the orchestrator's existing {html, viewport, fullPage, waitFor,
 * timeoutMs} payload normalises to a PNG full-page capture exactly as before.
 */
function validateScreenshotRequest(body) {
  const b = body || {};
  if (typeof b.html !== 'string' || b.html.length === 0) {
    return { ok: false, error: 'html (non-empty string) is required' };
  }

  const type = typeof b.type === 'string' ? b.type.toLowerCase() : 'png';
  if (!SCREENSHOT_TYPES.has(type)) {
    return { ok: false, error: `type must be one of: ${[...SCREENSHOT_TYPES].join(', ')}` };
  }

  let quality;
  if (b.quality !== undefined && b.quality !== null) {
    if (type === 'png') {
      return { ok: false, error: 'quality is not supported for png (use jpeg)' };
    }
    const q = Number(b.quality);
    if (!Number.isFinite(q) || q < 0 || q > 100) {
      return { ok: false, error: 'quality must be a number 0-100' };
    }
    quality = Math.round(q);
  }

  let deviceScaleFactor = 1;
  if (b.deviceScaleFactor !== undefined && b.deviceScaleFactor !== null) {
    const d = Number(b.deviceScaleFactor);
    if (!Number.isFinite(d) || d < 1 || d > 3) {
      return { ok: false, error: 'deviceScaleFactor must be a number 1-3' };
    }
    deviceScaleFactor = d;
  }

  const selector = (typeof b.selector === 'string' && b.selector.trim())
    ? b.selector.trim()
    : null;

  const mime = type === 'png' ? 'image/png' : 'image/jpeg';

  return {
    ok: true,
    value: {
      html: b.html,
      viewport: resolveViewport(b.viewport),
      deviceScaleFactor,
      waitUntil: resolveWaitUntil(b.waitFor),
      timeoutMs: clampTimeout(b.timeoutMs),
      // A selector implies an element capture, which is inherently not "full page".
      fullPage: b.fullPage !== false && !selector,
      type,
      quality,
      selector,
      omitBackground: b.omitBackground === true,
      mime,
    },
  };
}

/**
 * Validate + normalise the margin object of a PDF request. Accepts a number (=> px) or a
 * CSS length string ('1cm','10mm','0.5in','12px') per side. Returns { value } or { error }.
 */
function validateMargin(margin) {
  if (margin === undefined || margin === null) return { value: undefined };
  if (typeof margin !== 'object') {
    return { error: 'margin must be an object {top,right,bottom,left}' };
  }
  const out = {};
  for (const k of ['top', 'right', 'bottom', 'left']) {
    const v = margin[k];
    if (v === undefined || v === null) continue;
    if (typeof v === 'number' && Number.isFinite(v) && v >= 0) {
      out[k] = `${v}px`;
    } else if (typeof v === 'string' && /^\d+(\.\d+)?(px|in|cm|mm)?$/.test(v.trim())) {
      out[k] = v.trim();
    } else {
      return { error: `margin.${k} must be a non-negative number (px) or a CSS length ('1cm','10mm','0.5in','12px')` };
    }
  }
  return { value: Object.keys(out).length ? out : undefined };
}

/**
 * Validate + normalise a /internal/render/pdf body.
 * Returns { ok:true, value:{...} } or { ok:false, error:'...' }.
 */
function validatePdfRequest(body) {
  const b = body || {};
  if (typeof b.html !== 'string' || b.html.length === 0) {
    return { ok: false, error: 'html (non-empty string) is required' };
  }

  let format = 'A4';
  if (b.format !== undefined && b.format !== null) {
    if (typeof b.format !== 'string' || !PDF_FORMATS.has(b.format.toLowerCase())) {
      return { ok: false, error: `format must be one of: ${[...PDF_FORMATS].join(', ')}` };
    }
    format = b.format;
  }

  let scale = 1;
  if (b.scale !== undefined && b.scale !== null) {
    const s = Number(b.scale);
    // Playwright accepts 0.1-2.0 for page.pdf scale.
    if (!Number.isFinite(s) || s < 0.1 || s > 2) {
      return { ok: false, error: 'scale must be a number 0.1-2' };
    }
    scale = s;
  }

  const margin = validateMargin(b.margin);
  if (margin.error) return { ok: false, error: margin.error };

  let pageRanges;
  if (b.pageRanges !== undefined && b.pageRanges !== null) {
    if (typeof b.pageRanges !== 'string') {
      return { ok: false, error: "pageRanges must be a string like '1-5, 8'" };
    }
    pageRanges = b.pageRanges.trim() || undefined;
  }

  const displayHeaderFooter = b.displayHeaderFooter === true;

  return {
    ok: true,
    value: {
      html: b.html,
      format,
      landscape: b.landscape === true,
      printBackground: b.printBackground !== false, // default true
      scale,
      margin: margin.value,
      pageRanges,
      displayHeaderFooter,
      headerTemplate: (displayHeaderFooter && typeof b.headerTemplate === 'string')
        ? b.headerTemplate : undefined,
      footerTemplate: (displayHeaderFooter && typeof b.footerTemplate === 'string')
        ? b.footerTemplate : undefined,
      preferCSSPageSize: b.preferCSSPageSize === true,
      // Self-contained print HTML rarely needs networkidle; 'load' is faster + safer.
      waitUntil: resolveWaitUntil(b.waitFor, 'load'),
      timeoutMs: clampTimeout(b.timeoutMs),
    },
  };
}

/**
 * Resolve the recording viewport for a video request: explicit viewport wins over preset,
 * preset defaults to 'vertical'. Dimensions are floored to EVEN numbers (libx264 with
 * yuv420p rejects odd sizes) and clamped to [16, MAX_VIDEO_DIMENSION].
 * Returns { value } or { error }.
 */
function resolveVideoViewport(preset, viewport) {
  if (viewport && typeof viewport === 'object') {
    const w = Number(viewport.width);
    const h = Number(viewport.height);
    if (!Number.isFinite(w) || !Number.isFinite(h) || w < 16 || h < 16
        || w > MAX_VIDEO_DIMENSION || h > MAX_VIDEO_DIMENSION) {
      return { error: `viewport width/height must be numbers 16-${MAX_VIDEO_DIMENSION}` };
    }
    // floor-to-even so the H.264 transcode never fails on odd dimensions
    return { value: { width: Math.floor(w / 2) * 2, height: Math.floor(h / 2) * 2 } };
  }
  const key = (typeof preset === 'string' && preset) ? preset.toLowerCase() : 'vertical';
  const p = VIDEO_PRESETS[key];
  if (!p) {
    return { error: `preset must be one of: ${Object.keys(VIDEO_PRESETS).join(', ')}` };
  }
  return { value: { ...p } };
}

/**
 * Validate + normalise a /internal/render/video body.
 * Returns { ok:true, value:{...} } or { ok:false, error:'...' }.
 * `caps.maxDurationMs` lets the server LOWER the recording ceiling (env knob); the
 * hard MAX_VIDEO_DURATION_MS is never exceeded either way.
 */
function validateVideoRequest(body, caps) {
  const b = body || {};
  if (typeof b.html !== 'string' || b.html.length === 0) {
    return { ok: false, error: 'html (non-empty string) is required' };
  }

  const format = typeof b.format === 'string' ? b.format.toLowerCase() : 'mp4';
  if (!VIDEO_FORMATS.has(format)) {
    return { ok: false, error: `format must be one of: ${[...VIDEO_FORMATS].join(', ')}` };
  }

  const mode = typeof b.mode === 'string' ? b.mode.toLowerCase() : 'live';
  if (!VIDEO_MODES.has(mode)) {
    return { ok: false, error: `mode must be one of: ${[...VIDEO_MODES].join(', ')}` };
  }
  if (mode === 'smooth' && format !== 'mp4') {
    return { ok: false, error: 'mode=smooth produces mp4 only (frames are assembled directly into H.264)' };
  }

  const vp = resolveVideoViewport(b.preset, b.viewport);
  if (vp.error) return { ok: false, error: vp.error };

  const capMs = Math.min(
    (caps && Number(caps.maxDurationMs) > 0) ? Number(caps.maxDurationMs) : MAX_VIDEO_DURATION_MS,
    MAX_VIDEO_DURATION_MS,
  );
  let maxDurationMs = DEFAULT_VIDEO_DURATION_MS;
  if (b.maxDurationMs !== undefined && b.maxDurationMs !== null) {
    const d = Number(b.maxDurationMs);
    if (!Number.isFinite(d) || d < 1000) {
      return { ok: false, error: 'maxDurationMs must be a number >= 1000' };
    }
    maxDurationMs = d;
  }
  maxDurationMs = Math.min(maxDurationMs, capMs);

  let endPaddingMs = DEFAULT_VIDEO_END_PADDING_MS;
  if (b.endPaddingMs !== undefined && b.endPaddingMs !== null) {
    const p = Number(b.endPaddingMs);
    if (!Number.isFinite(p) || p < 0 || p > MAX_VIDEO_END_PADDING_MS) {
      return { ok: false, error: `endPaddingMs must be a number 0-${MAX_VIDEO_END_PADDING_MS}` };
    }
    endPaddingMs = Math.round(p);
  }

  let doneFlag = DEFAULT_VIDEO_DONE_FLAG;
  if (b.doneFlag !== undefined && b.doneFlag !== null) {
    if (typeof b.doneFlag !== 'string' || !DONE_FLAG_PATTERN.test(b.doneFlag)) {
      return { ok: false, error: 'doneFlag must be a valid JS identifier (e.g. __DONE__)' };
    }
    doneFlag = b.doneFlag;
  }

  let fps = DEFAULT_VIDEO_FPS;
  if (b.fps !== undefined && b.fps !== null) {
    const f = Number(b.fps);
    if (!Number.isFinite(f) || f < 10 || f > 60) {
      return { ok: false, error: 'fps must be a number 10-60' };
    }
    fps = Math.round(f);
  }

  return {
    ok: true,
    value: {
      html: b.html,
      viewport: vp.value,
      format,
      mode,
      mime: format === 'mp4' ? 'video/mp4' : 'video/webm',
      maxDurationMs,
      endPaddingMs,
      // waitForDone=false means "record the full maxDurationMs unconditionally"
      waitForDone: b.waitForDone !== false,
      doneFlag,
      fps,
      waitUntil: resolveWaitUntil(b.waitFor),
      timeoutMs: clampTimeout(b.timeoutMs),
    },
  };
}

/**
 * Injected into every frame (main page + iframes, srcdoc included) BEFORE any page script
 * runs. Replaces the page's clocks with a VIRTUAL clock the renderer advances one video
 * frame at a time: performance.now / Date.now / new Date() / requestAnimationFrame /
 * setTimeout / setInterval all follow it, CSS/WAAPI animations and SVG SMIL are re-seeked
 * on every step (relative to when each animation was first observed, so animations and
 * transitions created mid-clip play at the right speed too). The page cannot tell it is
 * being rendered offline - a 60fps clip comes out frame-perfect no matter how slow the
 * machine is.
 */
const VIRTUAL_TIME_INIT_SCRIPT = `(() => {
  if (window.__vtInstalled) return;
  window.__vtInstalled = true;
  var vt = 0;
  var epoch = 1600000000000;
  var timers = [];
  var nextId = 1;
  var rafQ = [];
  var RealDate = Date;
  try { performance.now = function () { return vt; }; } catch (e) {}
  window.Date = new Proxy(RealDate, {
    construct: function (target, args) {
      return args.length ? new target(...args) : new target(epoch + vt);
    },
    get: function (t, p) {
      if (p === 'now') return function () { return epoch + vt; };
      var v = t[p];
      return typeof v === 'function' ? v.bind(t) : v;
    }
  });
  window.requestAnimationFrame = function (cb) {
    var id = nextId++;
    rafQ.push({ id: id, cb: cb });
    return id;
  };
  window.cancelAnimationFrame = function (id) {
    for (var i = 0; i < rafQ.length; i++) {
      if (rafQ[i].id === id) { rafQ.splice(i, 1); return; }
    }
  };
  window.setTimeout = function (cb, d) {
    var id = nextId++;
    if (typeof cb !== 'function') return id;
    timers.push({ id: id, at: vt + (Number(d) || 0), cb: cb, args: [].slice.call(arguments, 2), interval: 0 });
    return id;
  };
  window.setInterval = function (cb, d) {
    var id = nextId++;
    if (typeof cb !== 'function') return id;
    var iv = Math.max(1, Number(d) || 1);
    timers.push({ id: id, at: vt + iv, cb: cb, args: [].slice.call(arguments, 2), interval: iv });
    return id;
  };
  window.clearTimeout = window.clearInterval = function (id) {
    for (var i = 0; i < timers.length; i++) {
      if (timers[i].id === id) { timers.splice(i, 1); return; }
    }
  };
  function runDue(target) {
    var guard = 0;
    for (;;) {
      if (++guard > 10000) break;
      var best = -1;
      for (var i = 0; i < timers.length; i++) {
        if (timers[i].at <= target && (best === -1 || timers[i].at < timers[best].at)) best = i;
      }
      if (best === -1) break;
      var t = timers[best];
      var due = t.at;
      if (t.interval) { t.at = due + t.interval; } else { timers.splice(best, 1); }
      vt = due;
      try { t.cb.apply(null, t.args); } catch (e) {}
    }
    vt = target;
  }
  window.__vtStep = function (delta) {
    runDue(vt + delta);
    var q = rafQ;
    rafQ = [];
    for (var i = 0; i < q.length; i++) { try { q[i].cb(vt); } catch (e) {} }
    try {
      var anims = document.getAnimations ? document.getAnimations() : [];
      for (var j = 0; j < anims.length; j++) {
        var a = anims[j];
        try {
          if (a.__vtStart === undefined) {
            a.__vtStart = vt;
            // Pause on first sight (mirrors the SMIL path): otherwise the animation keeps
            // advancing on the REAL compositor clock between the seek and the screenshot,
            // reintroducing load-dependent jitter.
            if (typeof a.pause === 'function') a.pause();
          }
          a.currentTime = vt - a.__vtStart;
        } catch (e) {}
      }
    } catch (e) {}
    try {
      var svgs = document.querySelectorAll('svg');
      for (var k = 0; k < svgs.length; k++) {
        try { svgs[k].pauseAnimations(); svgs[k].setCurrentTime(vt / 1000); } catch (e) {}
      }
    } catch (e) {}
    return vt;
  };
})();`;

/**
 * Drive the page through a SMOOTH (offline, frame-by-frame) render: load the HTML, then for
 * each output frame advance every frame's virtual clock by exactly 1000/fps ms and hand
 * control back to the caller to capture a screenshot. Stops when the page sets its done flag
 * (plus the end padding), at the frame ceiling, or at the wall-clock deadline (truncated
 * best-effort clip rather than an error). Pure orchestration - unit-testable with a fake page.
 * Returns { frames, truncated }.
 */
async function drivePageForSmoothVideo(page, o, captureFrame) {
  await page.setContent(o.html, { waitUntil: o.waitUntil, timeout: o.timeoutMs });
  const frameMs = 1000 / o.fps;
  const requestedFrames = Math.ceil(o.maxDurationMs / frameMs);
  const maxFrames = Math.min(requestedFrames, MAX_TOTAL_FRAMES);
  const padFrames = Math.max(1, Math.ceil(o.endPaddingMs / frameMs));
  const hardCap = Math.min(maxFrames + padFrames, MAX_TOTAL_FRAMES + padFrames);
  const wallDeadline = Date.now() + (o.wallTimeoutMs || DEFAULT_SMOOTH_WALL_TIMEOUT_MS);
  let padLeft = -1; // -1 = done flag not seen yet
  let frames = 0;
  let truncated = false;
  for (let i = 0; i < hardCap; i++) {
    // Capture FIRST, then advance: frame 0 is the page's t=0 state, frame i sits at
    // exactly i * 1000/fps ms of virtual time.
    await captureFrame(i);
    frames++;
    for (const fr of page.frames()) {
      try {
        await fr.evaluate((d) => (window.__vtStep ? window.__vtStep(d) : -1), frameMs);
      } catch (_) { /* frame may have detached mid-clip */ }
    }
    if (o.waitForDone && padLeft < 0) {
      let done = false;
      try {
        // Main frame only, deliberately: parity with the live path's waitForFunction.
        // The top-level interface template owns the clip timeline; iframes are content.
        done = await page.evaluate((flag) => window[flag] === true, o.doneFlag);
      } catch (_) { /* evaluation hiccup - keep rendering */ }
      if (done) padLeft = padFrames;
    } else if (padLeft > 0) {
      padLeft--;
      if (padLeft === 0) break;
    }
    if (!o.waitForDone && frames >= maxFrames) break;
    if (Date.now() > wallDeadline) { truncated = true; break; }
  }
  // Same defect class, second exit: the FRAME CEILING clamped the request, so the clip is
  // silently short exactly like the wall-clock case (120s asked at 60fps = 7200 frames ->
  // MAX_TOTAL_FRAMES delivers 60s). Two conditions, both load-bearing:
  //  - `requestedFrames > MAX_TOTAL_FRAMES` is the CLAMP itself. Gating on `padLeft < 0`
  //    alone would flag every done-flag-less page, whose clip ran its full maxDurationMs and
  //    ended normally (drivePageForVideo says the same), drowning the real signal.
  //  - `padLeft < 0` means the done flag never ended the clip first. It also holds for the
  //    whole waitForDone:false path (padLeft is only ever assigned under waitForDone), so
  //    that path is covered too: a clamped clip is silently short whether or not the caller
  //    asked us to watch for a done flag.
  if (padLeft < 0 && requestedFrames > MAX_TOTAL_FRAMES) {
    truncated = true;
  }
  return { frames, truncated };
}

/**
 * ffmpeg child-process lifecycle for the smooth path, isolated here so it is unit-testable
 * with a fake spawn and - critically - so no failure path can ever produce an unhandled
 * rejection: the internal exit promise ALWAYS resolves (never rejects), stdin carries a
 * permanent no-op 'error' listener (async EPIPE from a dying ffmpeg must not crash the
 * process), and abort() kills + reaps the child. finalize() is the only place an error is
 * thrown, into the caller's own try/catch.
 */
function createFrameSink(spawnFn, ffmpegPath, args) {
  const ff = spawnFn(ffmpegPath, args, { stdio: ['pipe', 'ignore', 'pipe'] });
  let stderrTail = '';
  if (ff.stderr && typeof ff.stderr.on === 'function') {
    ff.stderr.on('data', (d) => { stderrTail = (stderrTail + d).slice(-500); });
  }
  ff.stdin.on('error', () => { /* EPIPE after ffmpeg death - swallowed, surfaces via exit code */ });
  let exited = null;
  const exit = new Promise((resolve) => {
    ff.on('error', (err) => { exited = { code: -1, detail: String(err && err.message || err) }; resolve(exited); });
    ff.on('close', (code) => { exited = { code, detail: stderrTail }; resolve(exited); });
  });
  const midStreamDeath = (r) => new Error(`ffmpeg exited ${r.code} mid-stream: ${r.detail}`);
  return {
    /**
     * Backpressured write. If ffmpeg died, 'drain' will never fire and the swallowed stdin
     * 'error' can't reject anything - so the wait RACES the exit promise and a mid-stream
     * death throws into the caller's catch (which aborts) instead of hanging the render
     * loop forever and leaking the pool slot + browser context.
     */
    async write(buf) {
      if (exited) throw midStreamDeath(exited);
      if (!ff.stdin.write(buf)) {
        await Promise.race([
          new Promise((resolve) => ff.stdin.once('drain', resolve)),
          exit.then((r) => { throw midStreamDeath(r); }),
        ]);
      }
    },
    /** Close stdin, wait for ffmpeg, throw (into the caller's catch) on non-zero exit. */
    async finalize() {
      try { ff.stdin.end(); } catch (_) { /* already destroyed */ }
      const r = await exit;
      if (r.code !== 0) {
        throw new Error(`ffmpeg exited ${r.code}: ${r.detail}`);
      }
    },
    /** Failure path: end stdin, kill the child, reap it. Never throws. */
    async abort() {
      try { ff.stdin.end(); } catch (_) { /* already destroyed */ }
      try { ff.kill(); } catch (_) { /* already gone */ }
      await exit;
    },
  };
}

/**
 * ffmpeg argv for the smooth path: JPEG frames streamed on stdin (image2pipe) assembled
 * into H.264 + yuv420p + faststart at the exact requested fps. Pure, unit-testable.
 */
function buildImagePipeArgs(outputPath, opts) {
  const fps = (opts && Number(opts.fps) > 0) ? Math.round(Number(opts.fps)) : DEFAULT_VIDEO_FPS;
  return [
    '-y', '-loglevel', 'error',
    '-f', 'image2pipe', '-vcodec', 'mjpeg', '-framerate', String(fps),
    '-i', 'pipe:0',
    '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '18',
    '-pix_fmt', 'yuv420p',
    '-movflags', '+faststart',
    outputPath,
  ];
}

/**
 * Drive the recorded page through its clip: load the HTML, then either wait for the page's
 * done flag (window[doneFlag] === true, ceiling maxDurationMs) or record unconditionally for
 * maxDurationMs, then hold the end padding. Extracted from the /internal/render/video handler
 * so the timing orchestration is unit-testable with a fake page - Playwright's
 * waitForFunction signature is (pageFunction, ARG, options): passing the options object in
 * the arg slot silently ignores the timeout (real shipped bug), so the exact call shape is
 * pinned by tests.
 */
async function drivePageForVideo(page, o) {
  await page.setContent(o.html, { waitUntil: o.waitUntil, timeout: o.timeoutMs });
  if (o.waitForDone) {
    // Hitting maxDurationMs instead of seeing the done flag is a NORMAL ending, not an error.
    try {
      await page.waitForFunction(
        `window.${o.doneFlag} === true`,
        null, // arg slot - the options MUST be the third positional
        { timeout: o.maxDurationMs, polling: 250 },
      );
    } catch (err) {
      const message = (err && err.message) ? err.message : '';
      const isTimeout = (err && err.name === 'TimeoutError') || /Timeout.*exceeded/i.test(message);
      if (!isTimeout) throw err;
    }
  } else {
    await page.waitForTimeout(o.maxDurationMs);
  }
  if (o.endPaddingMs > 0) await page.waitForTimeout(o.endPaddingMs);
}

/**
 * ffmpeg argv for the webm -> mp4 transcode (pure, unit-testable). H.264 + yuv420p +
 * +faststart is the "plays everywhere, uploadable everywhere" combination; CRF 20 at
 * veryfast keeps the transcode a small fraction of the recording time.
 */
function buildFfmpegArgs(inputPath, outputPath, opts) {
  const fps = (opts && Number(opts.fps) > 0) ? Math.round(Number(opts.fps)) : DEFAULT_VIDEO_FPS;
  return [
    '-y', '-loglevel', 'error',
    '-i', inputPath,
    '-r', String(fps),
    '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '20',
    '-pix_fmt', 'yuv420p',
    '-movflags', '+faststart',
    outputPath,
  ];
}

/**
 * Minimal async semaphore with a bounded wait-queue. acquire() resolves when a slot is
 * free, or queues; when the queue is full it rejects with an Error whose .code is 'BUSY'.
 * release() hands the freed slot to the next waiter (if any) or lowers the in-flight count.
 */
class Semaphore {
  constructor(maxConcurrent, maxQueue) {
    this.max = Math.max(1, Math.floor(maxConcurrent) || 1);
    this.maxQueue = Math.max(0, Math.floor(maxQueue) || 0);
    this.inflight = 0;
    this.queue = [];
  }

  get queued() { return this.queue.length; }

  acquire() {
    if (this.inflight < this.max) {
      this.inflight += 1;
      return Promise.resolve();
    }
    if (this.queue.length >= this.maxQueue) {
      const err = new Error('renderer at capacity');
      err.code = 'BUSY';
      return Promise.reject(err);
    }
    return new Promise((resolve) => { this.queue.push(resolve); });
  }

  release() {
    const next = this.queue.shift();
    if (next) {
      // Transfer the slot directly to the waiter; in-flight count is unchanged.
      next();
    } else {
      this.inflight = Math.max(0, this.inflight - 1);
    }
  }
}

/**
 * Map a thrown render error to an HTTP status + message, without importing Playwright (kept pure
 * so it is unit-testable): the semaphore's BUSY → 429, a Playwright timeout (its error carries
 * name 'TimeoutError', or the message contains "Timeout ... exceeded") → 504, anything else → 502.
 */
function classifyRenderError(err) {
  if (err && err.code === 'BUSY') {
    return { status: 429, message: 'renderer at capacity, retry shortly' };
  }
  const message = (err && err.message) ? err.message : String(err);
  const isTimeout = (err && err.name === 'TimeoutError') || /Timeout.*exceeded/i.test(message);
  return { status: isTimeout ? 504 : 502, message };
}

/**
 * Owns the single warm browser + the concurrency guard for the render endpoints. Extracted from
 * server.js (with an injectable `launchBrowser`) so the orchestration invariants - acquire BEFORE
 * the try so a BUSY reject never reaches release(), context closed + permit released in finally,
 * idle-gated recycling that never kills an in-flight render - are unit-testable without Chromium.
 */
class RenderPool {
  constructor({ launchBrowser, maxConcurrent = 4, maxQueue = 16, recycleAfter = 500 }) {
    if (typeof launchBrowser !== 'function') {
      throw new TypeError('RenderPool requires a launchBrowser function');
    }
    this.launchBrowser = launchBrowser;
    this.recycleAfter = recycleAfter;
    this.sem = new Semaphore(maxConcurrent, maxQueue);
    this.browserPromise = null;
    this.renderCount = 0;   // renders served by the CURRENT browser (drives recycling)
    this.recycling = false;
  }

  async getBrowser() {
    if (!this.browserPromise) {
      this.renderCount = 0;
      const p = Promise.resolve(this.launchBrowser());
      this.browserPromise = p.then((b) => {
        if (b && typeof b.on === 'function') {
          b.on('disconnected', () => { if (this.browserPromise === p) this.browserPromise = null; });
        }
        return b;
      });
    }
    return this.browserPromise;
  }

  // Recycle once the current browser has served enough requests, but ONLY while idle so an
  // in-flight render is never killed, and guarded against re-entry.
  async maybeRecycle() {
    if (this.recycling || this.sem.inflight > 0 || !this.browserPromise) return;
    if (this.renderCount < this.recycleAfter) return;
    this.recycling = true;
    const p = this.browserPromise;
    this.browserPromise = null;
    try {
      const b = await p;
      if (b && typeof b.close === 'function') await b.close();
    } catch (_) {
      /* already gone */
    } finally {
      this.recycling = false;
    }
  }

  /**
   * Run `fn(page)` in a fresh isolated context under the concurrency guard. Acquire happens BEFORE
   * the try, so a BUSY rejection propagates without ever hitting release() (permit accounting stays
   * balanced). The context is always closed and the permit always released in finally.
   */
  async withContext({ viewport, deviceScaleFactor, initScript } = {}, fn) {
    await this.sem.acquire(); // rejects { code:'BUSY' } when saturated - NOT inside the try below
    let context;
    try {
      const browser = await this.getBrowser();
      context = await browser.newContext({
        viewport: viewport || { width: 1280, height: 800 },
        deviceScaleFactor: deviceScaleFactor || 1,
      });
      // Must be installed BEFORE the page exists so it runs ahead of any page script,
      // in the main document AND every iframe (srcdoc included).
      if (initScript && typeof context.addInitScript === 'function') {
        await context.addInitScript({ content: initScript });
      }
      const page = await context.newPage();
      const out = await fn(page);
      this.renderCount += 1;
      return out;
    } finally {
      if (context) {
        try { await context.close(); } catch (_) { /* context already gone */ }
      }
      this.sem.release();
      this.maybeRecycle().catch(() => {});
    }
  }

  /**
   * Run `fn(page)` in a fresh context that RECORDS VIDEO, under the same concurrency guard.
   * Playwright only finalises the webm when the context closes, so unlike withContext this
   * method closes the context itself (inside the try) and returns the finished video's file
   * path. The caller owns the file (read + delete). Returns null when the page produced no
   * video (should not happen for a successfully created page).
   */
  async withVideoContext({ viewport, videoDir }, fn) {
    await this.sem.acquire(); // BUSY rejection must not reach release(), same as withContext
    let context;
    try {
      const browser = await this.getBrowser();
      context = await browser.newContext({
        viewport,
        recordVideo: { dir: videoDir, size: viewport },
      });
      const page = await context.newPage();
      await fn(page);
      const video = page.video ? page.video() : null;
      // Finalise the recording. Clear `context` first so the finally never double-closes.
      const toClose = context;
      context = null;
      await toClose.close();
      this.renderCount += 1;
      return video ? await video.path() : null;
    } finally {
      if (context) {
        try { await context.close(); } catch (_) { /* context already gone */ }
      }
      this.sem.release();
      this.maybeRecycle().catch(() => {});
    }
  }

  health() {
    return {
      status: this.browserPromise ? 'warm' : 'cold',
      inflight: this.sem.inflight,
      queued: this.sem.queued,
      renderCount: this.renderCount,
    };
  }

  async shutdown() {
    if (this.browserPromise) {
      try {
        const b = await this.browserPromise;
        if (b && typeof b.close === 'function') await b.close();
      } catch (_) { /* already gone */ }
      this.browserPromise = null;
    }
  }
}

// ---- media (POST /internal/media) ------------------------------------------
// Pure spec validation + ffmpeg/ffprobe argv builders for the core:media node's
// audio operations (probe / mux_audio / mix / extract_audio). The impure side
// (multipart parsing, running ffmpeg, temp files) lives in media.js; server.js
// wires both into Express. Contract: scratchpad core-media-contract.md, section
// "Renderer HTTP API" - field names are cross-layer FINAL.

const MEDIA_OPERATIONS = new Set([
  'probe', 'mux_audio', 'mix', 'extract_audio', 'concat', 'frame', 'overlay',
]);
const MEDIA_INPUT_ROLES = new Set(['video', 'audio', 'input', 'image']);
const AUDIO_OUTPUT_FORMATS = new Set(['mp3', 'wav', 'aac']);
const AUDIO_FIT_MODES = new Set(['pad', 'shortest', 'loop']);
// One codec + mime per output format; wav is PCM so it takes no -b:a.
const MEDIA_AUDIO_CODECS = { mp3: 'libmp3lame', wav: 'pcm_s16le', aac: 'aac' };
const MEDIA_MIME_TYPES = {
  mp4: 'video/mp4', mp3: 'audio/mpeg', wav: 'audio/wav', aac: 'audio/aac',
  jpg: 'image/jpeg', png: 'image/png',
};
const MEDIA_PART_NAME_PATTERN = /^input\d+$/;
const MEDIA_TRACK_ID_PATTERN = /^[A-Za-z0-9_-]+$/;
const MEDIA_BITRATE_PATTERN = /^\d{1,4}k$/i;
const MAX_MIX_TRACKS = 8;
const MAX_MEDIA_INPUT_BYTES = 300 * 1024 * 1024;
const DEFAULT_AUDIO_BITRATE = '192k';
// normalize:true means single-pass loudnorm at this LUFS integrated target (TP fixed -1.5).
const DEFAULT_LOUDNORM_LUFS = -16;
// Per-op budget: max(60s, 3s per input-second measured by ffprobe), hard cap 300s.
const MEDIA_TIMEOUT_MIN_MS = 60000;
const MEDIA_TIMEOUT_PER_INPUT_SECOND_MS = 3000;
const MEDIA_TIMEOUT_MAX_MS = 300000;
// 422 FFMPEG_FAILED responses carry at most this much of ffmpeg's stderr.
const MEDIA_STDERR_TAIL_BYTES = 2048;
// Response headers the orchestrator's MediaRenderService reads back - literals are
// a cross-layer contract, same discipline as the X-Render-* pair above.
const MEDIA_HEADER_DURATION = 'X-Media-Duration-Seconds';
const MEDIA_HEADER_OPERATION = 'X-Media-Operation';
// frame only: the ACTUAL timestamp used after the default-middle / clamp rules.
const MEDIA_HEADER_TIMESTAMP = 'X-Media-Timestamp-Seconds';

// ---- media v2 (concat / frame / overlay) constants ---------------------------
const CONCAT_MAX_INPUTS = 8;
const CONCAT_TRANSITIONS = new Set(['cut', 'crossfade']);
// fps homogeneity tolerance for the concat fast-copy path (contract: 0.01).
const CONCAT_FPS_TOLERANCE = 0.01;
const FRAME_IMAGE_FORMATS = new Set(['jpeg', 'png']);
const OVERLAY_POSITIONS = new Set(['top_left', 'top_right', 'bottom_left', 'bottom_right', 'center']);
// ffprobe format_name tokens that identify a STILL IMAGE input for overlay. A real
// video container (mov,mp4,... / matroska / mp3) is rejected 422 by the overlay op.
const IMAGE_FORMAT_NAMES = new Set([
  'png_pipe', 'jpeg_pipe', 'image2', 'mjpeg', 'webp_pipe', 'gif', 'bmp_pipe', 'tiff_pipe',
]);

/**
 * Build an Error carrying the HTTP status + machine code the /internal/media handler
 * answers with ({error, code} JSON, plus stderr_tail for FFMPEG_FAILED).
 */
function mediaError(status, code, message, extra) {
  const err = new Error(message);
  err.status = status;
  err.code = code;
  if (extra && extra.stderrTail) err.stderrTail = extra.stderrTail;
  return err;
}

/**
 * Per-operation ffmpeg budget from the summed input durations (quick ffprobe, seconds).
 * Unknown/zero duration falls back to the 60s floor; the 300s cap bounds a hostile or
 * absurdly long input either way.
 */
function computeMediaTimeoutMs(totalInputSeconds) {
  const s = Number(totalInputSeconds);
  const scaled = (Number.isFinite(s) && s > 0) ? Math.round(s * MEDIA_TIMEOUT_PER_INPUT_SECOND_MS) : 0;
  return Math.min(MEDIA_TIMEOUT_MAX_MS, Math.max(MEDIA_TIMEOUT_MIN_MS, scaled));
}

// Filter-graph numbers: up to 3 decimals, no trailing zeros ('0.8', '7', '-16').
function fmtMediaNum(n) {
  return String(Math.round(n * 1000) / 1000);
}

// libx264 + yuv420p rejects odd dimensions - contract rounds DOWN to even.
function evenFloor(n) {
  return Math.floor(n / 2) * 2;
}

function badSpec(code, error) {
  return { ok: false, code, error };
}

// Optional numeric param: absent -> def; present -> finite number within [min, max].
function optNum(v, name, min, max, def) {
  if (v === undefined || v === null) return { value: def };
  const n = Number(v);
  if (!Number.isFinite(n) || n < min || n > max) {
    const range = max === Infinity ? `>= ${min}` : `${min}-${max}`;
    return { error: `${name} must be a number ${range}` };
  }
  return { value: n };
}

// normalize: true/absent -> default LUFS target, false -> null (no loudnorm),
// number -> that LUFS target within [-70, -5].
function resolveNormalize(v) {
  if (v === undefined || v === null || v === true) return { value: DEFAULT_LOUDNORM_LUFS };
  if (v === false) return { value: null };
  const n = Number(v);
  if (!Number.isFinite(n) || n < -70 || n > -5) {
    return { error: 'normalize must be true, false, or a LUFS target between -70 and -5' };
  }
  return { value: n };
}

function resolveBitrate(v) {
  if (v === undefined || v === null) return { value: DEFAULT_AUDIO_BITRATE };
  if (typeof v !== 'string' || !MEDIA_BITRATE_PATTERN.test(v.trim())) {
    return { error: "audio_bitrate must look like '192k'" };
  }
  return { value: v.trim().toLowerCase() };
}

function resolveAudioFit(v) {
  if (v === undefined || v === null) return { value: 'pad' };
  if (typeof v !== 'string' || !AUDIO_FIT_MODES.has(v.toLowerCase())) {
    return { error: `audio_fit must be one of: ${[...AUDIO_FIT_MODES].join(', ')}` };
  }
  return { value: v.toLowerCase() };
}

// trim_start_seconds / trim_end_seconds pair (both optional, end > start when both set).
function resolveTrims(o, label) {
  const start = optNum(o.trim_start_seconds, `${label}trim_start_seconds`, 0, Infinity, null);
  if (start.error) return start;
  const end = optNum(o.trim_end_seconds, `${label}trim_end_seconds`, 0, Infinity, null);
  if (end.error) return end;
  if (start.value !== null && end.value !== null && end.value <= start.value) {
    return { error: `${label}trim_end_seconds must be greater than trim_start_seconds` };
  }
  return { value: { trimStart: start.value, trimEnd: end.value } };
}

function validateMediaInputs(inputs) {
  if (!Array.isArray(inputs) || inputs.length === 0) {
    return { error: 'inputs must be a non-empty array of {name, role}' };
  }
  const seen = new Set();
  const out = [];
  for (const inp of inputs) {
    if (!inp || typeof inp !== 'object' || typeof inp.name !== 'string'
        || !MEDIA_PART_NAME_PATTERN.test(inp.name)) {
      return { error: "each input needs a name matching input<N> (e.g. 'input0')" };
    }
    const role = (inp.role === undefined || inp.role === null) ? 'input' : inp.role;
    if (typeof role !== 'string' || !MEDIA_INPUT_ROLES.has(role)) {
      return { error: `input role must be one of: ${[...MEDIA_INPUT_ROLES].join(', ')}` };
    }
    if (seen.has(inp.name)) return { error: `duplicate input part name '${inp.name}'` };
    seen.add(inp.name);
    out.push({ name: inp.name, role });
  }
  return { value: out };
}

/**
 * Validate + normalise a /internal/media spec ({operation, options, inputs}).
 * Returns { ok:true, value:{operation, inputs, options} } (options normalised to
 * camelCase with all defaults applied) or { ok:false, error, code } for a 400.
 */
function validateMediaSpec(spec) {
  if (!spec || typeof spec !== 'object' || Array.isArray(spec)) {
    return badSpec('INVALID_SPEC', 'spec must be a JSON object with {operation, options, inputs}');
  }
  const operation = spec.operation;
  if (typeof operation !== 'string' || !MEDIA_OPERATIONS.has(operation)) {
    return badSpec('UNKNOWN_OPERATION', `operation must be one of: ${[...MEDIA_OPERATIONS].join(', ')}`);
  }
  const inputsV = validateMediaInputs(spec.inputs);
  if (inputsV.error) return badSpec('INVALID_SPEC', inputsV.error);
  const inputs = inputsV.value;
  const o = (spec.options && typeof spec.options === 'object' && !Array.isArray(spec.options))
    ? spec.options
    : {};

  if (operation === 'probe') {
    if (inputs.length !== 1) return badSpec('INVALID_SPEC', 'probe takes exactly one input part');
    return { ok: true, value: { operation, inputs, options: {} } };
  }

  if (operation === 'extract_audio') {
    if (inputs.length !== 1) return badSpec('INVALID_SPEC', 'extract_audio takes exactly one input part');
    const fmt = (o.output_format === undefined || o.output_format === null) ? 'mp3' : o.output_format;
    if (typeof fmt !== 'string' || !AUDIO_OUTPUT_FORMATS.has(fmt.toLowerCase())) {
      return badSpec('VALUE_OUT_OF_RANGE', `output_format must be one of: ${[...AUDIO_OUTPUT_FORMATS].join(', ')}`);
    }
    const bitrate = resolveBitrate(o.audio_bitrate);
    if (bitrate.error) return badSpec('VALUE_OUT_OF_RANGE', bitrate.error);
    const trims = resolveTrims(o, '');
    if (trims.error) return badSpec('VALUE_OUT_OF_RANGE', trims.error);
    return {
      ok: true,
      value: {
        operation,
        inputs,
        options: { outputFormat: fmt.toLowerCase(), audioBitrate: bitrate.value, ...trims.value },
      },
    };
  }

  if (operation === 'concat') {
    // Per-clip settings: options.inputs mirrors the node's `inputs` param minus `source`
    // (source_part names the uploaded part, mix's tracks[] pattern; array order = concat
    // order, inputs[i] -> part input{i} per the v2 contract). Absent -> one default item
    // per uploaded part in input<N> numeric order. Only STRUCTURAL problems are 400s here;
    // the contract's concat preflight cases (1-8 clips, speed 0.5-2, trim_end>trim_start,
    // crossfade>=2 inputs, transition vs effective durations, target dims XOR) are 422
    // FFMPEG_FAILED, thrown by validateConcatStatic/planConcat before ffmpeg runs.
    const rawItems = o.inputs;
    let items;
    if (rawItems === undefined || rawItems === null) {
      items = inputs
        .slice()
        .sort((a, b) => Number(a.name.slice(5)) - Number(b.name.slice(5)))
        .map((inp) => ({ sourcePart: inp.name, trimStart: null, trimEnd: null, speed: 1 }));
    } else {
      if (!Array.isArray(rawItems)) {
        return badSpec('INVALID_SPEC', 'options.inputs must be an array of per-clip objects');
      }
      const inputNames = new Set(inputs.map((i) => i.name));
      const used = new Set();
      items = [];
      for (let i = 0; i < rawItems.length; i++) {
        const it = rawItems[i];
        const label = `inputs[${i}].`;
        if (!it || typeof it !== 'object' || Array.isArray(it)) {
          return badSpec('INVALID_SPEC', `inputs[${i}] must be an object`);
        }
        if (typeof it.source_part !== 'string' || !inputNames.has(it.source_part)) {
          // Deliberate 400, not the contract's 422 "a source missing": that 422 is the
          // NODE layer's job (a user-provided `source` FileRef that didn't resolve).
          // By the time a spec reaches the renderer, a dangling source_part means the
          // orchestrator built a malformed request - a spec violation, like v1's tracks.
          return badSpec('INVALID_SPEC', `${label}source_part must name an uploaded input part (input0..inputN)`);
        }
        if (used.has(it.source_part)) {
          return badSpec('INVALID_SPEC', `${label}source_part '${it.source_part}' is already used by another clip`);
        }
        used.add(it.source_part);
        const trimStart = optNum(it.trim_start_seconds, `${label}trim_start_seconds`, 0, Infinity, null);
        if (trimStart.error) return badSpec('VALUE_OUT_OF_RANGE', trimStart.error);
        const trimEnd = optNum(it.trim_end_seconds, `${label}trim_end_seconds`, 0, Infinity, null);
        if (trimEnd.error) return badSpec('VALUE_OUT_OF_RANGE', trimEnd.error);
        // The 0.5-2.0 RANGE is a contract 422 case - only the type is checked here.
        let speed = 1;
        if (it.speed !== undefined && it.speed !== null) {
          speed = Number(it.speed);
          if (!Number.isFinite(speed)) {
            return badSpec('VALUE_OUT_OF_RANGE', `${label}speed must be a number`);
          }
        }
        items.push({ sourcePart: it.source_part, trimStart: trimStart.value, trimEnd: trimEnd.value, speed });
      }
    }
    const transitionRaw = (o.transition === undefined || o.transition === null) ? 'cut' : o.transition;
    if (typeof transitionRaw !== 'string' || !CONCAT_TRANSITIONS.has(transitionRaw.toLowerCase())) {
      return badSpec('VALUE_OUT_OF_RANGE', `transition must be one of: ${[...CONCAT_TRANSITIONS].join(', ')}`);
    }
    const transitionSeconds = optNum(o.transition_seconds, 'transition_seconds', 0.1, 5, 0.5);
    if (transitionSeconds.error) return badSpec('VALUE_OUT_OF_RANGE', transitionSeconds.error);
    const targetWidth = optNum(o.target_width, 'target_width', 16, 4096, null);
    if (targetWidth.error) return badSpec('VALUE_OUT_OF_RANGE', targetWidth.error);
    const targetHeight = optNum(o.target_height, 'target_height', 16, 4096, null);
    if (targetHeight.error) return badSpec('VALUE_OUT_OF_RANGE', targetHeight.error);
    const targetFps = optNum(o.target_fps, 'target_fps', 1, 60, null);
    if (targetFps.error) return badSpec('VALUE_OUT_OF_RANGE', targetFps.error);
    const fadeIn = optNum(o.fade_in_seconds, 'fade_in_seconds', 0, Infinity, 0);
    if (fadeIn.error) return badSpec('VALUE_OUT_OF_RANGE', fadeIn.error);
    // Default 0, NOT mux_audio's 1 - deliberate contract difference.
    const fadeOut = optNum(o.fade_out_seconds, 'fade_out_seconds', 0, Infinity, 0);
    if (fadeOut.error) return badSpec('VALUE_OUT_OF_RANGE', fadeOut.error);
    // concat defaults normalize to FALSE (mux/mix default true): loudnorm forces the
    // re-encode path, so evening out loudness between clips must be an explicit opt-in.
    const normalize = (o.normalize === undefined || o.normalize === null)
      ? { value: null }
      : resolveNormalize(o.normalize);
    if (normalize.error) return badSpec('VALUE_OUT_OF_RANGE', normalize.error);
    const bitrate = resolveBitrate(o.audio_bitrate);
    if (bitrate.error) return badSpec('VALUE_OUT_OF_RANGE', bitrate.error);
    return {
      ok: true,
      value: {
        operation,
        inputs,
        options: {
          items,
          transition: transitionRaw.toLowerCase(),
          transitionSeconds: transitionSeconds.value,
          // Contract: even output dimensions enforced by rounding DOWN.
          targetWidth: targetWidth.value !== null ? evenFloor(targetWidth.value) : null,
          targetHeight: targetHeight.value !== null ? evenFloor(targetHeight.value) : null,
          targetFps: targetFps.value,
          fadeIn: fadeIn.value,
          fadeOut: fadeOut.value,
          normalizeLufs: normalize.value,
          audioBitrate: bitrate.value,
        },
      },
    };
  }

  if (operation === 'frame') {
    if (inputs.length !== 1) return badSpec('INVALID_SPEC', 'frame takes exactly one input part');
    const at = optNum(o.at_seconds, 'at_seconds', 0, Infinity, null);
    if (at.error) return badSpec('VALUE_OUT_OF_RANGE', at.error);
    const fmtRaw = (o.image_format === undefined || o.image_format === null) ? 'jpeg' : o.image_format;
    if (typeof fmtRaw !== 'string' || !FRAME_IMAGE_FORMATS.has(fmtRaw.toLowerCase())) {
      return badSpec('VALUE_OUT_OF_RANGE', `image_format must be one of: ${[...FRAME_IMAGE_FORMATS].join(', ')}`);
    }
    const width = optNum(o.width, 'width', 16, 4096, null);
    if (width.error) return badSpec('VALUE_OUT_OF_RANGE', width.error);
    return {
      ok: true,
      value: {
        operation,
        inputs,
        options: {
          atSeconds: at.value,
          imageFormat: fmtRaw.toLowerCase(),
          width: width.value !== null ? Math.round(width.value) : null,
        },
      },
    };
  }

  if (operation === 'overlay') {
    const video = inputs.find((i) => i.role === 'video');
    const image = inputs.find((i) => i.role === 'image');
    if (!video || !image) {
      return badSpec('INVALID_SPEC', "overlay needs one input with role 'video' and one with role 'image'");
    }
    const posRaw = (o.position === undefined || o.position === null) ? 'bottom_right' : o.position;
    if (typeof posRaw !== 'string' || !OVERLAY_POSITIONS.has(posRaw.toLowerCase())) {
      return badSpec('VALUE_OUT_OF_RANGE', `position must be one of: ${[...OVERLAY_POSITIONS].join(', ')}`);
    }
    const margin = optNum(o.margin_px, 'margin_px', 0, Infinity, 24);
    if (margin.error) return badSpec('VALUE_OUT_OF_RANGE', margin.error);
    const widthPercent = optNum(o.width_percent, 'width_percent', 1, 100, 15);
    if (widthPercent.error) return badSpec('VALUE_OUT_OF_RANGE', widthPercent.error);
    const opacity = optNum(o.opacity, 'opacity', 0, 1, 1);
    if (opacity.error) return badSpec('VALUE_OUT_OF_RANGE', opacity.error);
    const start = optNum(o.start_seconds, 'start_seconds', 0, Infinity, null);
    if (start.error) return badSpec('VALUE_OUT_OF_RANGE', start.error);
    const end = optNum(o.end_seconds, 'end_seconds', 0, Infinity, null);
    if (end.error) return badSpec('VALUE_OUT_OF_RANGE', end.error);
    if (start.value !== null && end.value !== null && end.value <= start.value) {
      return badSpec('VALUE_OUT_OF_RANGE', 'end_seconds must be greater than start_seconds');
    }
    return {
      ok: true,
      value: {
        operation,
        inputs,
        options: {
          position: posRaw.toLowerCase(),
          marginPx: margin.value,
          widthPercent: widthPercent.value,
          opacity: opacity.value,
          startSeconds: start.value,
          endSeconds: end.value,
        },
      },
    };
  }

  if (operation === 'mux_audio') {
    const video = inputs.find((i) => i.role === 'video');
    const audio = inputs.find((i) => i.role === 'audio');
    if (!video || !audio) {
      return badSpec('INVALID_SPEC', "mux_audio needs one input with role 'video' and one with role 'audio'");
    }
    const volume = optNum(o.volume, 'volume', 0, 400, 100);
    if (volume.error) return badSpec('VALUE_OUT_OF_RANGE', volume.error);
    const offset = optNum(o.offset_seconds, 'offset_seconds', 0, Infinity, 0);
    if (offset.error) return badSpec('VALUE_OUT_OF_RANGE', offset.error);
    const trims = resolveTrims(o, '');
    if (trims.error) return badSpec('VALUE_OUT_OF_RANGE', trims.error);
    const fadeIn = optNum(o.fade_in_seconds, 'fade_in_seconds', 0, Infinity, 0);
    if (fadeIn.error) return badSpec('VALUE_OUT_OF_RANGE', fadeIn.error);
    const fadeOut = optNum(o.fade_out_seconds, 'fade_out_seconds', 0, Infinity, 1);
    if (fadeOut.error) return badSpec('VALUE_OUT_OF_RANGE', fadeOut.error);
    const originalVolume = optNum(o.original_volume, 'original_volume', 0, 400, 100);
    if (originalVolume.error) return badSpec('VALUE_OUT_OF_RANGE', originalVolume.error);
    const audioFit = resolveAudioFit(o.audio_fit);
    if (audioFit.error) return badSpec('VALUE_OUT_OF_RANGE', audioFit.error);
    const normalize = resolveNormalize(o.normalize);
    if (normalize.error) return badSpec('VALUE_OUT_OF_RANGE', normalize.error);
    const bitrate = resolveBitrate(o.audio_bitrate);
    if (bitrate.error) return badSpec('VALUE_OUT_OF_RANGE', bitrate.error);
    const loop = o.loop === true || audioFit.value === 'loop';
    if (loop && (trims.value.trimStart !== null || trims.value.trimEnd !== null)) {
      // Filter-based looping of a trimmed segment would buffer the whole segment in RAM
      // (aloop); the -stream_loop route can only loop the WHOLE input. Explicit failure
      // beats a silently un-looped trim.
      return badSpec('INVALID_SPEC', 'loop (or audio_fit=loop) cannot be combined with trim_start_seconds/trim_end_seconds');
    }
    return {
      ok: true,
      value: {
        operation,
        inputs,
        options: {
          volume: volume.value,
          offsetSeconds: offset.value,
          trimStart: trims.value.trimStart,
          trimEnd: trims.value.trimEnd,
          loop,
          fadeIn: fadeIn.value,
          fadeOut: fadeOut.value,
          keepOriginalAudio: o.keep_original_audio === true,
          originalVolume: originalVolume.value,
          audioFit: audioFit.value,
          normalizeLufs: normalize.value,
          audioBitrate: bitrate.value,
        },
      },
    };
  }

  // mix
  const videoInputs = inputs.filter((i) => i.role === 'video');
  if (videoInputs.length > 1) return badSpec('INVALID_SPEC', 'mix takes at most one video input');
  const hasVideo = videoInputs.length === 1;
  if (!Array.isArray(o.tracks) || o.tracks.length < 1 || o.tracks.length > MAX_MIX_TRACKS) {
    return badSpec('TRACKS_LIMIT', `tracks must be an array of 1-${MAX_MIX_TRACKS} track objects`);
  }
  const inputNames = new Set(inputs.map((i) => i.name));
  const usedSources = new Set();
  const trackIds = new Set();
  const tracks = [];
  for (let i = 0; i < o.tracks.length; i++) {
    const t = o.tracks[i];
    const label = `tracks[${i}].`;
    if (!t || typeof t !== 'object' || Array.isArray(t)) {
      return badSpec('INVALID_SPEC', `${label.slice(0, -1)} must be an object`);
    }
    if (typeof t.source_part !== 'string' || !inputNames.has(t.source_part)) {
      return badSpec('INVALID_SPEC', `${label}source_part must name an uploaded input part (input0..inputN)`);
    }
    if (usedSources.has(t.source_part)) {
      return badSpec('INVALID_SPEC', `${label}source_part '${t.source_part}' is already used by another track (one input part per track)`);
    }
    usedSources.add(t.source_part);
    const id = (t.id === undefined || t.id === null) ? `track_${i + 1}` : t.id;
    if (typeof id !== 'string' || !MEDIA_TRACK_ID_PATTERN.test(id)) {
      return badSpec('INVALID_SPEC', `${label}id must be alphanumeric/underscore/dash`);
    }
    if (trackIds.has(id)) return badSpec('INVALID_SPEC', `duplicate track id '${id}'`);
    trackIds.add(id);
    const volume = optNum(t.volume, `${label}volume`, 0, 400, 100);
    if (volume.error) return badSpec('VALUE_OUT_OF_RANGE', volume.error);
    const offset = optNum(t.offset_seconds, `${label}offset_seconds`, 0, Infinity, 0);
    if (offset.error) return badSpec('VALUE_OUT_OF_RANGE', offset.error);
    const trims = resolveTrims(t, label);
    if (trims.error) return badSpec('VALUE_OUT_OF_RANGE', trims.error);
    const fadeIn = optNum(t.fade_in_seconds, `${label}fade_in_seconds`, 0, Infinity, 0);
    if (fadeIn.error) return badSpec('VALUE_OUT_OF_RANGE', fadeIn.error);
    const fadeOut = optNum(t.fade_out_seconds, `${label}fade_out_seconds`, 0, Infinity, 0);
    if (fadeOut.error) return badSpec('VALUE_OUT_OF_RANGE', fadeOut.error);
    const speed = optNum(t.speed, `${label}speed`, 0.5, 2, 1);
    if (speed.error) return badSpec('VALUE_OUT_OF_RANGE', speed.error);
    const duckAmount = optNum(t.duck_amount_db, `${label}duck_amount_db`, 1, 60, 12);
    if (duckAmount.error) return badSpec('VALUE_OUT_OF_RANGE', duckAmount.error);
    const duckAttack = optNum(t.duck_attack_ms, `${label}duck_attack_ms`, 1, 2000, 20);
    if (duckAttack.error) return badSpec('VALUE_OUT_OF_RANGE', duckAttack.error);
    const duckRelease = optNum(t.duck_release_ms, `${label}duck_release_ms`, 1, 10000, 300);
    if (duckRelease.error) return badSpec('VALUE_OUT_OF_RANGE', duckRelease.error);
    const loop = t.loop === true;
    if (loop && (trims.value.trimStart !== null || trims.value.trimEnd !== null)) {
      return badSpec('INVALID_SPEC', `${label}loop cannot be combined with trim_start_seconds/trim_end_seconds`);
    }
    let duckUnder = null;
    if (t.duck_under !== undefined && t.duck_under !== null) {
      if (typeof t.duck_under !== 'string' || t.duck_under.length === 0) {
        return badSpec('DUCK_REF_INVALID', `${label}duck_under must be the id of another track`);
      }
      duckUnder = t.duck_under;
    }
    tracks.push({
      id,
      sourcePart: t.source_part,
      volume: volume.value,
      offsetSeconds: offset.value,
      trimStart: trims.value.trimStart,
      trimEnd: trims.value.trimEnd,
      loop,
      fadeIn: fadeIn.value,
      fadeOut: fadeOut.value,
      speed: speed.value,
      duckUnder,
      duckAmountDb: duckAmount.value,
      duckAttackMs: duckAttack.value,
      duckReleaseMs: duckRelease.value,
    });
  }
  for (const t of tracks) {
    if (t.duckUnder === null) continue;
    if (t.duckUnder === t.id) {
      return badSpec('DUCK_REF_INVALID', `track '${t.id}' cannot duck under itself`);
    }
    if (!trackIds.has(t.duckUnder)) {
      return badSpec('DUCK_REF_INVALID', `duck_under '${t.duckUnder}' does not match any track id`);
    }
  }
  if (!hasVideo && tracks.every((t) => t.loop)) {
    return badSpec('INVALID_SPEC', 'an audio-only mix needs at least one non-looping track to define the output length');
  }
  const audioFit = resolveAudioFit(o.audio_fit);
  if (audioFit.error) return badSpec('VALUE_OUT_OF_RANGE', audioFit.error);
  const normalize = resolveNormalize(o.normalize);
  if (normalize.error) return badSpec('VALUE_OUT_OF_RANGE', normalize.error);
  const bitrate = resolveBitrate(o.audio_bitrate);
  if (bitrate.error) return badSpec('VALUE_OUT_OF_RANGE', bitrate.error);
  const originalVolume = optNum(o.original_volume, 'original_volume', 0, 400, 100);
  if (originalVolume.error) return badSpec('VALUE_OUT_OF_RANGE', originalVolume.error);
  let outputFormat = 'mp4';
  if (!hasVideo) {
    const fmt = (o.output_format === undefined || o.output_format === null) ? 'mp3' : o.output_format;
    if (typeof fmt !== 'string' || !AUDIO_OUTPUT_FORMATS.has(fmt.toLowerCase())) {
      return badSpec('VALUE_OUT_OF_RANGE', `output_format must be one of: ${[...AUDIO_OUTPUT_FORMATS].join(', ')} (mp4 is forced when a video input is present)`);
    }
    outputFormat = fmt.toLowerCase();
  }
  return {
    ok: true,
    value: {
      operation,
      inputs,
      options: {
        tracks,
        hasVideo,
        // keep_original_audio only means something when a video (and its audio) exists
        keepOriginalAudio: hasVideo && o.keep_original_audio === true,
        originalVolume: originalVolume.value,
        audioFit: audioFit.value,
        normalizeLufs: normalize.value,
        audioBitrate: bitrate.value,
        outputFormat,
      },
    },
  };
}

/** ffprobe argv for the probe operation (and the quick per-input duration probe reuses -show_format). */
function buildProbeArgs(inputPath) {
  return ['-v', 'error', '-show_format', '-show_streams', '-of', 'json', inputPath];
}

// "30000/1001" -> 29.97 (3 decimals). Bare number strings pass through.
function parseFpsFraction(rFrameRate) {
  if (typeof rFrameRate !== 'string' || rFrameRate.length === 0) return 0;
  const [num, den] = rFrameRate.split('/').map(Number);
  if (!Number.isFinite(num)) return 0;
  const fps = (den === undefined) ? num : (Number.isFinite(den) && den !== 0 ? num / den : 0);
  return Math.round(fps * 1000) / 1000;
}

/**
 * Transform raw `ffprobe -show_format -show_streams -of json` output into the contract's
 * probe response shape. Cover art embedded in audio files (attached_pic) does NOT count
 * as a video stream - an mp3 with artwork must report has_video:false.
 */
function transformProbeJson(raw) {
  const format = (raw && raw.format) || {};
  const streams = Array.isArray(raw && raw.streams) ? raw.streams : [];
  const toNum = (x) => { const n = Number(x); return Number.isFinite(n) ? n : null; };
  const v = streams.find((s) => s && s.codec_type === 'video'
    && !(s.disposition && Number(s.disposition.attached_pic) === 1));
  const a = streams.find((s) => s && s.codec_type === 'audio');
  return {
    duration_seconds: toNum(format.duration) || 0,
    size_bytes: toNum(format.size) || 0,
    format_name: typeof format.format_name === 'string' ? format.format_name : '',
    bit_rate: toNum(format.bit_rate),
    has_video: !!v,
    has_audio: !!a,
    video: v ? {
      codec: typeof v.codec_name === 'string' ? v.codec_name : '',
      width: toNum(v.width) || 0,
      height: toNum(v.height) || 0,
      fps: parseFpsFraction(v.r_frame_rate),
    } : null,
    audio: a ? {
      codec: typeof a.codec_name === 'string' ? a.codec_name : '',
      sample_rate: toNum(a.sample_rate) || 0,
      channels: toNum(a.channels) || 0,
    } : null,
  };
}

// atempo only accepts 0.5-2 per instance; validated speed always fits one, the chain is defensive.
function atempoChain(speed) {
  const steps = [];
  let s = speed;
  while (s < 0.5) { steps.push('atempo=0.5'); s /= 0.5; }
  while (s > 2) { steps.push('atempo=2'); s /= 2; }
  if (s !== 1) steps.push(`atempo=${fmtMediaNum(s)}`);
  return steps;
}

// Source segment length in seconds after trims (null when the duration is unknown).
function trackSegmentSeconds(t, durationSeconds) {
  const start = t.trimStart !== null && t.trimStart !== undefined ? t.trimStart : 0;
  let end = (t.trimEnd !== null && t.trimEnd !== undefined) ? t.trimEnd : durationSeconds;
  if (end !== null && end !== undefined && durationSeconds !== null && durationSeconds !== undefined) {
    end = Math.min(end, durationSeconds);
  }
  if (end === null || end === undefined) return null;
  return Math.max(0, end - start);
}

// How long this track is actually audible (post-speed, pre-delay) - anchors the fade-out.
function trackAudibleSeconds(t, durationSeconds, targetDurationSeconds) {
  const speed = (t.speed !== null && t.speed !== undefined) ? t.speed : 1;
  if (t.loop) {
    if (targetDurationSeconds === null || targetDurationSeconds === undefined) return null;
    return Math.max(0, targetDurationSeconds - t.offsetSeconds);
  }
  const seg = trackSegmentSeconds(t, durationSeconds);
  if (seg === null) return null;
  const eff = seg / speed;
  if (targetDurationSeconds === null || targetDurationSeconds === undefined) return eff;
  return Math.min(eff, Math.max(0, targetDurationSeconds - t.offsetSeconds));
}

/**
 * Per-track filter steps shared by mux_audio (single implicit track) and mix.
 * Order: trim/loop-cut -> speed -> volume -> fades -> delay. Looped inputs arrive
 * via -stream_loop -1, so the chain cuts them to the span the target needs.
 */
function buildTrackFilterSteps(t, { durationSeconds, targetDurationSeconds }) {
  const steps = [];
  const speed = (t.speed !== null && t.speed !== undefined) ? t.speed : 1;
  if (t.loop) {
    if (targetDurationSeconds === null || targetDurationSeconds === undefined) {
      // 422, not a 500: the anchor input (video or a non-looping track) exists but its
      // duration could not be probed - a client-side input problem, with guidance.
      throw mediaError(422, 'FFMPEG_FAILED',
        'a looped track needs a known target duration, but the anchor input\'s duration could not be read - re-encode the anchor file or drop loop');
    }
    const span = Math.max(0.001, (targetDurationSeconds - t.offsetSeconds) * speed);
    steps.push(`atrim=0:${fmtMediaNum(span)}`, 'asetpts=PTS-STARTPTS');
  } else if (t.trimStart !== null || t.trimEnd !== null) {
    const parts = [`start=${fmtMediaNum(t.trimStart !== null ? t.trimStart : 0)}`];
    if (t.trimEnd !== null) parts.push(`end=${fmtMediaNum(t.trimEnd)}`);
    steps.push(`atrim=${parts.join(':')}`, 'asetpts=PTS-STARTPTS');
  }
  if (speed !== 1) steps.push(...atempoChain(speed));
  if (t.volume !== 100) steps.push(`volume=${fmtMediaNum(t.volume / 100)}`);
  if (t.fadeIn > 0) steps.push(`afade=t=in:st=0:d=${fmtMediaNum(t.fadeIn)}`);
  if (t.fadeOut > 0) {
    const audible = trackAudibleSeconds(t, durationSeconds, targetDurationSeconds);
    if (audible !== null && audible > 0) {
      steps.push(`afade=t=out:st=${fmtMediaNum(Math.max(0, audible - t.fadeOut))}:d=${fmtMediaNum(t.fadeOut)}`);
    }
  }
  if (t.offsetSeconds > 0) steps.push(`adelay=${Math.round(t.offsetSeconds * 1000)}:all=1`);
  return steps;
}

// loudnorm resamples internally to 192kHz, which the aac encoder rejects - always
// pin the rate back down right after it. The aformat pin matters just as much:
// after aresample the channel layout is left undetermined, and a downstream apad
// cannot negotiate it (live failure on a mono mp3: "Cannot select channel layout
// for the link between filters Parsed_aresample and Parsed_apad"). Stereo is a
// safe upmix for mono sources and the natural layout for aac/mp3 output.
function normalizeSteps(normalizeLufs) {
  if (normalizeLufs === null || normalizeLufs === undefined) return [];
  return [`loudnorm=I=${fmtMediaNum(normalizeLufs)}:TP=-1.5`, 'aresample=48000', 'aformat=channel_layouts=stereo'];
}

/**
 * ffmpeg argv for mux_audio (one audio onto one video, `-c:v copy`). `paths` is
 * { parts: { <partName>: { path, durationSeconds } }, outputPath }. Input order is
 * fixed: video is ffmpeg input 0, audio input 1.
 */
function buildMuxAudioArgs(spec, paths) {
  const o = spec.options;
  const videoIn = spec.inputs.find((i) => i.role === 'video');
  const audioIn = spec.inputs.find((i) => i.role === 'audio');
  const video = paths.parts[videoIn.name];
  const audio = paths.parts[audioIn.name];
  const track = {
    volume: o.volume,
    offsetSeconds: o.offsetSeconds,
    trimStart: o.trimStart,
    trimEnd: o.trimEnd,
    loop: o.loop,
    fadeIn: o.fadeIn,
    fadeOut: o.fadeOut,
    speed: 1,
  };
  const steps = buildTrackFilterSteps(track, {
    durationSeconds: audio.durationSeconds,
    targetDurationSeconds: video.durationSeconds,
  });
  const post = normalizeSteps(o.normalizeLufs);
  // pad fills a short audio with silence up to the video's end (-shortest cuts the pad);
  // a looped track already covers the video, so apad would be dead weight.
  if (o.audioFit === 'pad' && !o.loop) post.push('apad');
  let filter;
  if (o.keepOriginalAudio) {
    filter = [
      `[1:a]${steps.join(',') || 'anull'}[anew]`,
      `[0:a]volume=${fmtMediaNum(o.originalVolume / 100)}[aorig]`,
      `[anew][aorig]amix=inputs=2:duration=longest:normalize=0${post.length ? `,${post.join(',')}` : ''}[aout]`,
    ].join(';');
  } else {
    const all = steps.concat(post);
    filter = `[1:a]${all.join(',') || 'anull'}[aout]`;
  }
  const args = ['-nostdin', '-y', '-loglevel', 'error', '-i', video.path];
  if (o.loop) args.push('-stream_loop', '-1');
  args.push('-i', audio.path);
  args.push('-filter_complex', filter);
  args.push('-map', '0:v:0', '-map', '[aout]');
  args.push('-c:v', 'copy', '-c:a', 'aac', '-b:a', o.audioBitrate);
  // -shortest terminates every audio_fit mode: pad's infinite apad and a looped
  // (-stream_loop -1) audio both end exactly where the copied video ends.
  args.push('-shortest', '-movflags', '+faststart', paths.outputPath);
  return args;
}

/**
 * ffmpeg argv for mix (1-8 tracks, optional video). ffmpeg input order follows
 * spec.inputs order; ducking is sidechaincompress keyed by an asplit of the
 * duck-target track; the target mix length is the video's duration when present,
 * else the longest non-looping track.
 */
function buildMixArgs(spec, paths) {
  const o = spec.options;
  const indexByName = {};
  spec.inputs.forEach((inp, i) => { indexByName[inp.name] = i; });
  const videoInput = spec.inputs.find((i) => i.role === 'video') || null;
  const videoIdx = videoInput ? indexByName[videoInput.name] : -1;
  let target = videoInput ? paths.parts[videoInput.name].durationSeconds : null;
  if (target === null || target === undefined) {
    target = 0;
    for (const t of o.tracks) {
      if (t.loop) continue;
      const audible = trackAudibleSeconds(t, paths.parts[t.sourcePart].durationSeconds, null);
      if (audible !== null) target = Math.max(target, t.offsetSeconds + audible);
    }
    if (!(target > 0)) target = null;
  }
  const chains = [];
  const finalLabels = [];
  o.tracks.forEach((t, i) => {
    const src = paths.parts[t.sourcePart];
    const steps = buildTrackFilterSteps(t, {
      durationSeconds: src.durationSeconds,
      targetDurationSeconds: target,
    });
    chains.push(`[${indexByName[t.sourcePart]}:a]${steps.join(',') || 'anull'}[t${i}]`);
    finalLabels[i] = `[t${i}]`;
  });
  // Ducking wiring: the duck TARGET (the track others listen to) is asplit so its signal
  // both plays in the mix and keys each ducker's sidechaincompress.
  const duckerIdxByTargetId = {};
  o.tracks.forEach((t, i) => {
    if (!t.duckUnder) return;
    (duckerIdxByTargetId[t.duckUnder] = duckerIdxByTargetId[t.duckUnder] || []).push(i);
  });
  const sidechainLabelForDucker = {};
  o.tracks.forEach((t, i) => {
    const duckers = duckerIdxByTargetId[t.id];
    if (!duckers || duckers.length === 0) return;
    const scLabels = duckers.map((_, k) => `[t${i}sc${k}]`);
    chains.push(`${finalLabels[i]}asplit=${duckers.length + 1}[t${i}m]${scLabels.join('')}`);
    finalLabels[i] = `[t${i}m]`;
    duckers.forEach((duckerIdx, k) => {
      // sidechaincompress ends at the SHORTER of its two inputs, so a key track that
      // stops early would TRUNCATE the ducked track with it (verified live: a 6s music
      // bed ducked under a 4s voice came out 4s). apad makes the key silent-infinite;
      // the compressor then ends exactly where the ducked (main) track ends.
      const padded = `[t${i}sc${k}p]`;
      chains.push(`${scLabels[k]}apad${padded}`);
      sidechainLabelForDucker[duckerIdx] = padded;
    });
  });
  o.tracks.forEach((t, i) => {
    if (!t.duckUnder) return;
    // duck_amount_db maps to the compressor ratio (12 dB -> 6:1), clamped to ffmpeg's 1-20.
    const ratio = Math.min(20, Math.max(1, t.duckAmountDb / 2));
    chains.push(
      `${finalLabels[i]}${sidechainLabelForDucker[i]}sidechaincompress=threshold=0.05`
      + `:ratio=${fmtMediaNum(ratio)}:attack=${fmtMediaNum(t.duckAttackMs)}:release=${fmtMediaNum(t.duckReleaseMs)}[t${i}d]`,
    );
    finalLabels[i] = `[t${i}d]`;
  });
  const mixInputs = finalLabels.slice();
  if (o.keepOriginalAudio && videoIdx >= 0) {
    chains.push(`[${videoIdx}:a]volume=${fmtMediaNum(o.originalVolume / 100)}[aorig]`);
    mixInputs.push('[aorig]');
  }
  const post = normalizeSteps(o.normalizeLufs);
  const anyLoop = o.tracks.some((t) => t.loop);
  if (videoIdx >= 0 && o.audioFit === 'pad' && !anyLoop) post.push('apad');
  if (mixInputs.length === 1) {
    chains.push(`${mixInputs[0]}${post.join(',') || 'anull'}[aout]`);
  } else {
    chains.push(`${mixInputs.join('')}amix=inputs=${mixInputs.length}:duration=longest:normalize=0${post.length ? `,${post.join(',')}` : ''}[aout]`);
  }
  const loopByName = {};
  o.tracks.forEach((t) => { if (t.loop) loopByName[t.sourcePart] = true; });
  const args = ['-nostdin', '-y', '-loglevel', 'error'];
  spec.inputs.forEach((inp) => {
    if (loopByName[inp.name]) args.push('-stream_loop', '-1');
    args.push('-i', paths.parts[inp.name].path);
  });
  args.push('-filter_complex', chains.join(';'));
  if (videoIdx >= 0) {
    args.push('-map', `${videoIdx}:v:0`, '-map', '[aout]');
    args.push('-c:v', 'copy', '-c:a', 'aac', '-b:a', o.audioBitrate);
    args.push('-shortest', '-movflags', '+faststart', paths.outputPath);
  } else {
    args.push('-map', '[aout]');
    args.push('-c:a', MEDIA_AUDIO_CODECS[o.outputFormat]);
    if (o.outputFormat !== 'wav') args.push('-b:a', o.audioBitrate);
    // Audio-only + a looped (-stream_loop -1) input: nothing downstream ever signals
    // EOF (atrim drops, it does not close), so cap the OUTPUT explicitly or ffmpeg
    // runs until the timeout kill.
    if (anyLoop && target !== null) args.push('-t', fmtMediaNum(target));
    args.push(paths.outputPath);
  }
  return args;
}

/**
 * ffmpeg argv for extract_audio. -ss/-to sit AFTER the input (output options) for
 * sample-accurate trimming; wav is PCM so it takes no bitrate.
 */
function buildExtractAudioArgs(spec, paths) {
  const o = spec.options;
  const input = paths.parts[spec.inputs[0].name];
  const args = ['-nostdin', '-y', '-loglevel', 'error', '-i', input.path];
  if (o.trimStart !== null && o.trimStart !== undefined) args.push('-ss', fmtMediaNum(o.trimStart));
  if (o.trimEnd !== null && o.trimEnd !== undefined) args.push('-to', fmtMediaNum(o.trimEnd));
  args.push('-vn', '-map', '0:a:0');
  args.push('-c:a', MEDIA_AUDIO_CODECS[o.outputFormat]);
  if (o.outputFormat !== 'wav') args.push('-b:a', o.audioBitrate);
  args.push(paths.outputPath);
  return args;
}

// ---- media v2: concat --------------------------------------------------------

/**
 * The video's sample_aspect_ratio from a raw ffprobe JSON ('1:1' when absent or the
 * ffprobe placeholders '0:1'/'N/A'). Feeds the concat copy-path homogeneity check;
 * deliberately NOT part of transformProbeJson so the v1 probe response stays byte-stable.
 */
function extractVideoSar(raw) {
  const streams = Array.isArray(raw && raw.streams) ? raw.streams : [];
  const v = streams.find((s) => s && s.codec_type === 'video'
    && !(s.disposition && Number(s.disposition.attached_pic) === 1));
  const sar = (v && typeof v.sample_aspect_ratio === 'string') ? v.sample_aspect_ratio : '';
  return (!sar || sar === '0:1' || sar === 'N/A') ? '1:1' : sar;
}

// A clip's playable length after trims and speed (probed duration bounds the trim end).
function concatEffectiveSeconds(item, durationSeconds) {
  const start = item.trimStart !== null ? item.trimStart : 0;
  const end = item.trimEnd !== null ? Math.min(item.trimEnd, durationSeconds) : durationSeconds;
  return (end - start) / (item.speed || 1);
}

/**
 * The contract's STATIC concat 422 preflight - every violation the renderer can catch
 * without probing throws 422 FFMPEG_FAILED before any child process is spawned:
 * 1-8 clips, target dims both-or-neither, per-clip speed 0.5-2.0 and trim_end>trim_start,
 * crossfade needs >= 2 clips.
 */
function validateConcatStatic(specValue) {
  const o = specValue.options;
  const n = o.items.length;
  if (n < 1 || n > CONCAT_MAX_INPUTS) {
    throw mediaError(422, 'FFMPEG_FAILED', `concat takes 1 to ${CONCAT_MAX_INPUTS} inputs (got ${n})`);
  }
  if ((o.targetWidth === null) !== (o.targetHeight === null)) {
    throw mediaError(422, 'FFMPEG_FAILED', 'target_width and target_height must be provided together (both or neither)');
  }
  o.items.forEach((it, i) => {
    if (it.speed < 0.5 || it.speed > 2) {
      throw mediaError(422, 'FFMPEG_FAILED', `inputs[${i}].speed must be between 0.5 and 2.0 (got ${fmtMediaNum(it.speed)})`);
    }
    if (it.trimStart !== null && it.trimEnd !== null && it.trimEnd <= it.trimStart) {
      throw mediaError(422, 'FFMPEG_FAILED', `inputs[${i}].trim_end_seconds must be greater than trim_start_seconds`);
    }
  });
  if (o.transition === 'crossfade' && n < 2) {
    throw mediaError(422, 'FFMPEG_FAILED', 'crossfade needs at least 2 inputs');
  }
}

/**
 * The concat FAST COPY PATH (concat demuxer + -c copy) is only lossless-safe when every
 * clip is bitstream-compatible and no edit forces a re-encode. Contract conditions: same
 * video codec/width/height/SAR, fps within 0.01; all clips have aac audio with the same
 * sample_rate+channels or none has audio; no trims, speed 1.0 everywhere, transition cut,
 * both global fades 0, normalize false. Explicit target dims/fps must MATCH the source
 * (otherwise honoring them requires the re-encode).
 */
function concatCopyEligible(specValue, probesByPart) {
  const o = specValue.options;
  if (o.transition !== 'cut' || o.fadeIn !== 0 || o.fadeOut !== 0 || o.normalizeLufs !== null) return false;
  if (o.items.some((it) => it.trimStart !== null || it.trimEnd !== null || it.speed !== 1)) return false;
  const probes = o.items.map((it) => probesByPart[it.sourcePart]);
  const first = probes[0];
  if (!first || !first.info || !first.info.has_video || !first.info.video) return false;
  const fv = first.info.video;
  if (o.targetWidth !== null && (o.targetWidth !== fv.width || o.targetHeight !== fv.height)) return false;
  if (o.targetFps !== null && Math.abs(o.targetFps - fv.fps) > CONCAT_FPS_TOLERANCE) return false;
  for (const p of probes) {
    if (!p || !p.info || !p.info.has_video || !p.info.video) return false;
    const v = p.info.video;
    if (v.codec !== fv.codec || v.width !== fv.width || v.height !== fv.height) return false;
    if (Math.abs(v.fps - fv.fps) > CONCAT_FPS_TOLERANCE) return false;
    if (p.sar !== first.sar) return false;
    if (p.info.has_audio !== first.info.has_audio) return false;
    if (p.info.has_audio) {
      const a = p.info.audio;
      const fa = first.info.audio;
      if (a.codec !== 'aac' || fa.codec !== 'aac') return false;
      if (a.sample_rate !== fa.sample_rate || a.channels !== fa.channels) return false;
    }
  }
  return true;
}

/**
 * Duration-dependent concat planning from the per-part full probes ({info, sar}, info =
 * transformProbeJson shape). Throws the contract's remaining 422 FFMPEG_FAILED cases
 * (unreadable duration, nothing left after trimming, transition_seconds >= an effective
 * clip duration - naming the clip index) and returns the numbers every builder needs:
 * { copy, effectiveSeconds[], outputDurationSeconds, width, height, fps }.
 */
function planConcat(specValue, probesByPart) {
  const o = specValue.options;
  const effective = o.items.map((it, i) => {
    const probe = probesByPart[it.sourcePart];
    const info = probe && probe.info;
    if (!info || !info.has_video || !info.video) {
      throw mediaError(422, 'FFMPEG_FAILED', `inputs[${i}] (part '${it.sourcePart}') has no video stream - concat inputs must be videos`);
    }
    const dur = info.duration_seconds;
    if (!(Number.isFinite(dur) && dur > 0)) {
      throw mediaError(422, 'FFMPEG_FAILED', `inputs[${i}] (part '${it.sourcePart}') has no readable duration - re-encode the clip or use a different file`);
    }
    const eff = concatEffectiveSeconds(it, dur);
    if (!(eff > 0)) {
      throw mediaError(422, 'FFMPEG_FAILED', `inputs[${i}] has no duration left after trim/speed (clip is ${fmtMediaNum(dur)}s)`);
    }
    return eff;
  });
  if (o.transition === 'crossfade') {
    effective.forEach((eff, i) => {
      if (o.transitionSeconds >= eff) {
        throw mediaError(422, 'FFMPEG_FAILED',
          `transition_seconds (${fmtMediaNum(o.transitionSeconds)}) must be smaller than the effective duration of inputs[${i}] (${fmtMediaNum(eff)}s)`);
      }
    });
  }
  const firstVideo = probesByPart[o.items[0].sourcePart].info.video;
  const overlap = o.transition === 'crossfade' ? (o.items.length - 1) * o.transitionSeconds : 0;
  return {
    copy: concatCopyEligible(specValue, probesByPart),
    effectiveSeconds: effective,
    outputDurationSeconds: effective.reduce((a, b) => a + b, 0) - overlap,
    width: o.targetWidth !== null ? o.targetWidth : evenFloor(firstVideo.width),
    height: o.targetHeight !== null ? o.targetHeight : evenFloor(firstVideo.height),
    fps: o.targetFps !== null ? o.targetFps : (firstVideo.fps > 0 ? firstVideo.fps : 30),
  };
}

/** concat demuxer list file body: one `file '<path>'` line per clip, quotes escaped. */
function buildConcatListText(filePaths) {
  return filePaths.map((p) => `file '${String(p).replace(/'/g, "'\\''")}'`).join('\n') + '\n';
}

/** ffmpeg argv for the concat FAST COPY path (demuxer list + -c copy, near-instant). */
function buildConcatCopyArgs(listPath, outputPath) {
  return [
    '-nostdin', '-y', '-loglevel', 'error',
    '-f', 'concat', '-safe', '0', '-i', listPath,
    '-c', 'copy', '-movflags', '+faststart',
    outputPath,
  ];
}

/**
 * ffmpeg argv for the concat RE-ENCODE path. Per clip: video trim(setpts)/speed(setpts)/
 * scale-to-fit + centered pad (never stretch)/setsar=1/fps/yuv420p; audio atrim/atempo/
 * aresample=48000/aformat stereo (the v1 mono-mp3 pin), with an anullsrc silent bed cut
 * to the clip's effective duration when the clip has no audio. Then concat n=N:v=1:a=1,
 * or pairwise xfade=fade + acrossfade chains with offsets from the probed effective
 * durations. Global fades + optional loudnorm on the result; h264+aac 48k stereo out.
 */
function buildConcatFilterArgs(specValue, probesByPart, plan, paths) {
  const o = specValue.options;
  const indexByName = {};
  specValue.inputs.forEach((inp, i) => { indexByName[inp.name] = i; });
  const chains = [];
  o.items.forEach((it, i) => {
    const idx = indexByName[it.sourcePart];
    const probe = probesByPart[it.sourcePart];
    const trimParts = [];
    if (it.trimStart !== null || it.trimEnd !== null) {
      trimParts.push(`start=${fmtMediaNum(it.trimStart !== null ? it.trimStart : 0)}`);
      if (it.trimEnd !== null) trimParts.push(`end=${fmtMediaNum(it.trimEnd)}`);
    }
    const v = [];
    if (trimParts.length) v.push(`trim=${trimParts.join(':')}`, 'setpts=PTS-STARTPTS');
    if (it.speed !== 1) v.push(`setpts=PTS/${fmtMediaNum(it.speed)}`);
    v.push(
      `scale=${plan.width}:${plan.height}:force_original_aspect_ratio=decrease`,
      `pad=${plan.width}:${plan.height}:(ow-iw)/2:(oh-ih)/2`,
      'setsar=1',
      `fps=${fmtMediaNum(plan.fps)}`,
      'format=yuv420p',
    );
    chains.push(`[${idx}:v]${v.join(',')}[v${i}]`);
    if (probe.info.has_audio) {
      const a = [];
      if (trimParts.length) a.push(`atrim=${trimParts.join(':')}`, 'asetpts=PTS-STARTPTS');
      if (it.speed !== 1) a.push(...atempoChain(it.speed));
      a.push('aresample=48000', 'aformat=channel_layouts=stereo');
      // A clip's audio can be SHORTER than its video (or longer): pad with silence up
      // to the clip's effective duration, then clamp to it, so every audio segment is
      // EXACTLY as long as its video segment. Without this, concat spliced the next
      // clip's audio early (cumulative A/V desync) and acrossfade failed at runtime
      // when a clip's audio was shorter than transition_seconds. apad sits AFTER the
      // aformat stereo pin (the v1 mono-mp3 channel-layout rule); atrim covers the
      // audio-LONGER-than-video direction of the same mismatch.
      const eff = fmtMediaNum(plan.effectiveSeconds[i]);
      a.push(`apad=whole_dur=${eff}`, `atrim=0:${eff}`, 'asetpts=PTS-STARTPTS');
      chains.push(`[${idx}:a]${a.join(',')}[a${i}]`);
    } else {
      // Silent bed exactly as long as the clip's effective (post trim/speed) duration.
      chains.push(`anullsrc=r=48000:cl=stereo,atrim=0:${fmtMediaNum(plan.effectiveSeconds[i])},asetpts=PTS-STARTPTS[a${i}]`);
    }
  });
  const n = o.items.length;
  let vLabel = '[v0]';
  let aLabel = '[a0]';
  if (o.transition === 'crossfade' && n >= 2) {
    // Offset of the k-th xfade = sum(eff_0..k-1) - k*T (each earlier joint ate one T).
    let sumEff = plan.effectiveSeconds[0];
    for (let k = 1; k < n; k++) {
      const offset = sumEff - k * o.transitionSeconds;
      const vOut = k === n - 1 ? '[vcat]' : `[vx${k}]`;
      chains.push(`${vLabel}[v${k}]xfade=transition=fade:duration=${fmtMediaNum(o.transitionSeconds)}:offset=${fmtMediaNum(offset)}${vOut}`);
      vLabel = vOut;
      const aOut = k === n - 1 ? '[acat]' : `[ax${k}]`;
      chains.push(`${aLabel}[a${k}]acrossfade=d=${fmtMediaNum(o.transitionSeconds)}${aOut}`);
      aLabel = aOut;
      sumEff += plan.effectiveSeconds[k];
    }
  } else {
    const pairs = [];
    for (let i = 0; i < n; i++) pairs.push(`[v${i}][a${i}]`);
    chains.push(`${pairs.join('')}concat=n=${n}:v=1:a=1[vcat][acat]`);
    vLabel = '[vcat]';
    aLabel = '[acat]';
  }
  const vPost = [];
  const aPost = [];
  if (o.fadeIn > 0) {
    vPost.push(`fade=t=in:st=0:d=${fmtMediaNum(o.fadeIn)}`);
    aPost.push(`afade=t=in:st=0:d=${fmtMediaNum(o.fadeIn)}`);
  }
  if (o.fadeOut > 0) {
    const st = fmtMediaNum(Math.max(0, plan.outputDurationSeconds - o.fadeOut));
    vPost.push(`fade=t=out:st=${st}:d=${fmtMediaNum(o.fadeOut)}`);
    aPost.push(`afade=t=out:st=${st}:d=${fmtMediaNum(o.fadeOut)}`);
  }
  aPost.push(...normalizeSteps(o.normalizeLufs));
  let vMap = vLabel;
  let aMap = aLabel;
  if (vPost.length) {
    chains.push(`${vLabel}${vPost.join(',')}[vout]`);
    vMap = '[vout]';
  }
  if (aPost.length) {
    chains.push(`${aLabel}${aPost.join(',')}[aout]`);
    aMap = '[aout]';
  }
  const args = ['-nostdin', '-y', '-loglevel', 'error'];
  specValue.inputs.forEach((inp) => { args.push('-i', paths.parts[inp.name].path); });
  args.push('-filter_complex', chains.join(';'));
  args.push('-map', vMap, '-map', aMap);
  args.push('-c:v', 'libx264', '-preset', 'veryfast', '-crf', '20');
  args.push('-c:a', 'aac', '-b:a', o.audioBitrate);
  args.push('-movflags', '+faststart', paths.outputPath);
  return args;
}

// ---- media v2: frame ----------------------------------------------------------

/**
 * The timestamp the frame op actually seeks to (contract): default = the middle of the
 * video (probed duration / 2); an explicit at_seconds at or past the end CLAMPS to
 * max(0, duration - 0.1) instead of erroring. Rounded to 3 decimals so the value echoed
 * back as timestamp_seconds is stable.
 */
function resolveFrameTimestamp(atSeconds, durationSeconds) {
  const dur = (Number.isFinite(durationSeconds) && durationSeconds > 0) ? durationSeconds : null;
  const round3 = (x) => Math.round(x * 1000) / 1000;
  if (atSeconds === null || atSeconds === undefined) {
    return dur !== null ? round3(dur / 2) : 0;
  }
  if (dur !== null && atSeconds >= dur) {
    return round3(Math.max(0, dur - 0.1));
  }
  return round3(atSeconds);
}

/**
 * ffmpeg argv for frame: fast input seek (-ss before -i) + -frames:v 1; jpeg written at
 * -q:v 2, png as-is; optional width downscale keeping aspect ratio (height -2 = even).
 */
function buildFrameArgs(specValue, inputPath, timestampSeconds, outputPath) {
  const o = specValue.options;
  const args = [
    '-nostdin', '-y', '-loglevel', 'error',
    '-ss', fmtMediaNum(timestampSeconds),
    '-i', inputPath,
    '-frames:v', '1',
  ];
  if (o.width !== null) args.push('-vf', `scale=${o.width}:-2`);
  if (o.imageFormat === 'jpeg') args.push('-q:v', '2');
  args.push(outputPath);
  return args;
}

// ---- media v2: overlay ----------------------------------------------------------

/** True when an ffprobe format_name identifies a still image (overlay's 422 gate). */
function isImageProbeFormat(formatName) {
  if (typeof formatName !== 'string' || formatName.length === 0) return false;
  return formatName.split(',').some((tok) => IMAGE_FORMAT_NAMES.has(tok.trim()));
}

// overlay x/y expressions for the five anchors (margin from the two nearest edges).
function overlayPositionExpr(position, marginPx) {
  const m = fmtMediaNum(marginPx);
  switch (position) {
    case 'top_left': return { x: m, y: m };
    case 'top_right': return { x: `W-w-${m}`, y: m };
    case 'bottom_left': return { x: m, y: `H-h-${m}` };
    case 'center': return { x: '(W-w)/2', y: '(H-h)/2' };
    default: return { x: `W-w-${m}`, y: `H-h-${m}` }; // bottom_right
  }
}

/**
 * ffmpeg argv for overlay: the image is scaled to width_percent of the PROBED video width
 * (height auto, AR kept), optionally faded via format=rgba + colorchannelmixer=aa when
 * opacity < 1, and burnt in at the anchor position; a start/end window becomes
 * enable='between(t,S,E)' (E defaults to the probed video duration). The video re-encodes
 * h264 yuv420p; audio is STREAM-COPIED when the video has any. `videoInfo` is the
 * transformProbeJson result for the video input.
 */
function buildOverlayArgs(specValue, videoInfo, paths) {
  const o = specValue.options;
  const videoIn = specValue.inputs.find((i) => i.role === 'video');
  const imageIn = specValue.inputs.find((i) => i.role === 'image');
  const mainWidth = (videoInfo && videoInfo.video) ? videoInfo.video.width : 0;
  const scaledW = Math.max(1, Math.round(mainWidth * o.widthPercent / 100));
  const img = [`scale=${scaledW}:-1`];
  if (o.opacity < 1) img.push('format=rgba', `colorchannelmixer=aa=${fmtMediaNum(o.opacity)}`);
  const pos = overlayPositionExpr(o.position, o.marginPx);
  let overlay = `overlay=x=${pos.x}:y=${pos.y}`;
  if (o.startSeconds !== null || o.endSeconds !== null) {
    const s = o.startSeconds !== null ? o.startSeconds : 0;
    const e = o.endSeconds !== null
      ? o.endSeconds
      : ((videoInfo && videoInfo.duration_seconds > 0) ? videoInfo.duration_seconds : 999999);
    overlay += `:enable='between(t,${fmtMediaNum(s)},${fmtMediaNum(e)})'`;
  }
  const filter = `[1:v]${img.join(',')}[ovl];[0:v][ovl]${overlay},format=yuv420p[vout]`;
  const args = [
    '-nostdin', '-y', '-loglevel', 'error',
    '-i', paths.parts[videoIn.name].path,
    '-i', paths.parts[imageIn.name].path,
    '-filter_complex', filter,
    '-map', '[vout]',
  ];
  if (videoInfo && videoInfo.has_audio) args.push('-map', '0:a:0', '-c:a', 'copy');
  args.push('-c:v', 'libx264', '-preset', 'veryfast', '-crf', '20');
  args.push('-movflags', '+faststart', paths.outputPath);
  return args;
}

/** Output file extension (drives the ffmpeg muxer AND the response Content-Type). */
function mediaOutputExtension(specValue) {
  if (specValue.operation === 'mux_audio') return 'mp4';
  if (specValue.operation === 'mix') return specValue.options.hasVideo ? 'mp4' : specValue.options.outputFormat;
  if (specValue.operation === 'concat' || specValue.operation === 'overlay') return 'mp4';
  if (specValue.operation === 'frame') return specValue.options.imageFormat === 'png' ? 'png' : 'jpg';
  return specValue.options.outputFormat; // extract_audio
}

module.exports = {
  DEFAULT_VIEWPORT,
  DEFAULT_TIMEOUT_MS,
  MAX_TIMEOUT_MS,
  PDF_FORMATS,
  SCREENSHOT_TYPES,
  WAIT_UNTIL,
  VIDEO_PRESETS,
  VIDEO_FORMATS,
  DEFAULT_VIDEO_DURATION_MS,
  MAX_VIDEO_DURATION_MS,
  DEFAULT_VIDEO_END_PADDING_MS,
  MAX_VIDEO_END_PADDING_MS,
  DEFAULT_VIDEO_DONE_FLAG,
  DEFAULT_VIDEO_FPS,
  MAX_VIDEO_DIMENSION,
  VIDEO_MODES,
  MAX_TOTAL_FRAMES,
  SMOOTH_JPEG_QUALITY,
  DEFAULT_SMOOTH_WALL_TIMEOUT_MS,
  RENDER_HEADER_TRUNCATED,
  RENDER_HEADER_FRAMES,
  buildRenderOutcomeHeaders,
  VIRTUAL_TIME_INIT_SCRIPT,
  drivePageForSmoothVideo,
  buildImagePipeArgs,
  createFrameSink,
  clampTimeout,
  resolveViewport,
  resolveWaitUntil,
  resolveVideoViewport,
  validateScreenshotRequest,
  validateMargin,
  validatePdfRequest,
  validateVideoRequest,
  drivePageForVideo,
  buildFfmpegArgs,
  Semaphore,
  classifyRenderError,
  RenderPool,
  // media (/internal/media)
  MEDIA_OPERATIONS,
  AUDIO_OUTPUT_FORMATS,
  AUDIO_FIT_MODES,
  MEDIA_AUDIO_CODECS,
  MEDIA_MIME_TYPES,
  MAX_MIX_TRACKS,
  MAX_MEDIA_INPUT_BYTES,
  DEFAULT_AUDIO_BITRATE,
  DEFAULT_LOUDNORM_LUFS,
  MEDIA_TIMEOUT_MIN_MS,
  MEDIA_TIMEOUT_PER_INPUT_SECOND_MS,
  MEDIA_TIMEOUT_MAX_MS,
  MEDIA_STDERR_TAIL_BYTES,
  MEDIA_HEADER_DURATION,
  MEDIA_HEADER_OPERATION,
  MEDIA_HEADER_TIMESTAMP,
  mediaError,
  computeMediaTimeoutMs,
  validateMediaSpec,
  buildProbeArgs,
  transformProbeJson,
  buildMuxAudioArgs,
  buildMixArgs,
  buildExtractAudioArgs,
  mediaOutputExtension,
  // media v2 (concat / frame / overlay)
  CONCAT_MAX_INPUTS,
  CONCAT_FPS_TOLERANCE,
  extractVideoSar,
  validateConcatStatic,
  concatCopyEligible,
  planConcat,
  buildConcatListText,
  buildConcatCopyArgs,
  buildConcatFilterArgs,
  resolveFrameTimestamp,
  buildFrameArgs,
  isImageProbeFormat,
  buildOverlayArgs,
};
