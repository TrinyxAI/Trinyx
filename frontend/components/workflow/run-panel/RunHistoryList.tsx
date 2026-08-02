'use client';

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslations } from 'next-intl';
import {
  History, RefreshCw, XCircle, StepForward, Pin, Calendar, FlaskConical,
  CheckCircle2, Clock, AlertCircle, CircleSlash, PauseCircle, PlayCircle,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { orchestratorApi, type WorkflowRun } from '@/lib/api/orchestrator';
import { getClientLocale } from '@/lib/utils/locale';
import { formatRelativeDateI18n, formatUtcDateTime, parseUtcAware } from '@/lib/utils/dateFormatters';
import { getRunDisplayStatus } from '@/lib/utils/runStatusUtils';
import { formatCompactDuration } from './runFormatting';

interface RunHistoryListProps {
  workflowId?: string;
  /** Run currently displayed by the canvas - rendered as the selected row. */
  currentRunId?: string | null;
  /** Called with the picked run: the caller binds the canvas + drills into it. */
  onSelectRun: (run: WorkflowRun) => void;
}

const LIMIT = 15;

type RunRowStatus =
  | 'pending' | 'waiting_trigger' | 'running' | 'paused' | 'awaiting_signal'
  | 'completed' | 'failed' | 'partial_success' | 'cancelled' | 'timeout' | 'stopped' | 'skipped';

/**
 * Every RunStatus the backend can report gets its own row visual.
 *
 * The former history panel mapped six values and defaulted the rest to
 * "pending", so a live reusable run - which RESTS in WAITING_TRIGGER between
 * fires - showed up as a pending amber clock in its own history. This covers all
 * eleven values of the backend enum, plus `stopped`, which the enum does not have
 * but older rows and the streaming layer still carry (normalized to "cancelled"
 * elsewhere in the app). An unknown value degrades to `pending` rather than
 * rendering nothing.
 */
const mapRunStatus = (status: string): RunRowStatus => {
  const normalized = (status || '').toLowerCase();
  switch (normalized) {
    case 'pending':
    case 'waiting_trigger':
    case 'running':
    case 'paused':
    case 'awaiting_signal':
    case 'completed':
    case 'failed':
    case 'partial_success':
    case 'cancelled':
    case 'timeout':
    case 'stopped':
    case 'skipped':
      return normalized as RunRowStatus;
    default:
      return 'pending';
  }
};

/**
 * Status as a single ICON in the row's leading column.
 *
 * Deliberately not `StatusBadge`: even its `noBackground` variant renders the
 * translated LABEL next to the icon, which blows out the fixed `w-6` column the
 * rows share with the epoch list and wraps the whole row. The label is carried
 * by the `title` instead, so the row keeps the epoch-row geometry.
 */
const RUN_STATUS_ICON: Record<RunRowStatus, { icon: React.ReactNode; className: string }> = {
  completed: { icon: <CheckCircle2 className="w-3.5 h-3.5" />, className: 'text-emerald-600 dark:text-emerald-400' },
  failed: { icon: <XCircle className="w-3.5 h-3.5" />, className: 'text-red-500 dark:text-red-400' },
  cancelled: { icon: <XCircle className="w-3.5 h-3.5" />, className: 'text-gray-400 dark:text-gray-500' },
  running: { icon: <RefreshCw className="w-3.5 h-3.5 animate-spin" />, className: 'text-blue-500 dark:text-blue-400' },
  // The resting state of a live reusable run, between two fires - not "pending".
  waiting_trigger: { icon: <PlayCircle className="w-3.5 h-3.5" />, className: 'text-blue-500 dark:text-blue-400' },
  paused: { icon: <PauseCircle className="w-3.5 h-3.5" />, className: 'text-amber-500 dark:text-amber-400' },
  // Blocked on an approval / timer / webhook: the run is alive and waiting on
  // someone, which is not the same thing as "pending".
  awaiting_signal: { icon: <PauseCircle className="w-3.5 h-3.5" />, className: 'text-violet-500 dark:text-violet-400' },
  pending: { icon: <Clock className="w-3.5 h-3.5" />, className: 'text-amber-500 dark:text-amber-400' },
  partial_success: { icon: <AlertCircle className="w-3.5 h-3.5" />, className: 'text-amber-600 dark:text-amber-400' },
  timeout: { icon: <Clock className="w-3.5 h-3.5" />, className: 'text-orange-500 dark:text-orange-400' },
  stopped: { icon: <CircleSlash className="w-3.5 h-3.5" />, className: 'text-gray-500 dark:text-gray-400' },
  skipped: { icon: <CircleSlash className="w-3.5 h-3.5" />, className: 'text-gray-400 dark:text-gray-500' },
};

/**
 * How long the workflow's last execution took.
 *
 * The column used to print a bare "-" on every row, and not by accident: a
 * reusable run never ends. It rests in WAITING_TRIGGER between fires and
 * accumulates epochs, so it has neither `durationMs` nor `endedAt`, and the
 * formatter answers "-" when it cannot measure. Since runs aggregate rather than
 * fork, that is the state of MOST rows.
 *
 * With nothing to measure at all - no epoch has closed and the run has not
 * finished - the cell is EMPTY: a blank reads as "not applicable", a dash reads
 * as a broken value.
 */
const formatRunDuration = (run: WorkflowRun): { text: string; isLastEpoch: boolean } => {
  // The LAST EXECUTION comes first, and it is read from the epoch row rather than
  // reconstructed here. Two reasons the obvious alternatives are wrong:
  //
  //  - the whole-run span is not a fallback, it is a trap. `cancelStaleRuns`
  //    stamps `endedAt` on every resting run whenever a new one is created, so a
  //    reusable run fired daily for a week reports SEVEN DAYS as its "duration" -
  //    a plausible-looking number that answers a question nobody asked;
  //  - `lastCycleAt` minus `lastFireAt` looks equivalent but is not: the engine
  //    runs concurrent epochs across trigger DAGs, so those two timestamps can
  //    belong to different epochs and even different triggers, and the difference
  //    comes out positive and confidently wrong.
  //
  // Every branch goes through formatCompactDuration: the column is fixed-width and
  // tabular, and mixing it with dateFormatters' spaced style ("1m 30s" next to
  // "1m30s") makes the same quantity look like two different fields.
  if (run.lastEpochDurationMs != null) {
    return { text: formatCompactDuration(run.lastEpochDurationMs), isLastEpoch: true };
  }

  // A run that finished WITHOUT ever opening an epoch - a single-shot
  // step-by-step run, or one that ended before the first fire. There the whole
  // run IS the single execution, so its span means what it says.
  //
  // Gated on having no epoch at all, which is what separates it from the trap
  // above: a stale-cancelled reusable run also carries `endedAt`, but it has
  // epochs, so it never reaches here. (`durationMs` is not usable - nothing in
  // the orchestrator ever writes it on a run.)
  const neverOpenedAnEpoch = !run.currentEpoch;
  if (neverOpenedAnEpoch && run.startedAt && (run.endedAt || run.completedAt)) {
    const span = parseUtcAware((run.endedAt || run.completedAt)!).getTime()
      - parseUtcAware(run.startedAt).getTime();
    if (span >= 0) return { text: formatCompactDuration(span), isLastEpoch: false };
  }

  return { text: '', isLastEpoch: false };
};

/**
 * Every run of the workflow, as the PARENT level of the Run tab: picking a row
 * binds that run and drills into its epochs / steps.
 *
 * Rows deliberately mirror the epoch rows of {@link EpochSelector} (same
 * `px-3 py-2.5` metrics, same `w-6` leading column, same left-border
 * selected state) so moving between "which run" and "which epoch" reads as one
 * continuous hierarchy instead of two unrelated lists.
 */
export function RunHistoryList({ workflowId, currentRunId, onSelectRun }: RunHistoryListProps) {
  const t = useTranslations();
  const tStatus = useTranslations('status');
  const [runs, setRuns] = useState<WorkflowRun[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(true);
  const [pinnedVersion, setPinnedVersion] = useState<number | null>(null);
  const [pinnedRun, setPinnedRun] = useState<WorkflowRun | null>(null);
  const observerTarget = useRef<HTMLDivElement>(null);
  const offsetRef = useRef(0);

  // Pinned version + pinned (production) run - drives the pin badge and the
  // "production run first" ordering.
  useEffect(() => {
    if (!workflowId) return;
    orchestratorApi.listVersions(workflowId)
      .then((data) => setPinnedVersion(data.pinnedVersion ?? null))
      .catch(() => {});
    orchestratorApi.getPinnedWorkflowRun(workflowId)
      .then((run) => setPinnedRun(run))
      .catch(() => setPinnedRun(null));
  }, [workflowId]);

  // Listen for pin/unpin changes
  useEffect(() => {
    const handler = (e: Event) => {
      const newPinned = (e as CustomEvent).detail?.pinnedVersion ?? null;
      setPinnedVersion(newPinned);
      if (workflowId && newPinned != null) {
        orchestratorApi.getPinnedWorkflowRun(workflowId)
          .then((run) => setPinnedRun(run))
          .catch(() => setPinnedRun(null));
      } else {
        setPinnedRun(null);
      }
    };
    window.addEventListener('workflowPinnedVersionChange', handler);
    return () => window.removeEventListener('workflowPinnedVersionChange', handler);
  }, [workflowId]);

  const fetchRuns = useCallback(async (reset: boolean = false) => {
    if (!workflowId) return;
    const currentOffset = reset ? 0 : offsetRef.current;
    try {
      if (reset) {
        setLoading(true);
        setRuns([]);
        offsetRef.current = 0;
        setHasMore(true);
      } else {
        setLoadingMore(true);
      }
      setError(null);
      const data = await orchestratorApi.getWorkflowRuns(workflowId, LIMIT, currentOffset);
      setRuns(prev => (reset ? (data || []) : [...prev, ...(data || [])]));
      setHasMore(!!data && data.length === LIMIT);
      offsetRef.current = currentOffset + LIMIT;
    } catch (err) {
      console.error('[RunHistoryList] Error fetching runs:', err);
      setError(t('runs.loadError'));
    } finally {
      setLoading(false);
      setLoadingMore(false);
    }
  }, [workflowId, t]);

  useEffect(() => {
    if (workflowId) fetchRuns(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the workflow changes
  }, [workflowId]);

  // Infinite scroll
  useEffect(() => {
    if (!hasMore || loadingMore) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting && hasMore && !loadingMore) fetchRuns(false);
      },
      { threshold: 0.1 },
    );
    const currentTarget = observerTarget.current;
    if (currentTarget) observer.observe(currentTarget);
    return () => { if (currentTarget) observer.unobserve(currentTarget); };
  }, [hasMore, loadingMore, fetchRuns, runs.length]);

  // Production run first, then the rest in the order the API returned them.
  const displayRuns = pinnedRun ? [pinnedRun, ...runs.filter(r => r.id !== pinnedRun.id)] : runs;

  return (
    // `data-runs-history-panel` is kept from the former floating panel: it is the
    // stable hook the run-history e2e suites locate this list by.
    <div className="flex-1 min-h-0 flex flex-col" data-run-history-list data-runs-history-panel>
      {/* Header */}
      <div className="flex items-center justify-between px-3 py-2 border-b border-theme flex-shrink-0">
        <div className="flex items-center gap-2">
          <History className="w-3.5 h-3.5 text-theme-secondary" />
          <span className="text-sm font-medium text-theme-primary">{t('runs.title')}</span>
        </div>
        <Button
          variant="ghost"
          size="icon"
          onClick={() => fetchRuns(true)}
          disabled={loading}
          className="w-7 h-7"
          title={t('actions.refresh')}
        >
          <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} />
        </Button>
      </div>

      {/* Rows */}
      <div className="flex-1 min-h-0 overflow-y-auto py-1">
        {error ? (
          <div className="flex flex-col items-center justify-center py-12 text-center text-theme-secondary">
            <XCircle className="w-10 h-10 mb-3 opacity-50" />
            <p className="text-sm mb-3">{error}</p>
            <Button variant="outline" size="sm" onClick={() => fetchRuns(true)}>
              {t('errors.retry')}
            </Button>
          </div>
        ) : displayRuns.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-12 text-center text-theme-secondary">
            <History className="w-10 h-10 mb-3 opacity-50" />
            <p className="text-sm">{t('runs.noRuns')}</p>
            <p className="text-xs mt-1.5 opacity-70">{t('runs.runWorkflow')}</p>
          </div>
        ) : (
          displayRuns.map((run) => {
            const isCurrentRun = !!currentRunId && (currentRunId === run.id || currentRunId === run.runId);
            const displayStatus = getRunDisplayStatus(run.status, run.metadata);
            const status = mapRunStatus(displayStatus);
            const isRunning = status === 'running' || status === 'waiting_trigger';
            const duration = formatRunDuration(run);
            // Reusable triggers keep one run across many fires: `lastFireAt` is the
            // most recent epoch's start (what the user reads as "last execution"),
            // `startedAt` the run's birth. Show the former, explain both on hover.
            const display = run.lastFireAt ?? run.startedAt;
            const locale = getClientLocale();
            const statusVisual = RUN_STATUS_ICON[status] ?? RUN_STATUS_ICON.pending;
            const tooltipParts: string[] = [tStatus(status), run.runId ?? ''];
            if (run.startedAt) tooltipParts.push(`${t('runs.runStarted')}: ${formatUtcDateTime(run.startedAt, { locale })}`);
            if (run.lastFireAt && run.lastFireAt !== run.startedAt) {
              tooltipParts.push(`${t('runs.lastFire')}: ${formatUtcDateTime(run.lastFireAt, { locale })}`);
            }
            // The duration column shows the whole run for a finished one and the
            // last execution for a live one - say which, or the number is a riddle.
            if (duration.text) {
              tooltipParts.push(duration.isLastEpoch
                ? `${t('runs.lastFireDuration')}: ${duration.text}`
                : `${t('runs.duration')}: ${duration.text}`);
            }

            return (
              <button
                key={run.id}
                type="button"
                data-run-history-row
                data-run-item
                data-selected={isCurrentRun || undefined}
                title={tooltipParts.filter(Boolean).join(' • ')}
                onClick={() => onSelectRun(run)}
                className={`w-full flex items-center gap-1.5 px-3 py-2.5 text-xs transition-colors ${
                  isCurrentRun
                    ? 'border-l-2 border-gray-900 dark:border-gray-100 bg-gray-50 dark:bg-white/[0.04] font-semibold text-gray-900 dark:text-gray-100'
                    : 'border-l-2 border-transparent hover:bg-gray-50/80 dark:hover:bg-white/[0.03]'
                }`}
              >
                {/* Status - w-6 leading column, same as the epoch number column */}
                <span className={`w-6 shrink-0 flex items-center justify-center ${statusVisual.className}`}>
                  {statusVisual.icon}
                </span>

                {/* Running pulse - slot always reserved so the columns never shift */}
                <span className="relative flex h-1.5 w-1.5 shrink-0">
                  {isRunning && (
                    <>
                      <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-blue-400 opacity-75" />
                      <span className="relative inline-flex rounded-full h-1.5 w-1.5 bg-blue-500" />
                    </>
                  )}
                </span>

                {/* Status label + version + epoch + mode markers.
                    Type scale follows the project convention: `text-sm` for the
                    row identity (the content you read), `text-xs` for the
                    trailing timestamp/duration (meta). Icons are sized to their
                    text (`h-3.5` next to `text-sm`, `h-3` next to `text-xs`). */}
                <span className="flex-1 min-w-0 inline-flex items-center gap-1.5 overflow-hidden">
                  <span className={`text-sm font-medium truncate ${statusVisual.className}`}>
                    {tStatus(status)}
                  </span>
                  {run.planVersion != null && (
                    <span className="inline-flex items-center gap-0.5 text-sm font-medium text-gray-600 dark:text-gray-300 tabular-nums shrink-0">
                      {t('runs.version', { version: run.planVersion })}
                      {pinnedVersion != null && run.planVersion === pinnedVersion && (
                        <Pin className="w-3.5 h-3.5 text-amber-500 dark:text-amber-400" />
                      )}
                    </span>
                  )}
                  {run.currentEpoch != null && run.currentEpoch > 0 && (
                    <span className="inline-flex items-center gap-0.5 text-sm font-medium text-gray-500 dark:text-gray-400 tabular-nums shrink-0">
                      <Calendar className="w-3.5 h-3.5" />
                      {run.currentEpoch}
                    </span>
                  )}
                  {run.executionMode === 'step_by_step' && (
                    <StepForward className="w-3.5 h-3.5 text-purple-600 dark:text-purple-300 shrink-0" aria-label={t('runs.stepByStepMode')} />
                  )}
                  {/* Mock-run marker: only the all_mcp dry-run override gets the flask
                      ('off' means the run explicitly IGNORED all mocks) */}
                  {run.metadata?.__mockMode__ === 'all_mcp' && (
                    <FlaskConical
                      className="w-3.5 h-3.5 text-indigo-600 dark:text-indigo-300 shrink-0"
                      data-testid="run-history-mock-icon"
                      aria-label={t('workflowBuilder.canvas.mockRun')}
                    />
                  )}
                </span>

                {/* Relative last-fire time */}
                <span className="text-xs text-gray-500 dark:text-gray-400 whitespace-nowrap shrink-0">
                  {display ? formatRelativeDateI18n(display, (key, params) => t(`runs.${key}`, params)) : ''}
                </span>

                {/* Duration - fixed column so the ticking value never reflows the row */}
                <span className="w-14 text-right text-xs tabular-nums font-medium text-gray-500 dark:text-gray-400 shrink-0">
                  {duration.text}
                </span>
              </button>
            );
          })
        )}

        {loadingMore && (
          <div className="flex items-center justify-center py-3">
            <RefreshCw className="w-3.5 h-3.5 animate-spin text-theme-secondary" />
          </div>
        )}

        {hasMore && !loadingMore && displayRuns.length > 0 && (
          <div ref={observerTarget} className="h-6 flex items-center justify-center">
            <span className="text-[11px] text-theme-secondary opacity-50">{t('runs.scrollToLoadMore')}</span>
          </div>
        )}

        {!hasMore && displayRuns.length > 0 && (
          <div className="text-center py-2 text-[11px] text-theme-secondary opacity-50">
            {t('runs.noMoreRuns')}
          </div>
        )}
      </div>
    </div>
  );
}
