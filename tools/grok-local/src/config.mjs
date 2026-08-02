import { homedir } from 'node:os';
import { join } from 'node:path';
import { mkdirSync, readFileSync, writeFileSync, existsSync, chmodSync } from 'node:fs';
import { randomBytes } from 'node:crypto';

/**
 * Everything lives under one directory in the user's home so the tool is
 * portable: copy the repo to another machine, run it, sign in once, done.
 * Nothing is written inside the checkout, so `git pull` never clobbers state.
 */
export const ROOT = process.env.GROK_LOCAL_HOME ?? join(homedir(), '.grok-local');
export const PROFILE_DIR = join(ROOT, 'profile');
export const OUTPUT_DIR = join(ROOT, 'output');
export const STATE_PATH = join(ROOT, 'state', 'jobs.json');
export const CONFIG_PATH = join(ROOT, 'config.json');

function ensureDirs() {
  for (const dir of [ROOT, PROFILE_DIR, OUTPUT_DIR, join(ROOT, 'state')]) {
    mkdirSync(dir, { recursive: true });
  }
}

/**
 * The token is generated once and kept on disk. It is not optional: the server
 * controls a browser holding a signed-in session, so an unauthenticated
 * endpoint would hand that session to anything able to reach the port.
 */
export function loadConfig(overrides = {}) {
  ensureDirs();
  let cfg = {};
  if (existsSync(CONFIG_PATH)) {
    try {
      cfg = JSON.parse(readFileSync(CONFIG_PATH, 'utf8'));
    } catch {
      console.warn(`[grok-local] ${CONFIG_PATH} is unreadable, regenerating`);
    }
  }
  let created = false;
  if (!cfg.token) {
    cfg.token = randomBytes(32).toString('base64url');
    created = true;
  }
  cfg.host ??= '127.0.0.1';
  cfg.port ??= 8477;
  cfg.grokUrl ??= 'https://grok.com/imagine';
  cfg.jobTimeoutMs ??= 300_000;
  cfg.headless ??= false;

  const merged = { ...cfg, ...overrides };
  if (created || !existsSync(CONFIG_PATH)) {
    writeFileSync(CONFIG_PATH, JSON.stringify({ ...cfg }, null, 2), 'utf8');
    try {
      chmodSync(CONFIG_PATH, 0o600);
    } catch {
      // Windows ignores POSIX modes; the file still sits in the user profile.
    }
  }
  return { ...merged, created };
}

export function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--lan') out.host = '0.0.0.0';
    else if (arg === '--host') out.host = argv[++i];
    else if (arg === '--port') out.port = Number(argv[++i]);
    else if (arg === '--headless') out.headless = true;
    else if (arg === '--url') out.grokUrl = argv[++i];
    else if (arg === '--help' || arg === '-h') out.help = true;
  }
  return out;
}

export const HELP = `
grok-local - drive Grok Imagine from your own signed-in browser and expose it over HTTP

  npm start                 listen on 127.0.0.1:8477 (default)
  npm start -- --lan        listen on every interface, so another machine on the LAN can drive it
  npm start -- --port 9000  change the port
  npm start -- --headless   run the browser hidden (sign in first, with a visible window)

State lives in ${ROOT}
  profile/   the browser profile: your session persists here, sign in once
  output/    generated clips
  config.json  host, port and the bearer token (chmod 600)

Endpoints (all but /health need "Authorization: Bearer <token>"):
  GET  /health                 browser + session status, queue depth
  POST /jobs                   {prompt, duration_seconds?} -> 202 {job_id, position}
  GET  /jobs                   queue counters
  GET  /jobs/:id               one job
  GET  /jobs/:id/download      the finished mp4
  GET  /debug/snapshot         screenshot + html, to see what the page looks like
  GET  /debug/calibrate        check the selectors still match the live UI
`;
