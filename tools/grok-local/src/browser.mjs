import { chromium } from 'playwright';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { PROFILE_DIR } from './config.mjs';

/**
 * Owns a persistent browser profile on this machine. The profile directory
 * carries the session, so signing in once survives restarts and reboots -
 * this module never sees or stores credentials.
 *
 * Unlike a server-side setup, the window is visible by default: you sign in
 * yourself the first time, and you can watch what the automation does.
 *
 * SELECTORS is the only part coupled to the upstream UI. Entries are role and
 * text based rather than CSS paths, and `calibrate()` reports which ones still
 * resolve, so a redesign is a one-line fix instead of a debugging session.
 */
const SELECTORS = {
  composer: '[contenteditable="true"]',
  loginWall: 'text=/sign in|log in|se connecter|s.inscrire/i',
  submit: 'button[type="submit"], button[aria-label="Valider"], button[aria-label*="send" i]',
  // The generation mode is a button group (aria-label "Mode de génération"),
  // not a tab strip. The UI is localised, so match either label.
  videoMode: 'button[aria-label="Vidéo"], button[aria-label="Video"]',
  anyVideo: 'video',
  // Two engines cannot be mixed in one selector string; keep them apart.
  // Continuing a clip from its last frame. The API exposes this as
  // /v1/videos/extensions; whether the web UI surfaces it is checked by
  // /debug/calibrate rather than assumed.
  extendControl: 'button[aria-label*="tend" i], button[aria-label*="ontinu" i], button:has-text("Extend"), button:has-text("Prolonger"), button:has-text("Continuer")',
  alertRole: '[role="alert"]',
  refusalText: 'text=/quota|limit|rate limit|try again|réessay/i',
};

export class BrowserDriver {
  constructor({ outputDir, grokUrl, headless, onLog = () => {} }) {
    this.outputDir = outputDir;
    this.grokUrl = grokUrl;
    this.headless = headless;
    this.onLog = onLog;
    this.context = null;
    this.starting = null;
  }

  /** Single-flight: concurrent callers share one launch instead of racing. */
  async ensureContext() {
    if (this.context) return this.context;
    if (this.starting) return this.starting;
    this.starting = (async () => {
      this.onLog('info', `launching browser (headless=${this.headless}) profile=${PROFILE_DIR}`);
      const ctx = await chromium.launchPersistentContext(PROFILE_DIR, {
        headless: this.headless,
        viewport: null,
        args: ['--no-first-run', '--no-default-browser-check', '--disable-blink-features=AutomationControlled'],
      });
      ctx.on('close', () => {
        this.onLog('warn', 'browser context closed');
        this.context = null;
      });
      this.context = ctx;
      return ctx;
    })().finally(() => { this.starting = null; });
    return this.starting;
  }

  async page() {
    const ctx = await this.ensureContext();
    const pages = ctx.pages();
    return pages.find((p) => p.url().includes('grok.com')) ?? pages[0] ?? ctx.newPage();
  }

  /** Never throws: /health must answer even when the browser is unhappy. */
  async status() {
    try {
      const page = await this.page();
      if (!page.url().includes('grok.com')) {
        await page.goto(this.grokUrl, { waitUntil: 'domcontentloaded', timeout: 30_000 });
      }
      const composer = await page.locator(SELECTORS.composer).first()
        .isVisible({ timeout: 5_000 }).catch(() => false);
      const wall = await page.locator(SELECTORS.loginWall).first()
        .isVisible({ timeout: 1_000 }).catch(() => false);
      return { browser_ok: true, signed_in: composer && !wall, url: page.url() };
    } catch (err) {
      return { browser_ok: false, signed_in: false, error: err.message };
    }
  }

  async debugSnapshot(name = 'debug') {
    const page = await this.page();
    const png = join(this.outputDir, `${name}.png`);
    await page.screenshot({ path: png });
    const htmlPath = join(this.outputDir, `${name}.html`);
    await writeFile(htmlPath, (await page.content()).slice(0, 500_000), 'utf8');
    return { screenshot: png, html: htmlPath, url: page.url() };
  }

  async calibrate() {
    const page = await this.page();
    if (!page.url().includes('grok.com')) {
      await page.goto(this.grokUrl, { waitUntil: 'domcontentloaded', timeout: 30_000 });
    }
    const report = {};
    for (const [name, selector] of Object.entries(SELECTORS)) {
      report[name] = { selector, matches: await page.locator(selector).count().catch(() => -1) };
    }
    return report;
  }

  /** Every video src currently on the page, including <source> children. */
  async videoSources(page) {
    return page.locator(SELECTORS.anyVideo).evaluateAll((nodes) =>
      nodes
        .map((n) => n.currentSrc || n.getAttribute('src')
          || n.querySelector('source')?.getAttribute('src'))
        .filter(Boolean));
  }

  /**
   * Returns the text of a visible refusal, or null. Kept separate from the
   * happy path because a quota message and a missing video look identical
   * to a plain "did a video appear" check.
   */
  async refusalText(page) {
    for (const selector of [SELECTORS.alertRole, SELECTORS.refusalText]) {
      const node = page.locator(selector).first();
      if (await node.isVisible({ timeout: 300 }).catch(() => false)) {
        const text = (await node.textContent().catch(() => '')) ?? '';
        if (/quota|limit|rate|réessay|try again/i.test(text)) return text.trim();
      }
    }
    return null;
  }

  /**
   * Continues the clip currently on screen from its last frame.
   *
   * This is what makes a construction sequence work: additive gestures cannot
   * loop, so a wall going up is built by chaining continuations rather than by
   * trying to make the last frame match the first.
   *
   * Requires the previous generation to still be the active result on the page,
   * which is why sequences run as one queue job rather than as N independent
   * ones - an interleaved job would replace the result being extended.
   */
  async extendVideo({ prompt, jobId, index, timeoutMs = 300_000 }) {
    const page = await this.page();
    const deadline = Date.now() + timeoutMs;

    const control = page.locator(SELECTORS.extendControl).first();
    if (!await control.isVisible({ timeout: 5_000 }).catch(() => false)) {
      const err = new Error(
        'no extend control on the page: this build of the UI does not expose continuation, '
        + 'run /debug/calibrate to see which selectors resolve');
      err.code = 'NO_EXTEND';
      throw err;
    }

    const before = new Set(await this.videoSources(page));
    await control.click();

    // Some builds ask for a prompt for the continuation, some just continue.
    const composer = page.locator(SELECTORS.composer).first();
    if (prompt && await composer.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await composer.click();
      await composer.fill('');
      await composer.type(prompt, { delay: 15 });
      const submit = page.locator(SELECTORS.submit).first();
      if (await submit.isVisible({ timeout: 3_000 }).catch(() => false)) await submit.click();
      else await composer.press('Enter');
    }

    let videoUrl = null;
    while (Date.now() < deadline) {
      const refusal = await this.refusalText(page);
      if (refusal) {
        const err = new Error(`refused by the site: ${refusal.slice(0, 200)}`);
        err.code = 'QUOTA';
        throw err;
      }
      const fresh = (await this.videoSources(page)).filter((src) => !before.has(src));
      if (fresh.length > 0) {
        [videoUrl] = fresh;
        break;
      }
      await page.waitForTimeout(2_000);
    }
    if (!videoUrl) {
      const err = new Error(`no continuation appeared within ${timeoutMs}ms`);
      err.code = 'TIMEOUT';
      throw err;
    }

    return this.saveVideo(page, videoUrl, `${jobId}-${index}`);
  }

  /** Pull the bytes through the page's own session and write them to disk. */
  async saveVideo(page, videoUrl, name) {
    const absolute = new URL(videoUrl, page.url()).toString();
    const resp = await page.request.get(absolute, { timeout: 120_000 });
    if (!resp.ok()) {
      const err = new Error(`could not fetch the clip: HTTP ${resp.status()}`);
      err.code = 'DOWNLOAD_FAILED';
      throw err;
    }
    const bytes = await resp.body();
    const path = join(this.outputDir, `${name}.mp4`);
    await writeFile(path, bytes);
    return { path, bytes: bytes.length, source_url: absolute };
  }

  /**
   * One generation. Returns the local path of the downloaded mp4.
   *
   * The finished clip is served from a short-lived signed URL, so the bytes are
   * pulled immediately through the page's own request context (session cookies
   * attached) and written to disk. Callers get a file that outlives the URL.
   */
  async generateVideo({ prompt, durationSeconds, jobId, timeoutMs = 300_000 }) {
    const page = await this.page();
    const deadline = Date.now() + timeoutMs;

    await page.goto(this.grokUrl, { waitUntil: 'domcontentloaded', timeout: 45_000 });

    const videoMode = page.locator(SELECTORS.videoMode).first();
    await videoMode.waitFor({ state: 'visible', timeout: 20_000 });
    await videoMode.click();

    const composer = page.locator(SELECTORS.composer).first();
    await composer.waitFor({ state: 'visible', timeout: 20_000 });
    await composer.click();
    await composer.fill('');
    await composer.type(prompt, { delay: 15 });

    // The landing page already renders a discovery feed full of <video> tags,
    // so "a video exists" is not a completion signal. Record what is on the
    // page before submitting and wait for a src that was not there before.
    const before = new Set(await this.videoSources(page));

    const submit = page.locator(SELECTORS.submit).first();
    if (await submit.isVisible({ timeout: 3_000 }).catch(() => false)) await submit.click();
    else await composer.press('Enter');

    // Poll for a genuinely new video or an explicit refusal. A quota message is
    // terminal - waiting the full timeout on it would just burn five minutes.
    let videoUrl = null;
    while (Date.now() < deadline) {
      const refusal = await this.refusalText(page);
      if (refusal) {
        const err = new Error(`refused by the site: ${refusal.slice(0, 200)}`);
        err.code = 'QUOTA';
        throw err;
      }
      const fresh = (await this.videoSources(page)).filter((src) => !before.has(src));
      if (fresh.length > 0) {
        [videoUrl] = fresh;
        break;
      }
      await page.waitForTimeout(2_000);
    }

    if (!videoUrl) {
      const err = new Error(`no video appeared within ${timeoutMs}ms`);
      err.code = 'TIMEOUT';
      throw err;
    }

    const saved = await this.saveVideo(page, videoUrl, jobId);
    return { ...saved, duration_requested: durationSeconds ?? null };
  }

  async close() {
    if (this.context) await this.context.close().catch(() => {});
    this.context = null;
  }
}

export { SELECTORS };
