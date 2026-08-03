/**
 * Pure formatting / status helpers shared by every run surface:
 * the compact canvas run bar, the side-panel Run tab (summary + steps +
 * epochs) and the run history list.
 *
 * Dependency-free on purpose so they stay trivially unit-testable and can be
 * imported from a server-rendered module without pulling in React.
 */

import { TERMINAL_STATUSES } from '@/contexts/workflow-run/RunStateStore';
import { parseUtcAware } from '@/lib/utils/dateFormatters';

/**
 * Run statuses a trigger can no longer be fired into (the dispatcher rejects it).
 *
 * Derived from {@link TERMINAL_STATUSES}, the single source of truth that mirrors
 * the backend's {@code RunStatus.isTerminal()}. The run surfaces used to carry
 * their own hardcoded copy, which had drifted: it omitted `stopped`, so a STOPPED
 * run was never offered the Reactivate button (right under a comment claiming
 * "every terminal status is reactivatable") and still showed a fire button the
 * dispatcher would refuse. Upper-cased here because the run REST payload reports
 * the status upper-case while the store keeps it lower-case.
 */
export const TERMINAL_RUN_STATUSES: ReadonlySet<string> = new Set(
  [...TERMINAL_STATUSES].map(s => s.toUpperCase()),
);

export interface EpochTimestamp {
  epoch: number;
  startedAt: string;
  endedAt: string | null;
  /**
   * How long the epoch spent EXECUTING, from its first node starting to its last
   * node finishing. Absent while nothing has run yet.
   *
   * `endedAt - startedAt` is NOT this figure: the close is stamped when the epoch is
   * reconciled, which can be a resume or a restart recovery sweep long after the last
   * node finished, so that span counts the idle tail and reported things like 32h42m
   * for epochs that really execute in seconds. Use the timestamps to place the epoch
   * on the timeline, this to say how long it took.
   */
  workDurationMs?: number | null;
}

export interface StepStatusCounts {
  completed?: number;
  failed?: number;
  skipped?: number;
  running?: number;
  awaitingSignal?: number;
}

export interface StepEntry {
  alias: string;
  toolId?: string;
  status: string;
  startTime: string | null;
  endTime: string | null;
  executionTimeMs?: number;
  totalExecutionTimeMs?: number;
  statusCounts?: StepStatusCounts;
}

/** `1.4s` / `42s` / `3m07s` / `2h05m` - never wider than 6 chars. */
export function formatCompactDuration(ms: number): string {
  if (ms < 1000) return '<1s';
  const sec = ms / 1000;
  if (sec < 10) return `${sec.toFixed(1)}s`;
  if (sec < 60) return `${Math.round(sec)}s`;
  const minutes = Math.floor(sec / 60);
  const remSec = Math.round(sec % 60);
  if (minutes < 60) return `${minutes}m${String(remSec).padStart(2, '0')}s`;
  const hours = Math.floor(minutes / 60);
  const remainMin = minutes % 60;
  return `${hours}h${String(remainMin).padStart(2, '0')}m`;
}

/** Tailwind classes for a waterfall duration bar, by effective step status. */
export function getBarColor(status: string): string {
  switch (status) {
    case 'error':
    case 'failed':
    case 'partial_success':
      return 'bg-red-400/80 dark:bg-red-500/70';
    case 'running':
    case 'pending':
      return 'bg-blue-500 animate-pulse';
    case 'skipped':
      return 'bg-gray-300 dark:bg-gray-600';
    case 'awaiting_signal':
      return 'bg-amber-400/80 dark:bg-amber-500/70';
    default:
      return 'bg-emerald-500/80 dark:bg-emerald-500/70';
  }
}

/**
 * Derive the effective display status from statusCounts (multi-epoch aggregate).
 * Priority: running > awaiting_signal > failed/partial > completed > skipped > raw.
 */
export function deriveEffectiveStatus(
  rawStatus: string,
  statusCounts?: StepStatusCounts,
): string {
  if (!statusCounts) return rawStatus;
  const { completed = 0, failed = 0, running = 0, awaitingSignal = 0, skipped = 0 } = statusCounts;
  if (running > 0) return 'running';
  if (awaitingSignal > 0) return 'awaiting_signal';
  if (failed > 0) return completed > 0 ? 'partial_success' : 'failed';
  if (completed > 0) return 'completed';
  if (skipped > 0) return 'skipped';
  return rawStatus;
}

/** True while the run can still accept a trigger fire (non-terminal status). */
export function isRunStatusActive(status: string | null | undefined): boolean {
  const upper = (status || '').toUpperCase();
  return !!upper && !TERMINAL_RUN_STATUSES.has(upper);
}

/**
 * How long to say an epoch took, in milliseconds.
 *
 * An epoch that is CLOSED reports the window its nodes executed, which the backend
 * measures on the step rows. `endedAt - startedAt` must NOT be used: the close is
 * stamped when the epoch is reconciled, which can be a resume or a restart recovery
 * sweep hours or days after the last node finished. That span is where the run
 * history's 32h42m came from, for epochs whose nodes ran for seconds.
 *
 * An epoch that is still OPEN reports elapsed-since-start, and it ticks. The normal
 * close happens at the end of the cycle, so an open epoch is one that has not
 * finished: either it is executing, or it is blocked on an approval or an in-flight
 * agent, which is the one case that defers the close. Both are genuinely still in
 * progress, and the row shows the pulsing "running" marker beside the figure, so a
 * growing number reads correctly. The settled window would under-report them: an
 * epoch three minutes into an approval is not a two-second epoch.
 *
 * Returns null for "unknown", which is NOT the same as 0. Zero is a measurement (an
 * all-skipped epoch really does start and end at the same instant); null means the
 * payload never carried a window, so callers must render nothing rather than assert
 * the epoch was instantaneous.
 */
export function epochDisplayDurationMs(
  entry: Pick<EpochTimestamp, 'startedAt' | 'endedAt' | 'workDurationMs'>,
  now: number,
): number | null {
  const measured = entry.workDurationMs != null ? Math.max(0, entry.workDurationMs) : null;
  if (!entry.startedAt) return measured;
  // Settled epoch: only the measured window can answer. Null means the payload never
  // carried one - a showcase snapshot frozen before this field existed, or a
  // frontend running ahead of its orchestrator mid-deploy. Callers render nothing.
  // Returning 0 here would print "<1s", a confident claim that the epoch was
  // instantaneous, on every epoch of every application published before this shipped.
  if (entry.endedAt) return measured;
  const start = parseUtcAware(entry.startedAt).getTime();
  if (isNaN(start)) return measured;
  // Open epoch: the larger of the two. Elapsed is the truth for the epoch as a
  // whole, and the measured window guards against a start timestamp in the future
  // (a client clock ahead of the server) collapsing a real duration to zero.
  return Math.max(measured ?? 0, Math.max(0, now - start));
}
