/**
 * Pure formatting / status helpers shared by every run surface:
 * the compact canvas run bar, the side-panel Run tab (summary + steps +
 * epochs) and the run history list.
 *
 * Dependency-free on purpose so they stay trivially unit-testable and can be
 * imported from a server-rendered module without pulling in React.
 */

import { TERMINAL_STATUSES } from '@/contexts/workflow-run/RunStateStore';

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
 * The epoch that should be selected by default: the most recent one.
 *
 * The run surfaces never show "no epoch" - a run is always read through one of
 * its epochs - so callers seed their selection with this and re-apply it as new
 * epochs open, until the user picks one explicitly.
 */
export function latestEpoch(epochTimestamps: readonly EpochTimestamp[]): number | null {
  if (!epochTimestamps || epochTimestamps.length === 0) return null;
  return epochTimestamps.reduce((max, e) => (e.epoch > max ? e.epoch : max), epochTimestamps[0].epoch);
}
