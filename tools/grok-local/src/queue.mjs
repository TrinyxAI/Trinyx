import { randomUUID } from 'node:crypto';
import { mkdir, readFile, writeFile, rename } from 'node:fs/promises';
import { dirname, join } from 'node:path';

/**
 * Persistent FIFO job queue with a single worker.
 *
 * Concurrency is deliberately 1: the upstream drives one browser tab and one
 * generation at a time, so a second worker would only interleave clicks in the
 * same session and corrupt both jobs. Depth is bounded so a runaway producer
 * gets a 429 instead of an unbounded backlog.
 *
 * State is written to disk after every transition. A restart replays the file
 * and re-queues whatever was mid-flight, because a job that was RUNNING when
 * the process died has no browser state left to resume from.
 */
export class JobQueue {
  constructor({ statePath, maxDepth = 50, historyLimit = 200, onLog = () => {} }) {
    this.statePath = statePath;
    this.maxDepth = maxDepth;
    this.historyLimit = historyLimit;
    this.onLog = onLog;
    this.jobs = new Map();
    this.pending = [];
    this.running = null;
    this.worker = null;
    this.handler = null;
    this.persistChain = Promise.resolve();
  }

  async load() {
    await mkdir(dirname(this.statePath), { recursive: true });
    let raw;
    try {
      raw = await readFile(this.statePath, 'utf8');
    } catch (err) {
      if (err.code !== 'ENOENT') throw err;
      return;
    }
    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch {
      this.onLog('warn', 'queue state file is corrupt, starting empty');
      return;
    }
    for (const job of parsed.jobs ?? []) {
      // A job left RUNNING by a crash cannot be resumed: the browser tab and
      // any half-typed prompt are gone. Send it back to the queue once.
      if (job.status === 'running') {
        job.status = 'queued';
        job.requeued = (job.requeued ?? 0) + 1;
        job.startedAt = null;
      }
      this.jobs.set(job.id, job);
      if (job.status === 'queued') this.pending.push(job.id);
    }
    this.onLog('info', `queue restored: ${this.jobs.size} jobs, ${this.pending.length} pending`);
  }

  /** Serialised so concurrent transitions cannot interleave partial writes. */
  persist() {
    this.persistChain = this.persistChain.then(async () => {
      const jobs = [...this.jobs.values()]
        .sort((a, b) => a.createdAt - b.createdAt)
        .slice(-this.historyLimit);
      const tmp = `${this.statePath}.tmp`;
      await writeFile(tmp, JSON.stringify({ jobs }, null, 2), 'utf8');
      await rename(tmp, this.statePath);
    }).catch((err) => this.onLog('error', `queue persist failed: ${err.message}`));
    return this.persistChain;
  }

  submit(params) {
    if (this.pending.length >= this.maxDepth) {
      const err = new Error(`queue is full (${this.maxDepth} waiting)`);
      err.code = 'QUEUE_FULL';
      throw err;
    }
    const job = {
      id: randomUUID(),
      status: 'queued',
      params,
      createdAt: Date.now(),
      startedAt: null,
      finishedAt: null,
      attempts: 0,
      result: null,
      error: null,
    };
    this.jobs.set(job.id, job);
    this.pending.push(job.id);
    this.persist();
    this.pump();
    return job;
  }

  get(id) {
    return this.jobs.get(id) ?? null;
  }

  /** 0 = next up. -1 = not waiting (running, done or failed). */
  position(id) {
    const idx = this.pending.indexOf(id);
    return idx;
  }

  stats() {
    let done = 0; let failed = 0;
    for (const j of this.jobs.values()) {
      if (j.status === 'done') done++;
      else if (j.status === 'failed') failed++;
    }
    return {
      queued: this.pending.length,
      running: this.running ? 1 : 0,
      done,
      failed,
      total: this.jobs.size,
    };
  }

  setHandler(fn) {
    this.handler = fn;
    this.pump();
  }

  pump() {
    if (this.worker || !this.handler) return;
    this.worker = this.drain().finally(() => { this.worker = null; });
  }

  async drain() {
    while (this.pending.length > 0) {
      const id = this.pending.shift();
      const job = this.jobs.get(id);
      if (!job || job.status !== 'queued') continue;

      job.status = 'running';
      job.startedAt = Date.now();
      job.attempts += 1;
      this.running = job.id;
      await this.persist();
      this.onLog('info', `job ${job.id} started (attempt ${job.attempts})`);

      try {
        const result = await this.handler(job);
        job.status = 'done';
        job.result = result;
        job.error = null;
        this.onLog('info', `job ${job.id} done in ${Date.now() - job.startedAt}ms`);
      } catch (err) {
        job.status = 'failed';
        job.error = { message: err.message, code: err.code ?? null };
        this.onLog('error', `job ${job.id} failed: ${err.message}`);
      } finally {
        job.finishedAt = Date.now();
        this.running = null;
        await this.persist();
      }
    }
  }
}

export function jobView(job, position) {
  if (!job) return null;
  return {
    job_id: job.id,
    status: job.status,
    position: job.status === 'queued' ? position : null,
    params: job.params,
    created_at: new Date(job.createdAt).toISOString(),
    started_at: job.startedAt ? new Date(job.startedAt).toISOString() : null,
    finished_at: job.finishedAt ? new Date(job.finishedAt).toISOString() : null,
    duration_ms: job.startedAt && job.finishedAt ? job.finishedAt - job.startedAt : null,
    attempts: job.attempts,
    result: job.result,
    error: job.error,
  };
}

export { join };
