// Browser-free unit tests for the render sidecar's validation + concurrency helpers (lib.js).
// Run with `npm test` (node --test). No Chromium launch - pure logic only.

const { test } = require('node:test');
const assert = require('node:assert/strict');

const vm = require('node:vm');

const {
  clampTimeout,
  resolveViewport,
  resolveWaitUntil,
  resolveVideoViewport,
  validateScreenshotRequest,
  validateMargin,
  validatePdfRequest,
  validateVideoRequest,
  drivePageForVideo,
  drivePageForSmoothVideo,
  buildFfmpegArgs,
  buildImagePipeArgs,
  createFrameSink,
  Semaphore,
  classifyRenderError,
  RenderPool,
  DEFAULT_TIMEOUT_MS,
  MAX_TIMEOUT_MS,
  DEFAULT_VIDEO_DURATION_MS,
  MAX_VIDEO_DURATION_MS,
  DEFAULT_VIDEO_END_PADDING_MS,
  DEFAULT_VIDEO_DONE_FLAG,
  DEFAULT_VIDEO_FPS,
  DEFAULT_SMOOTH_WALL_TIMEOUT_MS,
  RENDER_HEADER_TRUNCATED,
  RENDER_HEADER_FRAMES,
  buildRenderOutcomeHeaders,
  VIRTUAL_TIME_INIT_SCRIPT,
} = require('../lib');

// A minimal Playwright-browser stand-in: records context creations and close() calls so the
// RenderPool tests can assert isolation + recycling without launching Chromium.
function makeFakeBrowser() {
  const state = { contexts: 0, closedContexts: 0, closed: false, lastContextOpts: null };
  return {
    state,
    on() { /* disconnected listener - unused in tests */ },
    async newContext(opts) {
      state.contexts += 1;
      state.lastContextOpts = opts;
      return {
        async newPage() { return { id: state.contexts }; },
        async close() { state.closedContexts += 1; },
      };
    },
    async close() { state.closed = true; },
  };
}

// ---- clampTimeout ---------------------------------------------------------
test('clampTimeout: non-number / non-positive falls back to default', () => {
  assert.equal(clampTimeout(undefined), DEFAULT_TIMEOUT_MS);
  assert.equal(clampTimeout('9000'), DEFAULT_TIMEOUT_MS);
  assert.equal(clampTimeout(0), DEFAULT_TIMEOUT_MS);
  assert.equal(clampTimeout(-5), DEFAULT_TIMEOUT_MS);
  assert.equal(clampTimeout(NaN), DEFAULT_TIMEOUT_MS);
});

test('clampTimeout: within range passes through, above max is capped', () => {
  assert.equal(clampTimeout(5000), 5000);
  assert.equal(clampTimeout(999999), MAX_TIMEOUT_MS);
});

// ---- resolveViewport ------------------------------------------------------
test('resolveViewport: defaults when missing or invalid', () => {
  assert.deepEqual(resolveViewport(undefined), { width: 1280, height: 800 });
  assert.deepEqual(resolveViewport({ width: 0, height: -1 }), { width: 1280, height: 800 });
});

test('resolveViewport: honours provided width/height (floored)', () => {
  assert.deepEqual(resolveViewport({ width: 640, height: 480 }), { width: 640, height: 480 });
  assert.deepEqual(resolveViewport({ width: 800.9, height: 600.2 }), { width: 800, height: 600 });
});

// ---- resolveWaitUntil -----------------------------------------------------
test('resolveWaitUntil: valid value kept, invalid falls to given fallback', () => {
  assert.equal(resolveWaitUntil('load'), 'load');
  assert.equal(resolveWaitUntil('bogus'), 'networkidle');
  assert.equal(resolveWaitUntil('bogus', 'load'), 'load');
  assert.equal(resolveWaitUntil(undefined, 'load'), 'load');
});

// ---- validateScreenshotRequest --------------------------------------------
test('screenshot: missing html rejected', () => {
  const r = validateScreenshotRequest({});
  assert.equal(r.ok, false);
  assert.match(r.error, /html/);
});

test('screenshot: default is png full-page (back-compat with the orchestrator payload)', () => {
  const r = validateScreenshotRequest({ html: '<p>hi</p>', fullPage: true, waitFor: 'networkidle', timeoutMs: 8000 });
  assert.equal(r.ok, true);
  assert.equal(r.value.type, 'png');
  assert.equal(r.value.mime, 'image/png');
  assert.equal(r.value.fullPage, true);
  assert.equal(r.value.selector, null);
});

test('screenshot: jpeg accepts quality; png rejects quality', () => {
  const jpeg = validateScreenshotRequest({ html: 'x', type: 'jpeg', quality: 70 });
  assert.equal(jpeg.ok, true);
  assert.equal(jpeg.value.type, 'jpeg');
  assert.equal(jpeg.value.quality, 70);
  assert.equal(jpeg.value.mime, 'image/jpeg');

  const png = validateScreenshotRequest({ html: 'x', quality: 70 });
  assert.equal(png.ok, false);
  assert.match(png.error, /quality/);
});

test('screenshot: unknown type and out-of-range values rejected', () => {
  assert.equal(validateScreenshotRequest({ html: 'x', type: 'gif' }).ok, false);
  assert.equal(validateScreenshotRequest({ html: 'x', type: 'jpeg', quality: 500 }).ok, false);
  assert.equal(validateScreenshotRequest({ html: 'x', deviceScaleFactor: 9 }).ok, false);
});

test('screenshot: selector disables fullPage (element capture)', () => {
  const r = validateScreenshotRequest({ html: 'x', selector: '#chart', fullPage: true });
  assert.equal(r.ok, true);
  assert.equal(r.value.selector, '#chart');
  assert.equal(r.value.fullPage, false);
});

// ---- validateMargin -------------------------------------------------------
test('margin: number becomes px, CSS length string passes, junk rejected', () => {
  assert.deepEqual(validateMargin({ top: 10, bottom: '1cm', left: '0.5in', right: '10mm' }).value,
    { top: '10px', bottom: '1cm', left: '0.5in', right: '10mm' });
  assert.equal(validateMargin(undefined).value, undefined);
  assert.ok(validateMargin({ top: 'huge' }).error);
  assert.ok(validateMargin('nope').error);
});

// ---- validatePdfRequest ---------------------------------------------------
test('pdf: missing html rejected', () => {
  const r = validatePdfRequest({});
  assert.equal(r.ok, false);
  assert.match(r.error, /html/);
});

test('pdf: sensible defaults (A4, portrait, printBackground on, load wait)', () => {
  const r = validatePdfRequest({ html: '<h1>doc</h1>' });
  assert.equal(r.ok, true);
  assert.equal(r.value.format, 'A4');
  assert.equal(r.value.landscape, false);
  assert.equal(r.value.printBackground, true);
  assert.equal(r.value.waitUntil, 'load');
});

test('pdf: format is case-insensitive against the whitelist; unknown rejected', () => {
  assert.equal(validatePdfRequest({ html: 'x', format: 'letter' }).value.format, 'letter');
  assert.equal(validatePdfRequest({ html: 'x', format: 'A3' }).value.format, 'A3');
  assert.equal(validatePdfRequest({ html: 'x', format: 'A9' }).ok, false);
});

test('pdf: scale bounds enforced; printBackground opt-out; landscape opt-in', () => {
  assert.equal(validatePdfRequest({ html: 'x', scale: 5 }).ok, false);
  assert.equal(validatePdfRequest({ html: 'x', scale: 0.5 }).value.scale, 0.5);
  assert.equal(validatePdfRequest({ html: 'x', printBackground: false }).value.printBackground, false);
  assert.equal(validatePdfRequest({ html: 'x', landscape: true }).value.landscape, true);
});

test('pdf: header/footer templates only kept when displayHeaderFooter is on', () => {
  const off = validatePdfRequest({ html: 'x', headerTemplate: '<span>h</span>' });
  assert.equal(off.value.headerTemplate, undefined);
  const on = validatePdfRequest({ html: 'x', displayHeaderFooter: true, headerTemplate: '<span>h</span>' });
  assert.equal(on.value.displayHeaderFooter, true);
  assert.equal(on.value.headerTemplate, '<span>h</span>');
});

// ---- Semaphore ------------------------------------------------------------
test('semaphore: acquires up to max synchronously, then queues', async () => {
  const s = new Semaphore(2, 2);
  await s.acquire();
  await s.acquire();
  assert.equal(s.inflight, 2);

  let thirdResolved = false;
  const third = s.acquire().then(() => { thirdResolved = true; });
  // queued, not yet resolved
  await Promise.resolve();
  assert.equal(thirdResolved, false);
  assert.equal(s.queued, 1);

  s.release();               // hands the slot to the queued waiter
  await third;
  assert.equal(thirdResolved, true);
  assert.equal(s.inflight, 2);
});

test('semaphore: rejects with BUSY when the queue is full', async () => {
  const s = new Semaphore(1, 1);
  await s.acquire();          // fills the single slot
  s.acquire();                // fills the single queue slot (pending)
  await assert.rejects(() => s.acquire(), (e) => e.code === 'BUSY');
});

test('semaphore: release with no waiters lowers inflight, never below zero', () => {
  const s = new Semaphore(1, 0);
  s.release();               // nothing acquired
  assert.equal(s.inflight, 0);
});

test('semaphore: multi-cycle - transfer to waiter then plain release decrements', async () => {
  const s = new Semaphore(1, 1);
  await s.acquire();                 // inflight 1
  let secondGranted = false;
  const second = s.acquire().then(() => { secondGranted = true; });
  s.release();                       // transfers slot to the waiter, inflight stays 1
  await second;
  assert.equal(secondGranted, true);
  assert.equal(s.inflight, 1);
  s.release();                       // no waiter now -> inflight drops to 0
  assert.equal(s.inflight, 0);
  assert.equal(s.queued, 0);
});

// ---- classifyRenderError --------------------------------------------------
test('classifyRenderError: BUSY -> 429 with a friendly message', () => {
  const busy = Object.assign(new Error('renderer at capacity'), { code: 'BUSY' });
  const r = classifyRenderError(busy);
  assert.equal(r.status, 429);
  assert.match(r.message, /capacity/);
});

test('classifyRenderError: Playwright timeout -> 504 (by error name or message)', () => {
  const byName = Object.assign(new Error('boom'), { name: 'TimeoutError' });
  assert.equal(classifyRenderError(byName).status, 504);
  const byMsg = new Error('Timeout 8000ms exceeded');
  assert.equal(classifyRenderError(byMsg).status, 504);
});

test('classifyRenderError: anything else -> 502 and preserves the message', () => {
  const r = classifyRenderError(new Error('Target closed'));
  assert.equal(r.status, 502);
  assert.equal(r.message, 'Target closed');
});

// ---- RenderPool -----------------------------------------------------------
test('RenderPool: requires a launchBrowser function', () => {
  assert.throws(() => new RenderPool({}), TypeError);
});

test('RenderPool: withContext runs fn with a page, closes the context, releases the permit', async () => {
  const fake = makeFakeBrowser();
  const pool = new RenderPool({ launchBrowser: () => fake });
  const out = await pool.withContext({ viewport: { width: 640, height: 480 }, deviceScaleFactor: 2 },
    async (page) => { assert.ok(page); return 'RESULT'; });

  assert.equal(out, 'RESULT');
  assert.equal(fake.state.contexts, 1);
  assert.equal(fake.state.closedContexts, 1, 'context must be closed in finally');
  assert.deepEqual(fake.state.lastContextOpts, { viewport: { width: 640, height: 480 }, deviceScaleFactor: 2 });
  assert.equal(pool.sem.inflight, 0, 'permit must be released');
  assert.equal(pool.renderCount, 1);
});

test('RenderPool: fn throwing still closes the context + releases the permit, and propagates', async () => {
  const fake = makeFakeBrowser();
  const pool = new RenderPool({ launchBrowser: () => fake });
  await assert.rejects(
    () => pool.withContext({}, async () => { throw new Error('render boom'); }),
    /render boom/);
  assert.equal(fake.state.closedContexts, 1, 'context closed even on failure');
  assert.equal(pool.sem.inflight, 0, 'permit released even on failure');
  assert.equal(pool.renderCount, 0, 'a failed render does not count toward recycling');
});

test('RenderPool: BUSY reject never runs fn and keeps permit accounting balanced', async () => {
  const fake = makeFakeBrowser();
  const pool = new RenderPool({ launchBrowser: () => fake, maxConcurrent: 1, maxQueue: 0 });
  let release;
  const blocker = new Promise((r) => { release = r; });
  const first = pool.withContext({}, async () => { await blocker; return 'first'; });
  await Promise.resolve();

  let ranSecond = false;
  await assert.rejects(
    () => pool.withContext({}, async () => { ranSecond = true; return 'second'; }),
    (e) => e.code === 'BUSY');
  assert.equal(ranSecond, false, 'a rejected (BUSY) request must not run its fn');

  release();
  assert.equal(await first, 'first');
  assert.equal(pool.sem.inflight, 0, 'inflight returns to 0 - the BUSY reject did not corrupt accounting');
  assert.equal(fake.state.contexts, 1, 'only the admitted request created a context');
});

test('RenderPool: recycles the browser after recycleAfter renders (while idle), relaunching lazily', async () => {
  let launches = 0;
  const browsers = [];
  const pool = new RenderPool({
    launchBrowser: () => { launches += 1; const b = makeFakeBrowser(); browsers.push(b); return b; },
    recycleAfter: 2,
  });

  await pool.withContext({}, async () => 'a');   // renderCount 1
  await pool.withContext({}, async () => 'b');   // renderCount 2 -> maybeRecycle (idle) closes browser #1
  assert.equal(launches, 1);
  assert.equal(browsers[0].state.closed, true, 'first browser recycled after reaching recycleAfter');

  await pool.withContext({}, async () => 'c');   // lazily relaunches browser #2
  assert.equal(launches, 2);
  assert.equal(pool.renderCount, 1, 'render counter resets with the fresh browser');
});

test('RenderPool: maybeRecycle does NOT recycle while a render is in flight', async () => {
  const fake = makeFakeBrowser();
  const pool = new RenderPool({ launchBrowser: () => fake, recycleAfter: 1 });
  await pool.getBrowser();
  pool.renderCount = 5;                 // past the threshold
  await pool.sem.acquire();             // simulate an in-flight render (inflight = 1)
  await pool.maybeRecycle();
  assert.equal(fake.state.closed, false, 'must not kill the browser while inflight > 0');
  pool.sem.release();                   // now idle
  await pool.maybeRecycle();
  assert.equal(fake.state.closed, true, 'recycles once idle');
});

test('RenderPool: health reflects warm/cold + counters', async () => {
  const pool = new RenderPool({ launchBrowser: () => makeFakeBrowser() });
  assert.equal(pool.health().status, 'cold');
  await pool.withContext({}, async () => 'x');
  const h = pool.health();
  assert.equal(h.status, 'warm');
  assert.equal(h.renderCount, 1);
  assert.equal(h.inflight, 0);
});

// ---- resolveVideoViewport ---------------------------------------------------
test('video viewport: presets resolve, default is vertical, unknown preset rejected', () => {
  assert.deepEqual(resolveVideoViewport('vertical').value, { width: 1080, height: 1920 });
  assert.deepEqual(resolveVideoViewport('HORIZONTAL').value, { width: 1920, height: 1080 });
  assert.deepEqual(resolveVideoViewport('square').value, { width: 1080, height: 1080 });
  assert.deepEqual(resolveVideoViewport(undefined).value, { width: 1080, height: 1920 });
  assert.ok(resolveVideoViewport('cinema').error);
});

test('video viewport: explicit viewport wins over preset and is floored to even', () => {
  const r = resolveVideoViewport('square', { width: 1081, height: 1919.9 });
  assert.deepEqual(r.value, { width: 1080, height: 1918 });
});

test('video viewport: out-of-range explicit viewport rejected', () => {
  assert.ok(resolveVideoViewport(undefined, { width: 8, height: 100 }).error);
  assert.ok(resolveVideoViewport(undefined, { width: 100, height: 4000 }).error);
  assert.ok(resolveVideoViewport(undefined, { width: 'big', height: 100 }).error);
});

// ---- validateVideoRequest ---------------------------------------------------
test('video: missing html rejected', () => {
  const r = validateVideoRequest({});
  assert.equal(r.ok, false);
  assert.match(r.error, /html/);
});

test('video: defaults are mp4 vertical, waitForDone on, standard done flag', () => {
  const r = validateVideoRequest({ html: '<p>clip</p>' });
  assert.equal(r.ok, true);
  assert.equal(r.value.format, 'mp4');
  assert.equal(r.value.mime, 'video/mp4');
  assert.deepEqual(r.value.viewport, { width: 1080, height: 1920 });
  assert.equal(r.value.maxDurationMs, DEFAULT_VIDEO_DURATION_MS);
  assert.equal(r.value.endPaddingMs, DEFAULT_VIDEO_END_PADDING_MS);
  assert.equal(r.value.waitForDone, true);
  assert.equal(r.value.doneFlag, DEFAULT_VIDEO_DONE_FLAG);
  assert.equal(r.value.fps, DEFAULT_VIDEO_FPS);
});

test('video: unknown format rejected; webm carries its mime', () => {
  assert.equal(validateVideoRequest({ html: 'x', format: 'avi' }).ok, false);
  const webm = validateVideoRequest({ html: 'x', format: 'webm' });
  assert.equal(webm.ok, true);
  assert.equal(webm.value.mime, 'video/webm');
});

test('video: maxDurationMs below 1s rejected; clamped by caps and by the hard max', () => {
  assert.equal(validateVideoRequest({ html: 'x', maxDurationMs: 500 }).ok, false);
  const capped = validateVideoRequest({ html: 'x', maxDurationMs: 50000 }, { maxDurationMs: 10000 });
  assert.equal(capped.value.maxDurationMs, 10000);
  const hard = validateVideoRequest({ html: 'x', maxDurationMs: 999999 }, { maxDurationMs: 999999 });
  assert.equal(hard.value.maxDurationMs, MAX_VIDEO_DURATION_MS, 'caps can never RAISE the ceiling');
});

test('video: endPaddingMs bounds enforced', () => {
  assert.equal(validateVideoRequest({ html: 'x', endPaddingMs: -1 }).ok, false);
  assert.equal(validateVideoRequest({ html: 'x', endPaddingMs: 5000 }).ok, false);
  assert.equal(validateVideoRequest({ html: 'x', endPaddingMs: 1000 }).value.endPaddingMs, 1000);
});

test('video: doneFlag must be a plain JS identifier (no injection into waitForFunction)', () => {
  assert.equal(validateVideoRequest({ html: 'x', doneFlag: 'alert(1)//' }).ok, false);
  assert.equal(validateVideoRequest({ html: 'x', doneFlag: 'a b' }).ok, false);
  assert.equal(validateVideoRequest({ html: 'x', doneFlag: '1abc' }).ok, false);
  assert.equal(validateVideoRequest({ html: 'x', doneFlag: '__CLIP_OVER__' }).value.doneFlag, '__CLIP_OVER__');
});

test('video: fps bounds enforced and rounded; waitForDone opt-out honoured', () => {
  assert.equal(validateVideoRequest({ html: 'x', fps: 5 }).ok, false);
  assert.equal(validateVideoRequest({ html: 'x', fps: 120 }).ok, false);
  assert.equal(validateVideoRequest({ html: 'x', fps: 24.6 }).value.fps, 25);
  assert.equal(validateVideoRequest({ html: 'x', waitForDone: false }).value.waitForDone, false);
});

// ---- buildFfmpegArgs --------------------------------------------------------
test('ffmpeg args: h264 + yuv420p + faststart, default fps, output last', () => {
  const args = buildFfmpegArgs('/in.webm', '/out.mp4', {});
  assert.ok(args.includes('libx264'));
  assert.ok(args.includes('yuv420p'));
  assert.ok(args.includes('+faststart'));
  assert.equal(args[args.indexOf('-i') + 1], '/in.webm');
  assert.equal(args[args.indexOf('-r') + 1], String(DEFAULT_VIDEO_FPS));
  assert.equal(args[args.length - 1], '/out.mp4');
});

test('ffmpeg args: custom fps applied', () => {
  const args = buildFfmpegArgs('/a.webm', '/b.mp4', { fps: 24 });
  assert.equal(args[args.indexOf('-r') + 1], '24');
});

// ---- drivePageForVideo ------------------------------------------------------
// Pins the exact Playwright call shape: waitForFunction(pageFunction, ARG, options).
// The shipped bug passed the options object in the arg slot, so the recording ceiling
// (maxDurationMs) was silently ignored and every clip used the 30s context default.
function makeFakeVideoPage() {
  const calls = { setContent: [], waitForFunction: [], waitForTimeout: [] };
  return {
    calls,
    async setContent(html, opts) { calls.setContent.push([html, opts]); },
    async waitForFunction(expr, arg, options) { calls.waitForFunction.push([expr, arg, options]); },
    async waitForTimeout(ms) { calls.waitForTimeout.push(ms); },
  };
}

const VIDEO_DRIVE_OPTS = {
  html: '<p>clip</p>',
  waitUntil: 'networkidle',
  timeoutMs: 8000,
  waitForDone: true,
  doneFlag: '__DONE__',
  maxDurationMs: 45000,
  endPaddingMs: 400,
};

test('drivePageForVideo: waitForFunction gets the timeout in the OPTIONS slot (3rd positional), null arg', async () => {
  const page = makeFakeVideoPage();
  await drivePageForVideo(page, VIDEO_DRIVE_OPTS);

  assert.equal(page.calls.waitForFunction.length, 1);
  const [expr, arg, options] = page.calls.waitForFunction[0];
  assert.equal(expr, 'window.__DONE__ === true');
  assert.equal(arg, null, 'arg slot must be null - options in this slot are silently ignored');
  assert.deepEqual(options, { timeout: 45000, polling: 250 },
    'maxDurationMs must land in Playwright options.timeout, or the recording ceiling is a no-op');
  assert.deepEqual(page.calls.waitForTimeout, [400], 'end padding after the done signal');
  assert.deepEqual(page.calls.setContent[0][1], { waitUntil: 'networkidle', timeout: 8000 });
});

test('drivePageForVideo: hitting the ceiling (TimeoutError) is a normal ending, not an error', async () => {
  const page = makeFakeVideoPage();
  page.waitForFunction = async () => {
    const err = new Error('Timeout 45000ms exceeded');
    err.name = 'TimeoutError';
    throw err;
  };
  await drivePageForVideo(page, VIDEO_DRIVE_OPTS); // must not throw
  assert.deepEqual(page.calls.waitForTimeout, [400], 'padding still applies after a ceiling ending');
});

test('drivePageForVideo: a NON-timeout failure propagates (page crash must surface as 502)', async () => {
  const page = makeFakeVideoPage();
  page.waitForFunction = async () => { throw new Error('Target closed'); };
  await assert.rejects(() => drivePageForVideo(page, VIDEO_DRIVE_OPTS), /Target closed/);
});

test('drivePageForVideo: waitForDone=false records the full maxDurationMs unconditionally', async () => {
  const page = makeFakeVideoPage();
  await drivePageForVideo(page, { ...VIDEO_DRIVE_OPTS, waitForDone: false, endPaddingMs: 0 });

  assert.equal(page.calls.waitForFunction.length, 0);
  assert.deepEqual(page.calls.waitForTimeout, [45000]);
});

// ---- video mode validation --------------------------------------------------
test('video: mode defaults to live; smooth accepted; unknown rejected; smooth+webm rejected', () => {
  assert.equal(validateVideoRequest({ html: 'x' }).value.mode, 'live');
  assert.equal(validateVideoRequest({ html: 'x', mode: 'smooth' }).value.mode, 'smooth');
  assert.equal(validateVideoRequest({ html: 'x', mode: 'SMOOTH' }).value.mode, 'smooth');
  assert.equal(validateVideoRequest({ html: 'x', mode: 'turbo' }).ok, false);
  const r = validateVideoRequest({ html: 'x', mode: 'smooth', format: 'webm' });
  assert.equal(r.ok, false);
  assert.match(r.error, /mp4/);
});

// ---- buildImagePipeArgs ------------------------------------------------------
test('image pipe args: image2pipe mjpeg stdin in, h264 yuv420p faststart out, fps applied', () => {
  const args = buildImagePipeArgs('/out.mp4', { fps: 60 });
  assert.equal(args[args.indexOf('-f') + 1], 'image2pipe');
  assert.equal(args[args.indexOf('-vcodec') + 1], 'mjpeg');
  assert.equal(args[args.indexOf('-framerate') + 1], '60');
  assert.equal(args[args.indexOf('-i') + 1], 'pipe:0');
  assert.ok(args.includes('libx264'));
  assert.ok(args.includes('yuv420p'));
  assert.ok(args.includes('+faststart'));
  assert.equal(args[args.length - 1], '/out.mp4');
});

// ---- VIRTUAL_TIME_INIT_SCRIPT semantics (run inside a vm sandbox) -----------
function makeVirtualPage() {
  const ctx = vm.createContext({});
  ctx.window = ctx;
  ctx.performance = {};
  ctx.document = { getAnimations: () => [], querySelectorAll: () => [] };
  ctx.Date = Date;
  vm.runInContext(VIRTUAL_TIME_INIT_SCRIPT, ctx);
  return ctx;
}

test('virtual clock: performance.now and Date.now advance ONLY via __vtStep', () => {
  const w = makeVirtualPage();
  const d0 = w.Date.now();
  assert.equal(w.performance.now(), 0);
  w.__vtStep(1000 / 30);
  assert.ok(Math.abs(w.performance.now() - 33.333) < 0.01);
  assert.ok(Math.abs(w.Date.now() - d0 - 33.333) < 1);
});

test('virtual clock: setTimeout fires at its virtual due time, in order, with vt frozen at the due time', () => {
  const w = makeVirtualPage();
  const fired = [];
  w.setTimeout(() => fired.push(['b', w.performance.now()]), 50);
  w.setTimeout(() => fired.push(['a', w.performance.now()]), 20);
  w.__vtStep(100);
  assert.deepEqual(fired.map((f) => f[0]), ['a', 'b'], 'timers fire in due-time order');
  assert.equal(fired[0][1], 20, 'callback sees the virtual clock AT its due time');
  assert.equal(fired[1][1], 50);
  assert.equal(w.performance.now(), 100, 'clock lands on the step target');
});

test('virtual clock: nested setTimeout scheduled from a callback fires within the same step', () => {
  const w = makeVirtualPage();
  const fired = [];
  w.setTimeout(() => {
    fired.push('outer@' + w.performance.now());
    w.setTimeout(() => fired.push('inner@' + w.performance.now()), 30);
  }, 10);
  w.__vtStep(100);
  assert.deepEqual(fired, ['outer@10', 'inner@40']);
});

test('virtual clock: setInterval fires once per period inside one big step; clearInterval stops it', () => {
  const w = makeVirtualPage();
  let n = 0;
  const id = w.setInterval(() => { n++; }, 40);
  w.__vtStep(200);
  assert.equal(n, 5, '200ms step with a 40ms interval = 5 fires');
  w.clearInterval(id);
  w.__vtStep(200);
  assert.equal(n, 5, 'cleared interval must not fire again');
});

test('virtual clock: cancelAnimationFrame removes the queued callback; cancel+reschedule fires ONCE per step (browser parity)', () => {
  const w = makeVirtualPage();
  let fired = 0;
  const id = w.requestAnimationFrame(() => { fired++; });
  w.cancelAnimationFrame(id);
  w.__vtStep(16);
  assert.equal(fired, 0, 'a cancelled rAF must never fire');

  // The common throttling idiom: cancel the pending handle, schedule a fresh one.
  let ticks = 0;
  let pending = w.requestAnimationFrame(function tick() { ticks++; });
  w.cancelAnimationFrame(pending);
  pending = w.requestAnimationFrame(() => { ticks++; });
  w.__vtStep(16);
  assert.equal(ticks, 1, 'cancel + reschedule must fire exactly once per step, like a real browser');
});

test('virtual clock: WAAPI animations are PAUSED on first observation (no real-time drift before the screenshot)', () => {
  const w = makeVirtualPage();
  const anim = { paused: 0, pause() { this.paused++; } };
  w.document.getAnimations = () => [anim];
  w.__vtStep(100);
  w.__vtStep(100);
  assert.equal(anim.paused, 1, 'paused exactly once, at first observation');
  assert.equal(anim.currentTime, 100, 'seek stays relative to first-seen time');
});

test('virtual clock: requestAnimationFrame callbacks run once per step and re-queue for the next', () => {
  const w = makeVirtualPage();
  const ticks = [];
  function loop(t) { ticks.push(t); w.requestAnimationFrame(loop); }
  w.requestAnimationFrame(loop);
  w.__vtStep(16);
  w.__vtStep(16);
  w.__vtStep(16);
  assert.deepEqual(ticks, [16, 32, 48], 'one rAF tick per step, timestamp = virtual time');
});

test('virtual clock: a zero-delay self-rescheduling timer cannot hang the step (guard)', () => {
  const w = makeVirtualPage();
  let n = 0;
  function evil() { n++; w.setTimeout(evil, 0); }
  w.setTimeout(evil, 0);
  w.__vtStep(10); // must return despite the infinite reschedule chain
  assert.ok(n > 0 && n <= 10001, 'guard bounds the per-step timer executions');
});

test('virtual clock: CSS/WAAPI animations are re-seeked RELATIVE to when each was first observed', () => {
  const w = makeVirtualPage();
  const early = {};
  const late = {};
  let anims = [early];
  w.document.getAnimations = () => anims;
  w.__vtStep(100);            // early observed at vt=100
  anims = [early, late];
  w.__vtStep(100);            // late observed at vt=200
  w.__vtStep(100);            // vt=300
  assert.equal(early.currentTime, 300 - 100, 'early animation advanced from its first-seen time');
  assert.equal(late.currentTime, 300 - 200, 'late animation (transition created mid-clip) starts at 0 from ITS first-seen time');
});

// ---- drivePageForSmoothVideo -------------------------------------------------
function makeFakeSmoothPage({ frameCount = 2, doneAtCapture = -1 } = {}) {
  const state = { setContent: [], steps: [], doneChecks: 0 };
  let captureIndex = 0;
  const subFrames = [];
  for (let i = 0; i < frameCount; i++) {
    subFrames.push({
      async evaluate(fn, arg) { state.steps.push([i, arg]); return 0; },
    });
  }
  return {
    state,
    markCapture() { captureIndex++; },
    async setContent(html, opts) { state.setContent.push([html, opts]); },
    frames() { return subFrames; },
    async evaluate(fn, arg) {
      state.doneChecks++;
      return doneAtCapture >= 0 && captureIndex > doneAtCapture;
    },
  };
}

const SMOOTH_OPTS = {
  html: '<p>x</p>', waitUntil: 'networkidle', timeoutMs: 8000,
  fps: 10, maxDurationMs: 1000, endPaddingMs: 200,
  waitForDone: false, doneFlag: '__DONE__', wallTimeoutMs: 60000,
};

test('smooth driver: waitForDone=false renders exactly fps*duration frames, stepping EVERY sub-frame by 1000/fps', async () => {
  const page = makeFakeSmoothPage({ frameCount: 2 });
  const captured = [];
  const out = await drivePageForSmoothVideo(page, SMOOTH_OPTS, async (i) => captured.push(i));

  assert.equal(out.frames, 10, '1000ms at 10fps = 10 frames');
  assert.equal(out.truncated, false);
  assert.equal(captured.length, 10);
  assert.equal(page.state.steps.length, 20, 'both iframes stepped on every frame');
  assert.equal(page.state.steps[0][1], 100, 'step delta = 1000/fps ms');
});

test('smooth driver: done flag ends the clip after the end-padding frames', async () => {
  const page = makeFakeSmoothPage({ frameCount: 1 });
  let captures = 0;
  page.evaluate = async () => captures >= 4; // page sets its done flag once 4 frames are out

  const out = await drivePageForSmoothVideo(
    page,
    { ...SMOOTH_OPTS, waitForDone: true },
    async () => { captures++; },
  );

  // 4 frames until done + 2 padding frames (200ms at 10fps)
  assert.equal(out.frames, 6);
  assert.equal(out.truncated, false);
});

// The budget buys FRAMES, not seconds of clip: delivered_seconds = (budget / ms_per_frame) / fps.
// At the measured ~330ms/frame of a real 1080x1920 interface with two srcdoc iframes, the old
// 180000 bought 540 frames, i.e. 9s at 60fps - every 20s clip silently shipped as 9s. Pinned as
// a VALUE (not `>= x`) so lowering it back is a deliberate, reviewed act.
// TUNING THIS? The orchestrator's read window must stay above it or it aborts a clip the
// sidecar was about to return: VideoReadTimeoutOrderingTest (orchestrator-service) mirrors
// this number and asserts the ordering. Change both together.
test('smooth wall-clock budget fits a 60fps 20s clip of a real interface (silent-9s-clip regression)', () => {
  assert.equal(DEFAULT_SMOOTH_WALL_TIMEOUT_MS, 450000);
  const msPerFrameMeasuredInProd = 330;
  const framesFor20sAt60fps = 20 * 60;
  assert.ok(
    framesFor20sAt60fps * msPerFrameMeasuredInProd <= DEFAULT_SMOOTH_WALL_TIMEOUT_MS,
    `a 20s@60fps clip needs ${framesFor20sAt60fps * msPerFrameMeasuredInProd}ms of budget; ` +
    `${DEFAULT_SMOOTH_WALL_TIMEOUT_MS}ms would truncate it`,
  );
});

// The header NAMES are a cross-layer contract: the orchestrator's InterfaceScreenshotServiceImpl
// matches these literals to decide whether the clip was cut short. Renaming one on this side
// alone silently restores the invisible-truncation bug (a short clip still answers 200), so the
// literals are pinned here and mirrored by the Java test.
test('render outcome header names are the literals the orchestrator matches', () => {
  assert.equal(RENDER_HEADER_TRUNCATED, 'X-Render-Truncated');
  assert.equal(RENDER_HEADER_FRAMES, 'X-Render-Frames');
});

test('render outcome headers carry the driver result verbatim on the wire', () => {
  // Pins the exact wire VALUES the orchestrator parses ("true"/"false", decimal frames),
  // not just the header names. Java reads these with equalsIgnoreCase("true") + Integer.valueOf.
  assert.deepEqual(buildRenderOutcomeHeaders({ frames: 540, truncated: true }),
    { 'X-Render-Frames': '540', 'X-Render-Truncated': 'true' });
  assert.deepEqual(buildRenderOutcomeHeaders({ frames: 1200, truncated: false }),
    { 'X-Render-Frames': '1200', 'X-Render-Truncated': 'false' });
  // Never emit "undefined"/"NaN": the Java side treats an unparseable count as absent, but a
  // literal "undefined" on the wire would be a lie about a render that did produce frames.
  assert.deepEqual(buildRenderOutcomeHeaders(undefined),
    { 'X-Render-Frames': '0', 'X-Render-Truncated': 'false' });
});

test('smooth driver: MAX_TOTAL_FRAMES clamping the REQUEST reports truncated (silent-shortening class)', async () => {
  // 120s at 60fps = 7200 frames requested, clamped to MAX_TOTAL_FRAMES (3600) = 60s delivered.
  // The clip is silently half the request and used to report truncated:false.
  const page = makeFakeSmoothPage({ frameCount: 1 });
  page.evaluate = async () => false; // page has no done flag

  const out = await drivePageForSmoothVideo(
    page,
    { ...SMOOTH_OPTS, fps: 60, waitForDone: true, maxDurationMs: 120000, endPaddingMs: 0, wallTimeoutMs: 600000 },
    async () => {},
  );

  assert.ok(out.frames <= 3600 + 1, `clamped to the ceiling, got ${out.frames}`);
  assert.equal(out.truncated, true,
    '7200 frames were requested and only ~3600 delivered - a silent halving that must be reported');
});

test('smooth driver: waitForDone=false clamped by the ceiling is ALSO reported truncated (sibling path)', async () => {
  // Latent today (the orchestrator always sends waitForDone=true) but the same defect class:
  // a clamped clip is silently short whether or not a done flag was being watched.
  const page = makeFakeSmoothPage({ frameCount: 1 });

  const out = await drivePageForSmoothVideo(
    page,
    { ...SMOOTH_OPTS, fps: 60, waitForDone: false, maxDurationMs: 120000, endPaddingMs: 0, wallTimeoutMs: 600000 },
    async () => {},
  );

  assert.ok(out.frames <= 3600 + 1, `clamped to the ceiling, got ${out.frames}`);
  assert.equal(out.truncated, true,
    '7200 frames requested, ~3600 delivered - the clamp is silent on this path too');
});

test('smooth driver: waitForDone=false at its REQUESTED duration is not truncated (the ceiling never clamped)', async () => {
  const page = makeFakeSmoothPage({ frameCount: 2 });
  const out = await drivePageForSmoothVideo(page, SMOOTH_OPTS, async () => {});

  assert.equal(out.frames, 10, '1000ms at 10fps = the full request');
  assert.equal(out.truncated, false, 'delivering exactly what was asked is not truncation');
});

test('smooth driver: a done-flag-less page that runs its FULL requested duration is NOT truncated', async () => {
  // The false-positive guard. `padLeft < 0` (done flag never seen) is a NORMAL ending for a
  // page without one - the clip still ran its whole maxDurationMs. Flagging it would make
  // every such render warn TRUNCATED and drown the real signal this change exists to add.
  // The orchestrator sends waitForDone=true on EVERY video, so this is the common path.
  const page = makeFakeSmoothPage({ frameCount: 1 });
  page.evaluate = async () => false;

  const out = await drivePageForSmoothVideo(
    page,
    { ...SMOOTH_OPTS, fps: 60, waitForDone: true, maxDurationMs: 20000, endPaddingMs: 0, wallTimeoutMs: 600000 },
    async () => {},
  );

  assert.ok(out.frames >= 1200, `full 20s at 60fps delivered, got ${out.frames}`);
  assert.equal(out.truncated, false,
    'nothing was cut: the ceiling (3600) never clamped a 1200-frame request');
});

test('smooth driver: done flag seen => NOT truncated (a clip the page ended is complete, not cut)', async () => {
  const page = makeFakeSmoothPage({ frameCount: 1 });
  let captures = 0;
  page.evaluate = async () => captures >= 4;

  const out = await drivePageForSmoothVideo(
    page,
    { ...SMOOTH_OPTS, waitForDone: true },
    async () => { captures++; },
  );

  assert.equal(out.truncated, false,
    'flagging a normally-ended clip would make the WARN pure noise');
});

test('smooth driver: wall-clock budget exceeded finalises a truncated clip instead of throwing', async () => {
  const page = makeFakeSmoothPage({ frameCount: 1 });
  const out = await drivePageForSmoothVideo(
    page,
    { ...SMOOTH_OPTS, maxDurationMs: 10000, wallTimeoutMs: 1 },
    async () => { await new Promise((r) => setTimeout(r, 5)); },
  );

  assert.equal(out.truncated, true);
  assert.ok(out.frames >= 1 && out.frames < 100, 'stops early, keeps what it captured');
});

test('smooth driver: a detached iframe (evaluate throws) never kills the render', async () => {
  const page = makeFakeSmoothPage({ frameCount: 1 });
  page.frames = () => [{ evaluate: async () => { throw new Error('Frame was detached'); } }];
  const out = await drivePageForSmoothVideo(page, SMOOTH_OPTS, async () => {});
  assert.equal(out.frames, 10, 'render completes despite the dead iframe');
});

// ---- createFrameSink ---------------------------------------------------------
// Fake ffmpeg child built on EventEmitter so every lifecycle path is exercised without a
// real process. The CRITICAL invariant pinned here: NO path may surface an unhandled
// rejection - a saturated-pool 429 or a mid-render crash previously killed the whole pod.
const { EventEmitter } = require('node:events');

function makeFakeFfmpeg({ writeReturns = true } = {}) {
  const child = new EventEmitter();
  child.killed = false;
  child.kill = () => { child.killed = true; };
  child.stderr = new EventEmitter();
  const stdin = new EventEmitter();
  stdin.written = [];
  stdin.ended = false;
  stdin.write = (buf) => { stdin.written.push(buf); return writeReturns; };
  stdin.end = () => { stdin.ended = true; };
  child.stdin = stdin;
  return child;
}

test('frame sink: write honours backpressure (waits for drain when write returns false)', async () => {
  const child = makeFakeFfmpeg({ writeReturns: false });
  const sink = createFrameSink(() => child, 'ffmpeg', ['-args']);
  let resolved = false;
  const w = sink.write(Buffer.from('f1')).then(() => { resolved = true; });
  await Promise.resolve();
  assert.equal(resolved, false, 'write must block until drain');
  child.stdin.emit('drain');
  await w;
  assert.equal(resolved, true);
});

test('frame sink: finalize resolves on exit 0 and throws with stderr detail on non-zero', async () => {
  const ok = makeFakeFfmpeg();
  const okSink = createFrameSink(() => ok, 'ffmpeg', []);
  const fin = okSink.finalize();
  ok.emit('close', 0);
  await fin; // no throw
  assert.equal(ok.stdin.ended, true);

  const bad = makeFakeFfmpeg();
  const badSink = createFrameSink(() => bad, 'ffmpeg', []);
  bad.stderr.emit('data', 'pipe:0: Invalid data');
  const fin2 = badSink.finalize();
  bad.emit('close', 1);
  await assert.rejects(() => fin2, /ffmpeg exited 1: .*Invalid data/);
});

test('frame sink: ffmpeg dying WHILE a write awaits drain rejects the write instead of hanging (slot-leak regression)', async () => {
  const child = makeFakeFfmpeg({ writeReturns: false });
  const sink = createFrameSink(() => child, 'ffmpeg', []);
  const w = sink.write(Buffer.from('frame'));
  // ffmpeg is OOM-killed mid-stream: no 'drain' will ever fire.
  child.stderr.emit('data', 'Killed');
  child.emit('close', 137);
  await assert.rejects(() => w, /ffmpeg exited 137 mid-stream/,
    'the drain wait must race the exit promise, never block forever');
});

test('frame sink: a write AFTER ffmpeg already died fails fast with the exit detail', async () => {
  const child = makeFakeFfmpeg();
  const sink = createFrameSink(() => child, 'ffmpeg', []);
  child.emit('close', 1);
  await new Promise((r) => setImmediate(r));
  await assert.rejects(() => sink.write(Buffer.from('frame')), /ffmpeg exited 1 mid-stream/);
});

test('frame sink: abort ends stdin, kills the child, reaps it, and NEVER throws (even on non-zero exit)', async () => {
  const child = makeFakeFfmpeg();
  const sink = createFrameSink(() => child, 'ffmpeg', []);
  const ab = sink.abort();
  child.emit('close', 1); // ffmpeg dies angry - abort must still resolve quietly
  await ab;
  assert.equal(child.stdin.ended, true);
  assert.equal(child.killed, true);
});

test('frame sink: no unhandled rejection when ffmpeg exits non-zero and NOBODY awaits yet (the pod-killer bug)', async () => {
  const unhandled = [];
  const handler = (reason) => unhandled.push(reason);
  process.on('unhandledRejection', handler);
  try {
    const child = makeFakeFfmpeg();
    const sink = createFrameSink(() => child, 'ffmpeg', []);
    // Simulate the 429/BUSY path: ffmpeg exits non-zero while the caller is elsewhere.
    child.emit('close', 1);
    await new Promise((r) => setImmediate(r));
    await new Promise((r) => setImmediate(r));
    assert.equal(unhandled.length, 0, 'the internal exit promise must RESOLVE, never reject unattended');
    await sink.abort(); // and the child is still reapable afterwards
  } finally {
    process.removeListener('unhandledRejection', handler);
  }
});

test('frame sink: async stdin error (EPIPE from a dead ffmpeg) is swallowed, not a process crash', async () => {
  const child = makeFakeFfmpeg();
  createFrameSink(() => child, 'ffmpeg', []);
  // Without the permanent error listener this emit would throw (EventEmitter error semantics).
  child.stdin.emit('error', new Error('EPIPE'));
  assert.ok(true, 'no throw');
});

test('frame sink: spawn-level error (ffmpeg binary missing) surfaces via finalize, not a rejection', async () => {
  const child = makeFakeFfmpeg();
  const sink = createFrameSink(() => child, 'ffmpeg', []);
  const fin = sink.finalize();
  child.emit('error', new Error('ENOENT'));
  await assert.rejects(() => fin, /ffmpeg exited -1: .*ENOENT/);
});

// ---- RenderPool initScript ordering ------------------------------------------
test('withContext: initScript is installed on the context BEFORE the first page is created', async () => {
  const order = [];
  const fake = {
    on() {},
    async newContext() {
      return {
        async addInitScript(s) { order.push(['init', !!(s && s.content)]); },
        async newPage() { order.push(['page']); return {}; },
        async close() {},
      };
    },
    async close() {},
  };
  const pool = new RenderPool({ launchBrowser: () => fake });
  await pool.withContext({ initScript: 'window.x = 1;' }, async () => 'ok');
  assert.deepEqual(order, [['init', true], ['page']],
    'the virtual clock must be armed before ANY page script can run');
});

// ---- RenderPool.withVideoContext -------------------------------------------
// Fake browser whose pages expose Playwright's page.video() contract: the video file
// path only resolves after the context is closed - the tests assert that ordering.
function makeFakeVideoBrowser({ videoPath = '/tmp/clip.webm', withVideo = true } = {}) {
  const state = { contexts: 0, closedContexts: 0, lastContextOpts: null, closedWhenPathResolved: null };
  return {
    state,
    on() { /* disconnected listener - unused in tests */ },
    async newContext(opts) {
      state.contexts += 1;
      state.lastContextOpts = opts;
      return {
        async newPage() {
          const page = {};
          if (withVideo) {
            page.video = () => ({
              path: async () => {
                state.closedWhenPathResolved = state.closedContexts;
                return videoPath;
              },
            });
          }
          return page;
        },
        async close() { state.closedContexts += 1; },
      };
    },
    async close() {},
  };
}

test('withVideoContext: passes recordVideo dir+size, closes context BEFORE reading the path, returns it', async () => {
  const fake = makeFakeVideoBrowser();
  const pool = new RenderPool({ launchBrowser: () => fake });
  const viewport = { width: 1080, height: 1920 };

  const out = await pool.withVideoContext({ viewport, videoDir: '/tmp/work' }, async () => {});

  assert.equal(out, '/tmp/clip.webm');
  assert.deepEqual(fake.state.lastContextOpts, {
    viewport,
    recordVideo: { dir: '/tmp/work', size: viewport },
  });
  assert.equal(fake.state.closedContexts, 1, 'context closed exactly once');
  assert.equal(fake.state.closedWhenPathResolved, 1, 'webm is only final AFTER context close');
  assert.equal(pool.sem.inflight, 0, 'permit released');
  assert.equal(pool.renderCount, 1);
});

test('withVideoContext: fn throwing still closes the context + releases the permit, and propagates', async () => {
  const fake = makeFakeVideoBrowser();
  const pool = new RenderPool({ launchBrowser: () => fake });
  await assert.rejects(
    () => pool.withVideoContext({ viewport: { width: 640, height: 480 }, videoDir: '/w' },
      async () => { throw new Error('record boom'); }),
    /record boom/);
  assert.equal(fake.state.closedContexts, 1, 'context closed even on failure');
  assert.equal(pool.sem.inflight, 0, 'permit released even on failure');
  assert.equal(pool.renderCount, 0, 'a failed recording does not count toward recycling');
});

test('withVideoContext: page without video() resolves to null (no crash)', async () => {
  const fake = makeFakeVideoBrowser({ withVideo: false });
  const pool = new RenderPool({ launchBrowser: () => fake });
  const out = await pool.withVideoContext({ viewport: { width: 640, height: 480 }, videoDir: '/w' }, async () => {});
  assert.equal(out, null);
  assert.equal(fake.state.closedContexts, 1);
});

test('withVideoContext: BUSY reject never runs fn and keeps permit accounting balanced', async () => {
  const fake = makeFakeVideoBrowser();
  const pool = new RenderPool({ launchBrowser: () => fake, maxConcurrent: 1, maxQueue: 0 });
  let release;
  const blocker = new Promise((r) => { release = r; });
  const first = pool.withVideoContext({ viewport: { width: 2, height: 2 }, videoDir: '/w' },
    async () => { await blocker; });
  await Promise.resolve();

  let ranSecond = false;
  await assert.rejects(
    () => pool.withVideoContext({ viewport: { width: 2, height: 2 }, videoDir: '/w' },
      async () => { ranSecond = true; }),
    (e) => e.code === 'BUSY');
  assert.equal(ranSecond, false);

  release();
  await first;
  assert.equal(pool.sem.inflight, 0);
  assert.equal(fake.state.contexts, 1, 'only the admitted request created a context');
});
