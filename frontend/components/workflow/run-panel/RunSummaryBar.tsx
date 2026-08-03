'use client';

import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { useTranslations, useLocale } from 'next-intl';
import { Play, Square, StepForward, Calendar, ChevronLeft, ChevronRight, History, Pin, XCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { canvasChromeChipRadiusClass } from '@/components/ui/canvas-chrome';
import { formatRelativeDateI18n } from '@/lib/utils/dateFormatters';
import { getRunDisplayStatus, getStatusClasses, getRunStatusLabel } from '@/lib/utils/runStatusUtils';
import { useHorizontalScrollHint } from '@/hooks/useHorizontalScrollHint';
import { TERMINAL_RUN_STATUSES } from './runFormatting';

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
  /**
   * How many epochs this run HAS - the length of the list the epoch selector
   * shows, not the engine's epoch cursor.
   *
   * The two differ by one for most of a run's life, and the cursor is the wrong
   * one to print: once an epoch finishes, the engine immediately PREPARES the
   * next cycle (`prepareNextCycle`), which moves the cursor to N+1 while that
   * epoch is dormant and has no row of its own. A run fired once therefore
   * reports cursor 2, and the bar used to say "All epochs (2)" next to a selector
   * listing one.
   */
  epochCount?: number;
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
  /** Rendered at the far left, before the chips (e.g. a back arrow). */
  leading?: ReactNode;
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
 * The compact run identity bar: status · version (+pin) · epoch · started-at ·
 * step-by-step, with stop/cancel/reactivate pinned at the FAR RIGHT.
 *
 * One component, two surfaces: the floating pill on the canvas and the header of
 * the side-panel Run tab. Keeping them on the same component is what guarantees
 * "same info, same look" between the two.
 *
 * Responsiveness is a horizontal scroll, not a set of breakpoints: the chips
 * live in a scrollable track that fades and grows an arrow on whichever side
 * still hides content. Hiding chips below `sm`/`md` (what this did before) made
 * the version - the ONLY route into the run history - vanish on a narrow canvas
 * or in a side panel, with nothing telling the user it was there.
 */
export function RunSummaryBar({
  currentRunInfo,
  pinnedVersion,
  epochCount = 0,
  selectedEpoch = null,
  isStepByStep = false,
  onStop,
  onCancel,
  onReactivate,
  onVersionClick,
  leading,
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
  const arrowBtnCls = isPanel ? 'w-6 h-6' : 'w-5 h-5';
  const arrowIconCls = isPanel ? 'w-3.5 h-3.5' : 'w-3 h-3';

  const { scrollRef, hint, onScroll, nudge } = useHorizontalScrollHint<HTMLDivElement>();

  // Fade the edge the arrow sits on, so the chevron reads over the chips instead
  // of covering them opaquely. A mask (not a gradient overlay) because this bar
  // renders on two different backgrounds - the white canvas pill and the side
  // panel - and a coloured gradient would only match one of them.
  const fadeMask = (hint.left || hint.right)
    ? `linear-gradient(to right, ${hint.left ? 'transparent 0, black 24px' : 'black 0'}, ${hint.right ? 'black calc(100% - 24px), transparent 100%' : 'black 100%'})`
    : undefined;

  /** Vertical wheel over the strip scrolls it sideways - a trackpad-less user
   *  otherwise cannot move a strip whose scrollbar is hidden. */
  const handleWheel = useCallback((e: React.WheelEvent<HTMLDivElement>) => {
    const el = e.currentTarget;
    if (el.scrollWidth <= el.clientWidth) return;
    const delta = Math.abs(e.deltaX) > Math.abs(e.deltaY) ? e.deltaX : e.deltaY;
    if (!delta) return;
    el.scrollLeft += delta;
    e.stopPropagation();
  }, []);

  return (
    <>
      <div className={`flex items-center gap-2 px-3 sm:px-4 py-2 flex-shrink-0 min-w-0 overflow-hidden ${className}`}>
        {leading}

        {/* Chip track - scrolls sideways instead of dropping chips at breakpoints.
            `flex-auto` (basis auto), not `flex-1`: the canvas pill is width:fit-content,
            and a 0 flex-basis makes it under-report its max-content size there, so the
            pill would render narrower than the chips it holds even with room to spare. */}
        <div className="relative flex-auto min-w-0 flex items-center">
          <div
            ref={scrollRef}
            onScroll={onScroll}
            onWheel={handleWheel}
            data-run-summary-chips
            className="flex items-center gap-2 min-w-0 overflow-x-auto scrollbar-hide"
            style={fadeMask ? { maskImage: fadeMask, WebkitMaskImage: fadeMask } : undefined}
          >
            {/* Status badge. The testid is the stable handle for it: the shape is a
                styling detail (it used to be the only `div.rounded-full` on the
                canvas, which is what e2e keyed on), the identity is not. */}
            <div
              data-testid="run-status-badge"
              className={`flex items-center gap-1 px-2 py-0.5 ${canvasChromeChipRadiusClass} ${textCls} font-medium whitespace-nowrap flex-shrink-0 ${getStatusClasses(displayStatus)}`}
            >
              {getRunStatusLabel(displayStatus, (k) => t(k))}
            </div>

            {/* No version to show, but the history still has to be reachable: the chip
                IS the canvas entry point, so a run whose version is unknown (older row,
                info not loaded yet) must not strand the user with no way in. Same slot,
                same click target, a History glyph instead of "vN". */}
            {currentRunInfo.planVersion == null && onVersionClick && (
              <span className="flex items-center gap-0.5 flex-shrink-0">
                <span className={`${textCls} text-gray-400 dark:text-gray-500`}>·</span>
                <button
                  type="button"
                  data-run-version-chip
                  onClick={(e) => { e.stopPropagation(); onVersionClick(); }}
                  title={t('runs.title')}
                  className={`flex items-center gap-0.5 ${textCls} font-medium text-gray-600 dark:text-gray-300 whitespace-nowrap ${canvasChromeChipRadiusClass} px-1 -mx-1 hover:bg-gray-100 dark:hover:bg-gray-700/60 transition-colors`}
                >
                  <History className={iconCls} />
                </button>
              </span>
            )}

            {/* Version badge + pinned indicator. Clicking it jumps to the run history
                (every run of this workflow) in the side panel. */}
            {currentRunInfo.planVersion != null && (
              <span className="flex items-center gap-0.5 flex-shrink-0">
                <span className={`${textCls} text-gray-400 dark:text-gray-500`}>·</span>
                {onVersionClick ? (
                  <button
                    type="button"
                    data-run-version-chip
                    onClick={(e) => { e.stopPropagation(); onVersionClick(); }}
                    title={t('runs.title')}
                    className={`flex items-center gap-0.5 ${textCls} font-medium text-gray-600 dark:text-gray-300 whitespace-nowrap ${canvasChromeChipRadiusClass} px-1 -mx-1 hover:bg-gray-100 dark:hover:bg-gray-700/60 transition-colors`}
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

            {/* Epoch indicator - right after version.
                A bare number can only mean "epoch N": showing the epoch COUNT
                the same way made the default view read as "you are on epoch 3"
                while the panel next to it said "All epochs". The cumulative view
                says so in words, and keeps the count that tells you how many
                times the run fired. */}
            {epochCount > 0 && (
              <span className="flex items-center gap-0.5 flex-shrink-0">
                <span className={`${textCls} text-gray-400 dark:text-gray-500`}>·</span>
                <span
                  data-run-epoch-chip
                  data-all-epochs={selectedEpoch == null || undefined}
                  className={`flex items-center gap-1 ${textCls} font-medium text-gray-600 dark:text-gray-300 ${selectedEpoch != null ? 'tabular-nums' : ''} whitespace-nowrap`}
                >
                  <Calendar className={iconCls} />
                  {selectedEpoch != null
                    ? selectedEpoch
                    : t('workflow.runSteps.allEpochsCount', { count: epochCount })}
                </span>
              </span>
            )}

            {/* Started at */}
            {currentRunInfo.startedAt && (
              <span className="flex items-center gap-0.5 flex-shrink-0">
                <span className={`${textCls} text-gray-400 dark:text-gray-500`}>·</span>
                <span className={`${textCls} text-gray-500 dark:text-gray-400 whitespace-nowrap`}>
                  {formatRel(currentRunInfo.startedAt)}
                </span>
              </span>
            )}

            {/* Step by step badge */}
            {isStepByStep && (
              <span className="flex items-center gap-2 flex-shrink-0">
                <span className={`${textCls} text-gray-400 dark:text-gray-500`}>·</span>
                <span className={`flex items-center gap-1.5 px-2 py-0.5 bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-300 ${canvasChromeChipRadiusClass}`}>
                  <StepForward className={iconCls} />
                  <span className={`${textCls} font-medium whitespace-nowrap`}>{t('workflow.mode.stepByStep')}</span>
                </span>
              </span>
            )}
          </div>

          {/* Scroll arrows - only on the side that still hides a chip */}
          {hint.left && (
            <button
              type="button"
              data-run-summary-scroll="left"
              aria-label={t('workflow.runInfo.scrollLeft')}
              title={t('workflow.runInfo.scrollLeft')}
              onClick={(e) => { e.stopPropagation(); nudge(-1); }}
              className={`absolute left-0 top-1/2 -translate-y-1/2 flex items-center justify-center ${arrowBtnCls} ${canvasChromeChipRadiusClass} text-gray-500 dark:text-gray-400 hover:text-gray-900 dark:hover:text-gray-100 transition-colors`}
            >
              <ChevronLeft className={arrowIconCls} />
            </button>
          )}
          {hint.right && (
            <button
              type="button"
              data-run-summary-scroll="right"
              aria-label={t('workflow.runInfo.scrollRight')}
              title={t('workflow.runInfo.scrollRight')}
              onClick={(e) => { e.stopPropagation(); nudge(1); }}
              className={`absolute right-0 top-1/2 -translate-y-1/2 flex items-center justify-center ${arrowBtnCls} ${canvasChromeChipRadiusClass} text-gray-500 dark:text-gray-400 hover:text-gray-900 dark:hover:text-gray-100 transition-colors`}
            >
              <ChevronRight className={arrowIconCls} />
            </button>
          )}
        </div>

        {/* Stop / Cancel / Reactivate button - FAR RIGHT, never scrolled away:
            the one destructive control of the bar must sit in a fixed place, and
            it must stay reachable when the chips overflow. */}
        {(() => {
          const isStoppable = rawStatus === 'RUNNING' || rawStatus === 'PAUSED';
          const isCancellable = rawStatus === 'WAITING_TRIGGER';
          // Every terminal status is reactivatable: the dispatcher rejects firing
          // into a terminal run, so the user must explicitly re-arm it.
          const isReactivatable = !!rawStatus && TERMINAL_RUN_STATUSES.has(rawStatus) && !!onReactivate;
          if (!(isStoppable && onStop) && !(isCancellable && onCancel) && !isReactivatable) return null;

          if (isReactivatable) {
            return (
              <span className="flex items-center gap-2 flex-shrink-0">
                <span className={`${textCls} text-gray-400 dark:text-gray-500`}>·</span>
                <button
                  type="button"
                  data-run-action="reactivate"
                  onClick={(e) => { e.stopPropagation(); onReactivate?.(); }}
                  className={`flex items-center justify-center ${actionBtnCls} ${canvasChromeChipRadiusClass} bg-green-100 dark:bg-green-900/30 text-green-600 dark:text-green-400 hover:bg-green-200 dark:hover:bg-green-900/50 transition-colors`}
                  title={t('workflow.reactivateRun.title')}
                >
                  <Play className={actionIconCls} />
                </button>
              </span>
            );
          }

          return (
            <span className="flex items-center gap-2 flex-shrink-0">
              <span className={`${textCls} text-gray-400 dark:text-gray-500`}>·</span>
              <button
                type="button"
                data-run-action={isCancellable ? 'cancel' : 'stop'}
                onClick={(e) => {
                  e.stopPropagation();
                  if (isCancellable) setCancelConfirm(true);
                  else onStop?.();
                }}
                className={`flex items-center justify-center ${actionBtnCls} ${canvasChromeChipRadiusClass} bg-red-100 dark:bg-red-900/30 text-red-600 dark:text-red-400 hover:bg-red-200 dark:hover:bg-red-900/50 transition-colors`}
                title={isCancellable ? t('workflow.cancelRun.title') : t('workflow.mode.stopWorkflow')}
              >
                <Square className={actionIconCls} />
              </button>
            </span>
          );
        })()}
      </div>

      {/* Cancel confirmation modal (WAITING_TRIGGER → terminal CANCELLED).
          stopPropagation on the backdrop even though this is a portal: React
          bubbles synthetic events through the REACT tree, not the DOM one, so a
          dismissing click here would still reach the canvas bar wrapping this
          component and open the run panel behind the modal. */}
      {mounted && cancelConfirm && createPortal(
        <div
          data-run-cancel-backdrop
          className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
          onClick={(e) => { e.stopPropagation(); setCancelConfirm(false); }}
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
