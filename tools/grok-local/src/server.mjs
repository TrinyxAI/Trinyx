import http from 'node:http';
import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import { timingSafeEqual } from 'node:crypto';
import { JobQueue, jobView } from './queue.mjs';
import { BrowserDriver } from './browser.mjs';
import { loadConfig, parseArgs, HELP, OUTPUT_DIR, STATE_PATH, ROOT, CONFIG_PATH } from './config.mjs';
import { listConcepts, findConcept, expandConcept, buildPrompt, buildStepPrompt, isSequenceable } from './prompts.mjs';
import { scoreLoop } from './loop.mjs';

const args = parseArgs(process.argv.slice(2));
if (args.help) {
  console.log(HELP);
  process.exit(0);
}
const cfg = loadConfig(args);

const log = (level, msg) =>
  console.log(`${new Date().toISOString()} [${level}] ${msg}`);

const queue = new JobQueue({ statePath: STATE_PATH, onLog: log });
const driver = new BrowserDriver({
  outputDir: OUTPUT_DIR,
  grokUrl: cfg.grokUrl,
  headless: cfg.headless,
  onLog: log,
});

const PUBLIC_BASE = `http://${cfg.host === '0.0.0.0' ? '<this-machine>' : cfg.host}:${cfg.port}`;

/**
 * A build sequence: one generation followed by N-1 continuations.
 *
 * This runs as a SINGLE queue job on purpose. Each continuation extends the
 * result currently on screen, so an unrelated job running in between would
 * replace it and the chain would silently continue from the wrong clip.
 *
 * A step that fails does not discard the ones already produced: a four-clip
 * sequence that broke on the fifth is still usable footage, and re-running the
 * whole thing would burn quota for clips that already exist.
 */
async function runSequence(job) {
  const concept = findConcept(job.params.concept_id);
  const steps = job.params.steps;
  const clips = [];

  const first = await driver.generateVideo({
    prompt: job.params.prompt,
    durationSeconds: job.params.duration_seconds,
    jobId: `${job.id}-1`,
    timeoutMs: cfg.jobTimeoutMs,
  });
  clips.push({ step: 1, ...first, download_url: `${PUBLIC_BASE}/jobs/${job.id}/download?step=1` });
  log('info', `sequence ${job.id}: step 1/${steps} done`);

  const stepPrompt = buildStepPrompt(concept);
  for (let i = 2; i <= steps; i += 1) {
    try {
      const clip = await driver.extendVideo({
        prompt: stepPrompt,
        jobId: job.id,
        index: i,
        timeoutMs: cfg.jobTimeoutMs,
      });
      clips.push({ step: i, ...clip, download_url: `${PUBLIC_BASE}/jobs/${job.id}/download?step=${i}` });
      log('info', `sequence ${job.id}: step ${i}/${steps} done`);
    } catch (err) {
      log('warn', `sequence ${job.id}: step ${i} failed (${err.code ?? 'ERROR'}): ${err.message}`);
      return {
        job_id: job.id,
        concept_id: concept.id,
        kind: 'sequence',
        steps_requested: steps,
        steps_completed: clips.length,
        stopped_at: i,
        stop_reason: err.code ?? 'ERROR',
        stop_message: err.message,
        clips,
      };
    }
  }

  return {
    job_id: job.id,
    concept_id: concept.id,
    kind: 'sequence',
    steps_requested: steps,
    steps_completed: clips.length,
    clips,
  };
}

queue.setHandler(async (job) => {
  if (job.params.type === 'sequence') return runSequence(job);
  const result = await driver.generateVideo({
    prompt: job.params.prompt,
    durationSeconds: job.params.duration_seconds,
    jobId: job.id,
    timeoutMs: cfg.jobTimeoutMs,
  });

  // Only cyclical gestures are meant to loop. Scoring an additive one (a brick
  // laid, concrete poured) would always report a seam, which is correct and
  // useless: the scene legitimately ended somewhere else. Those are closed in
  // the edit instead, so they carry no score rather than a misleading one.
  let loop = { loop_score: null, loop_applicable: false };
  if (job.params.kind !== 'additive') {
    // Best-effort: a clip that generated fine must not be reported as failed
    // just because the measurement could not run.
    try {
      loop = { ...await scoreLoop(await driver.ensureContext(), result.path), loop_applicable: true };
    } catch (err) {
      log('warn', `loop scoring failed for ${job.id}: ${err.message}`);
      loop = { loop_score: null, loop_applicable: true, loop_error: err.message };
    }
  }

  return {
    ...result,
    ...loop,
    job_id: job.id,
    concept_id: job.params.concept_id ?? null,
    kind: job.params.kind ?? null,
    roll: job.params.roll ?? null,
    download_url: `${PUBLIC_BASE}/jobs/${job.id}/download`,
  };
});

/** Constant-time compare so the token cannot be probed byte by byte. */
function authorised(req) {
  const header = req.headers.authorization ?? '';
  const presented = Buffer.from(header.startsWith('Bearer ') ? header.slice(7) : '');
  const expected = Buffer.from(cfg.token);
  return presented.length === expected.length && timingSafeEqual(presented, expected);
}

function send(res, status, body, headers = {}) {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', ...headers });
  res.end(typeof body === 'string' ? body : JSON.stringify(body, null, 2));
}

async function readJson(req, limit = 64 * 1024) {
  const chunks = [];
  let total = 0;
  for await (const chunk of req) {
    total += chunk.length;
    if (total > limit) throw new Error('request body too large');
    chunks.push(chunk);
  }
  return total === 0 ? {} : JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host ?? 'localhost'}`);
  const { pathname } = url;

  if (pathname === '/health' && req.method === 'GET') {
    const status = await driver.status();
    return send(res, status.browser_ok && status.signed_in ? 200 : 503,
      { ...status, queue: queue.stats() });
  }

  if (!authorised(req)) return send(res, 401, { error: 'unauthorized' });

  if (pathname === '/concepts' && req.method === 'GET') {
    return send(res, 200, { concepts: listConcepts() });
  }

  // Queue a whole concept at once. A clean loop is mostly luck, so the useful
  // unit of work is a batch of rolls to choose from, not a single generation.
  // Build sequences: additive gestures chained into a progression. A wall going
  // up reads better than the same brick looping, and sidesteps the loop problem
  // entirely rather than fighting it.
  const seqMatch = pathname.match(/^\/concepts\/([a-z0-9_]+)\/sequence$/i);
  if (seqMatch && req.method === 'POST') {
    const concept = findConcept(seqMatch[1]);
    if (!concept) return send(res, 404, { error: `unknown concept '${seqMatch[1]}'` });
    if (!isSequenceable(concept)) {
      return send(res, 409, {
        error: `'${concept.id}' is a ${concept.kind} gesture and has no continuation step`,
        hint: 'sequences are for additive concepts; cyclical ones are meant to loop, use /batch',
      });
    }
    let body;
    try {
      body = await readJson(req);
    } catch (err) {
      return send(res, 400, { error: err.message });
    }
    const steps = body.steps ?? 4;
    if (!Number.isInteger(steps) || steps < 2 || steps > 10) {
      return send(res, 400, { error: 'steps must be an integer between 2 and 10' });
    }
    const duration = body.duration_seconds ?? 6;
    if (!Number.isInteger(duration) || duration < 1 || duration > 15) {
      return send(res, 400, { error: 'duration_seconds must be an integer between 1 and 15' });
    }
    try {
      const seqJob = queue.submit({
        type: 'sequence',
        concept_id: concept.id,
        kind: 'sequence',
        steps,
        duration_seconds: duration,
        prompt: buildPrompt(concept, { aspectRatio: body.aspect_ratio ?? '9:16' }),
      });
      return send(res, 202, {
        ...jobView(seqJob, queue.position(seqJob.id)),
        note: `${steps} clips of ${duration}s, generated back to back as one job`,
      });
    } catch (err) {
      if (err.code === 'QUEUE_FULL') return send(res, 429, { error: err.message });
      throw err;
    }
  }

  const batchMatch = pathname.match(/^\/concepts\/([a-z0-9_]+)\/batch$/i);
  if (batchMatch && req.method === 'POST') {
    const concept = findConcept(batchMatch[1]);
    if (!concept) return send(res, 404, { error: `unknown concept '${batchMatch[1]}'` });
    let body;
    try {
      body = await readJson(req);
    } catch (err) {
      return send(res, 400, { error: err.message });
    }
    const rolls = body.rolls ?? 3;
    if (!Number.isInteger(rolls) || rolls < 1 || rolls > 5) {
      return send(res, 400, { error: 'rolls must be an integer between 1 and 5' });
    }
    const variants = expandConcept(concept, {
      rolls,
      aspectRatio: body.aspect_ratio ?? '9:16',
      durationSeconds: body.duration_seconds ?? 6,
    });
    const queued = [];
    for (const variant of variants) {
      try {
        const job = queue.submit(variant);
        queued.push(jobView(job, queue.position(job.id)));
      } catch (err) {
        if (err.code === 'QUEUE_FULL') break;
        throw err;
      }
    }
    if (queued.length === 0) return send(res, 429, { error: 'queue is full' });
    return send(res, 202, {
      concept: concept.id,
      requested: variants.length,
      queued: queued.length,
      jobs: queued,
    });
  }

  if (pathname === '/jobs' && req.method === 'POST') {
    let body;
    try {
      body = await readJson(req);
    } catch (err) {
      return send(res, 400, { error: err.message });
    }
    const prompt = typeof body.prompt === 'string' ? body.prompt.trim() : '';
    if (!prompt) return send(res, 400, { error: 'prompt is required' });
    if (prompt.length > 2000) return send(res, 400, { error: 'prompt exceeds 2000 characters' });
    const duration = body.duration_seconds;
    if (duration !== undefined && duration !== null
        && (!Number.isInteger(duration) || duration < 1 || duration > 15)) {
      return send(res, 400, { error: 'duration_seconds must be an integer between 1 and 15' });
    }
    try {
      const job = queue.submit({ prompt, duration_seconds: duration ?? null });
      return send(res, 202, jobView(job, queue.position(job.id)));
    } catch (err) {
      if (err.code === 'QUEUE_FULL') return send(res, 429, { error: err.message });
      throw err;
    }
  }

  if (pathname === '/jobs' && req.method === 'GET') {
    return send(res, 200, { queue: queue.stats() });
  }

  const match = pathname.match(/^\/jobs\/([0-9a-f-]{36})(\/download)?$/i);
  if (match && req.method === 'GET') {
    const job = queue.get(match[1]);
    if (!job) return send(res, 404, { error: 'unknown job' });
    if (!match[2]) return send(res, 200, jobView(job, queue.position(job.id)));
    if (job.status !== 'done') {
      return send(res, 409, { error: `job is ${job.status}, no artifact to download` });
    }

    // A sequence holds several clips, addressed by ?step=N. Without a step the
    // caller gets the list rather than an arbitrary one of them.
    let path = job.result?.path ?? null;
    let filename = `${job.id}.mp4`;
    if (job.result?.kind === 'sequence') {
      const step = Number(url.searchParams.get('step'));
      if (!step) {
        return send(res, 409, {
          error: 'this job is a sequence, pick a clip with ?step=N',
          steps: job.result.clips.map((c) => c.step),
        });
      }
      const clip = job.result.clips.find((c) => c.step === step);
      if (!clip) return send(res, 404, { error: `no step ${step} in this sequence` });
      path = clip.path;
      filename = `${job.id}-${step}.mp4`;
    }
    if (!path) return send(res, 409, { error: 'no artifact on this job' });

    let info;
    try {
      info = await stat(path);
    } catch {
      return send(res, 410, { error: 'artifact no longer on disk' });
    }
    res.writeHead(200, {
      'content-type': 'video/mp4',
      'content-length': info.size,
      'content-disposition': `attachment; filename="${filename}"`,
    });
    return createReadStream(path).pipe(res);
  }

  if (pathname === '/debug/snapshot' && req.method === 'GET') {
    try {
      return send(res, 200, await driver.debugSnapshot());
    } catch (err) {
      return send(res, 500, { error: err.message });
    }
  }

  if (pathname === '/debug/calibrate' && req.method === 'GET') {
    try {
      return send(res, 200, await driver.calibrate());
    } catch (err) {
      return send(res, 500, { error: err.message });
    }
  }

  return send(res, 404, { error: 'not found' });
});

server.on('clientError', (_err, socket) => socket.destroy());

await queue.load();
queue.pump();

server.listen(cfg.port, cfg.host, () => {
  log('info', `listening on http://${cfg.host}:${cfg.port}`);
  log('info', `state in ${ROOT}`);
  if (cfg.created) {
    console.log('\n  A bearer token was generated for this machine:\n');
    console.log(`    ${cfg.token}\n`);
    console.log(`  It is stored in ${CONFIG_PATH} and will not be printed again.\n`);
  }
  if (cfg.host === '0.0.0.0') {
    log('warn', 'bound to every interface: anyone who can reach this port and holds the token controls the browser');
  }
  log('info', 'open the window and sign in once, then GET /health to confirm signed_in=true');
});

for (const sig of ['SIGTERM', 'SIGINT']) {
  process.on(sig, async () => {
    log('info', `${sig} received, shutting down`);
    server.close();
    await driver.close();
    process.exit(0);
  });
}
