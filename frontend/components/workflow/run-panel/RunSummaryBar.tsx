'use client';

import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { useTranslations, useLocale } from 'next-intl';
import { Play, Square, StepForward, Calendar, History, Pin, XCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { formatRelativeDateI18n } from '@/lib/utils/dateFormatters';
import { getRunDisplayStatus, getStatusClasses, getRunStatusLabel } from '@/lib/utils/runStatusUtils';
import { TERMINAL_RUN_STATUSES, type EpochTimestamp } from './runFormatting';

export interface RunSummaryRunInfo {
  runId?: string;
  id?: string;
  status?: string;
  metadata?: Record<string, unknown> | null;
  planVersion?: number | null;
  startedAt?: string | null;
}

export interface RunSummaryBarProps {
  currentRunInfo: RunSummaryRunInfo;
  /** Pinned (production) version of the workflow, null if unpinned. */
  pinnedVersion?: number | null;
  /** Current epoch number reported by the run (0 = never fired). */
  currentEpoch?: number;
  epochTimestamps?: EpochTimestamp[];
  /** Epoch currently being viewed - shown in the epoch chip when set. */
  selectedEpoch?: number | null;
  isStepByStep?: boolean;
  /** Graceful stop (RUNNING/PAUSED → WAITING_TRIGGER). Omit to hide. */
  onStop?: () => void;
  /** Hard cancel (WAITING_TRIGGER → CANCELLED), asks for confirmation. Omit to hide. */
  onCancel?: () => void;
  /** Reactivate a terminal run (→ WAITING_TRIGGER). Omit to hide. */
  onReactivate?: () => void;
  /**
   * Click handler on the version chip. Wired to "open the run history" so the
   * bar is a shortcut into the list of every run of this workflow.
   */
  onVersionClick?: () => void;
  /** Rendered at the far right of the bar (panel toggle / back button / chevron). */
  trailing?: ReactNode;
  /** Rendered at the far left, before the stop button (e.g. a back arrow). */
  leading?: ReactNode;
  /**
   * Canvas bar only: hide the secondary chips below the `sm`/`md` breakpoints so
   * the pill never overflows a narrow canvas. The side panel always shows them.
   */
  responsiveChips?: boolean;
  /** Show the step-by-step label next to its icon (hidden on a narrow canvas). */
  showStepByStepLabel?: boolean;
  /**
   * Type scale. `compact` is the canvas pill, which must stay exactly as it was
   * (it shares the top of the canvas with the nodes). `panel` matches the run
   * history / step rows it sits above in the side panel, where the same `text-xs`
   * read as noticeably smaller than the list underneath it.
   */
  size?: 'compact' | 'panel';
  className?: string;
}

/**
 * The compact run identity bar: stop/cancel/reactivate · status · version (+pin)
 * · epoch · started-at · step-by-step.
 *
 * One component, two surfaces: the floating pill on the canvas and the header of
 * the side-panel Run tab. Keeping them on the same component is what guarantees
 * "same info, same look" between the two.
 */
export function RunSummaryBar({
  currentRunInfo,
  pinnedVersion,
  currentEpoch = 0,
  epochTimestamps = [],
  selectedEpoch = null,
  isStepByStep = false,
  onStop,
  onCancel,
  onReactivate,
  onVersionClick,
  trailing,
  leading,
  responsiveChips = false,
  showStepByStepLabel = true,
  size = 'compact',
  className = '',
}: RunSummaryBarProps) {
  const t = useTranslations();
  const locale = useLocale();
  const formatRel = useCallback(
    (d: string | Date | null | undefined) =>
      formatRelativeDateI18n(d, (k, p) => t(`runs.${k}`, p), locale),
    [t, locale],
  );

  const [cancelConfirm, setCancelConfirm] = useState(false);
  const [mounted, setMounted] = useState(false);
  useEffect(() => { setMounted(true); return () => setMounted(false); }, []);

  const rawStatus = currentRunInfo.status?.toUpperCase();
  const displayStatus = getRunDisplayStatus(currentRunInfo.status, currentRunInfo.metadata as any);
  // One switch for the whole bar so the chips, their icons and the separators
  // scale together instead of drifting apart.
  const isPanel = size === 'panel';
  const textCls = isPanel ? 'text-sm' : 'text-xs';
  const iconCls = isPanel ? 'w-3.5 h-3.5' : 'w-3 h-3';
  const actionBtnCls = isPanel ? 'w-6 h-6' : 'w-5 h-5';
  const actionIconCls = isPanel ? 'w-3 h-3' : 'w-2.5 h-2.5';
  // Chips that would overflow a narrow canvas pill are hidden below sm/md there;
  // in the side panel (responsiveChips=false) they are always visible.
  const chipClass = responsiveChips ? 'hidden sm:flex' : 'flex';
  const startedChipClass = responsiveChips ? 'hidden md:flex' : 'flex';

  return (
    <>
      <div className={`flex items-center gap-2 px-3 sm:px-4 py-2 flex-shrink-0 overflow-hidden ${className}`}>
        {leading}

        {/* Stop / Cancel / Reactivate button - leftmost */}
        {(() => {
          const isStoppable = rawStatus === 'RUNNING' || rawStatus === 'PAUSED';
          const isCancellable = rawStatus === 'WAITING_TRIGGER';
          // Every terminal status is reactivatable: the dispatcher rejects firing
          // into a terminal run, so the user must explicitly re-arm it.
          const isReactivatable = !!rawStatus && TERMINAL_RUN_STATUSES.has(rawStatus) && !!onReactivate;
          if (!(isStoppable && onStop) && !(isCancellable && onCancel) && !isReactivatable) return null;

          if (isReactivatable) {
            return (
              <>
                <button
                  onClick={(e) => { e.stopPropagation(); onReactivate?.(); }}
                  className={`flex items-center justify-center ${actionBtnCls} rounded-full bg-green-100 dark:bg-green-900/30 text-green-600 dark:text-green-400 hover:bg-green-200 dark:hover:bg-green-900/50 transition-colors`}
                  title={t('workflow.reactivateRun.title')}
                >
                  <Play className={actionIconCls} />
                </button>
                <span className={`${textCls} text-gray-400 dark:text-gray-500`}>·</span>
              </>
            );
          }

          return (
            <>
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  if (isCancellable) setCancelConfirm(true);
                  else onStop?.();
                }}
                className={`flex items-center justify-center ${actionBtnCls} rounded-full bg-red-100 dark:bg-red-900/30 text-red-600 dark:text-red-400 hover:bg-red-200 dark:hover:bg-red-900/50 transition-colors`}
                title={isCancellable ? t('workflow.cancelRun.title') : t('workflow.mode.stopWorkflow')}
              >
                <Square className={actionIconCls} />
              </button>
              <span className={`${textCls} text-gray-400 dark:text-gray-500`}>·</span>
            </>
          );
        })()}

        {/* Status badge */}
        <div className={`flex items-center gap-1 px-2 py-0.5 rounded-full ${textCls} font-medium ${getStatusClasses(displayStatus)}`}>
          {getRunStatusLabel(displayStatus, (k) => t(k))}
        </div>

        {/* No version to show, but the history still has to be reachable: the chip
            IS the canvas entry point, so a run whose version is unknown (older row,
            info not loaded yet) must not strand the user with no way in. Same slot,
            same click target, a History glyph instead of "vN". */}
        {currentRunInfo.planVersion == null && onVersionClick && (
          <span className={`items-center gap-0.5 ${chipClass}`}>
            <span className={`${textCls} text-gray-400 dark:text-gray-500`}>·</span>
            <button
              type="button"
              data-run-version-chip
              onClick={(e) => { e.stopPropagation(); onVersionClick(); }}
              title={t('runs.title')}
              className={`flex items-center gap-0.5 ${textCls} font-medium text-gray-600 dark:text-gray-300 whitespace-nowrap rounded px-1 -mx-1 hover:bg-gray-100 dark:hover:bg-gray-700/60 transition-colors`}
            >
              <History className={iconCls} />
            </button>
          </span>
        )}

        {/* Version badge + pinned indicator. Clicking it jumps to the run history
            (every run of this workflow) in the side panel. */}
        {currentRunInfo.planVersion != null && (
          <span className={`items-center gap-0.5 ${chipClass}`}>
            <span className={`${textCls} text-gray-400 dark:text-gray-500`}>·</span>
            {onVersionClick ? (
              <button
                type="button"
                data-run-version-chip
                onClick={(e) => { e.stopPropagation(); onVersionClick(); }}
                title={t('runs.title')}
                className={`flex items-center gap-0.5 ${textCls} font-medium text-gray-600 dark:text-gray-300 whitespace-nowrap rounded px-1 -mx-1 hover:bg-gray-100 dark:hover:bg-gray-700/60 transition-colors`}
              >
                v{currentRunInfo.planVersion}
                {pinnedVersion != null && currentRunInfo.planVersion === pinnedVersion && (
                  <Pin className={`${iconCls} text-amber-500 dark:text-amber-400`} />
                )}
              </button>
            ) : (
              <span className={`flex items-center gap-0.5 ${textCls} font-medium text-gray-600 dark:text-gray-300 whitespace-nowrap`}>
                v{currentRunInfo.planVersion}
                {pinnedVersion != null && currentRunInfo.planVersion === pinnedVersion && (
                  <Pin className={`${iconCls} text-amber-500 dark:text-amber-400`} />
                )}
              </span>
            )}
          </span>
        )}

        {/* Epoch indicator - right after version */}
        {currentEpoch > 0 && (
          <span className={`items-center gap-0.5 ${chipClass}`}>
            <span className={`${textCls} text-gray-400 dark:text-gray-500`}>·</span>
            <span className={`flex items-center gap-1 ${textCls} font-medium text-gray-600 dark:text-gray-300 tabular-nums whitespace-nowrap`}>
              <Calendar className={iconCls} />
              {selectedEpoch != null ? selectedEpoch : (epochTimestamps.length > 0 ? epochTimestamps.length : currentEpoch)}
            </span>
          </span>
        )}

        {/* Started at */}
        {currentRunInfo.startedAt && (
          <span className={`items-center gap-0.5 ${startedChipClass}`}>
            <span className={`${textCls} text-gray-400 dark:text-gray-500`}>·</span>
            <span className={`${textCls} text-gray-500 dark:text-gray-400 whitespace-nowrap`}>
              {formatRel(currentRunInfo.startedAt)}
            </span>
          </span>
        )}

        {/* Step by step badge - icon only when there is no room for the label */}
        {isStepByStep && (
          <>
            <span className={`${textCls} text-gray-400 dark:text-gray-500`}>·</span>
            <div className="flex items-center gap-1.5 px-2 py-0.5 bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-300 rounded-full">
              <StepForward className={iconCls} />
              {showStepByStepLabel && <span className={`${textCls} font-medium whitespace-nowrap`}>{t('workflow.mode.stepByStep')}</span>}
            </div>
          </>
        )}

        {trailing && <div className="ml-auto flex items-center gap-1 flex-shrink-0">{trailing}</div>}
      </div>

      {/* Cancel confirmation modal (WAITING_TRIGGER → terminal CANCELLED) */}
      {mounted && cancelConfirm && createPortal(
        <div
          className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
          onClick={() => setCancelConfirm(false)}
        >
          <div
            role="alertdialog"
            aria-modal="true"
            aria-labelledby="cancel-run-dialog-title"
            aria-describedby="cancel-run-dialog-description"
            className="max-w-sm w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] overflow-y-auto"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="text-center mb-6">
              <div className="w-14 h-14 bg-red-100 dark:bg-red-900/30 rounded-full flex items-center justify-center mx-auto mb-4">
                <XCircle className="w-7 h-7 text-red-600 dark:text-red-400" />
              </div>
              <h3 id="cancel-run-dialog-title" className="text-lg font-semibold text-theme-primary">
                {t('workflow.cancelRun.title')}
              </h3>
              <p id="cancel-run-dialog-description" className="text-sm text-theme-secondary mt-2">
                {t('workflow.cancelRun.description')}
              </p>
              {pinnedVersion != null && (
                <div className="mt-3 p-3 bg-red-50 dark:bg-red-900/20 rounded-xl border border-red-200 dark:border-red-800">
                  <p className="text-xs text-red-600 dark:text-red-400">
                    {t('workflow.cancelRun.warning')}
                  </p>
                </div>
              )}
            </div>
            <div className="flex gap-3">
              <Button variant="outline" onClick={() => setCancelConfirm(false)} className="flex-1">
                {t('workflow.cancelRun.keep')}
              </Button>
              <Button
                onClick={() => { setCancelConfirm(false); onCancel?.(); }}
                className="flex-1 bg-red-600 hover:bg-red-700 text-white"
              >
                {t('workflow.cancelRun.confirm')}
              </Button>
            </div>
          </div>
        </div>,
        document.body
      )}
    </>
  );
}
