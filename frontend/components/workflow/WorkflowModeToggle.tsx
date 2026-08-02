'use client';

import { useRef, useEffect, useState, useCallback } from 'react';
import { Edit3, History, Play, Eye, PanelRight } from 'lucide-react';
import { formatCost } from '@/lib/format-cost';
import { useTranslations } from 'next-intl';
import { useRouter, usePathname } from 'next/navigation';
import { orchestratorApi, type WorkflowRun } from '@/lib/api';
import { useToast } from '@/components/Toast';
import ToastContainer from '@/components/ToastContainer';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { VIEWING_EPOCH_EVENT, shouldAdoptEpochEvent, type EpochEventDetail } from '@/lib/workflow/epochEventScope';
import { isEmbeddedWorkflowCanvas } from '@/lib/workflow/canvasEmbedding';
import { computeRunInfoPanelWidths } from '@/components/workflow/runInfoPanelWidth';
import { RunSummaryBar } from '@/components/workflow/run-panel/RunSummaryBar';
import { openRunPanel } from '@/components/workflow/run-panel/runPanelBus';
import type { EpochTimestamp } from '@/components/workflow/run-panel/runFormatting';

interface WorkflowModeToggleProps {
  mode: 'edit' | 'run';
  onModeChange?: (mode: 'edit' | 'run') => void;
  workflowId?: string;
  /** Hide the edit/run mode toggle (for application mode, always in run) */
  hideToggle?: boolean;
  /** Show a "Read only" badge instead of the toggle */
  showReadOnlyBadge?: boolean;
  // Run info props
  currentRunInfo?: (WorkflowRun & {
    completedCount?: number;
    failedCount?: number;
    runningCount?: number;
    skippedCount?: number;
    executionTotal?: number;
    /** Per-epoch cost breakdown, epoch number (as string) -> credits. */
    costByEpoch?: Record<string, number>;
  }) | null;
  isStepByStep?: boolean;
  onStop?: () => void;
  onCancel?: () => void;
  onReactivate?: () => void;
  /** Current epoch number (0 = no fire, 1+ = epoch count) */
  currentEpoch?: number;
  /** Epoch timestamps - drives the epoch chip and the panel's epoch selector */
  epochTimestamps?: EpochTimestamp[];
  /** Pinned (production) version of the workflow, null if unpinned */
  pinnedVersion?: number | null;
  /** When the settings panel is open, hide the run bar & history button */
  isSettingsOpen?: boolean;
}

/**
 * Canvas chrome for a workflow: the edit/run toggle (centered) and the compact
 * run bar + history button (top right).
 *
 * The run bar is IDENTITY ONLY - stop/cancel · status · version · epoch · start
 * · step-by-step. Everything that used to expand out of it (epoch selector, step
 * list, cost) now lives in the side panel's Run tab, so nothing floats over the
 * ReactFlow canvas any more. The bar IS the entry point - there is no separate
 * history button next to it: the version chip opens the run HISTORY, the panel
 * button opens the CURRENT run.
 */
export function WorkflowModeToggle({
  mode,
  onModeChange,
  workflowId,
  hideToggle = false,
  showReadOnlyBadge = false,
  currentRunInfo,
  isStepByStep = false,
  onStop,
  onCancel,
  onReactivate,
  currentEpoch = 0,
  epochTimestamps = [],
  pinnedVersion,
  isSettingsOpen = false,
}: WorkflowModeToggleProps) {
  const t = useTranslations();
  const router = useRouter();
  const pathname = usePathname();
  const { toasts, addToast, removeToast } = useToast();

  // Surface an explanatory toast when the backend refuses to open a new epoch
  // because the workflow budget was reached (the in-flight epoch still finishes).
  // WorkflowRunManager (non-React) dispatches this window CustomEvent on the
  // runBudgetBlocked WS event; we only react to the run this bar is showing.
  useEffect(() => {
    const handler = (e: Event) => {
      const detail = (e as CustomEvent).detail as { runId?: string; spentCredits?: number; budgetCredits?: number } | undefined;
      if (!detail) return;
      const panelRunId = currentRunInfo?.runId;
      if (panelRunId && detail.runId && detail.runId !== panelRunId) return;
      addToast({
        type: 'warning',
        title: t('workflow.runInfo.budgetBlockedTitle'),
        message: t('workflow.runInfo.budgetBlockedMessage', {
          spent: formatCost(detail.spentCredits ?? null),
          budget: formatCost(detail.budgetCredits ?? null),
        }),
      });
    };
    window.addEventListener('workflow:runBudgetBlocked', handler as EventListener);
    return () => window.removeEventListener('workflow:runBudgetBlocked', handler as EventListener);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentRunInfo?.runId]);

  // Canvas run identity - used to scope the cross-tree viewingEpochChanged event
  // so side-panel tabs bound to OTHER runs don't move this canvas's epoch chip.
  const { setViewingEpoch, setRunId, runId: canvasRunId } = useWorkflowMode();
  const [sliderStyle, setSliderStyle] = useState({ left: 0, width: 0, opacity: 0 });
  /** Epoch shown in the chip. Mirrors the panel's selection through the scoped event. */
  const [selectedEpoch, setSelectedEpoch] = useState<number | null>(null);

  useEffect(() => {
    const handler = (e: Event) => {
      const detail = (e as CustomEvent).detail as EpochEventDetail | undefined;
      // Ignore epoch changes broadcast for a DIFFERENT run (e.g. a sibling app
      // tab in the side panel) so this canvas only follows its own run's epoch.
      if (!shouldAdoptEpochEvent(detail?.runId, canvasRunId)) return;
      setSelectedEpoch(detail?.epoch ?? null);
    };
    window.addEventListener(VIEWING_EPOCH_EVENT, handler);
    return () => window.removeEventListener(VIEWING_EPOCH_EVENT, handler);
  }, [canvasRunId]);

  const editButtonRef = useRef<HTMLButtonElement>(null);
  const runButtonRef = useRef<HTMLButtonElement>(null);
  /** Invisible probe element to measure the real available container width
   *  (accounts for SidePanel, sidebar, etc. - not just window.innerWidth). */
  const probeRef = useRef<HTMLDivElement>(null);
  /** Available width of the canvas container (updated via ResizeObserver). */
  const [containerWidth, setContainerWidth] = useState(
    typeof window !== 'undefined' ? window.innerWidth : 1200,
  );

  const isRunMode = mode === 'run';
  const showRunInfo = isRunMode && !!currentRunInfo;

  useEffect(() => {
    const activeButtonRef = mode === 'edit' ? editButtonRef : runButtonRef;
    if (activeButtonRef.current) {
      const { offsetLeft, offsetWidth } = activeButtonRef.current;
      setSliderStyle({ left: offsetLeft, width: offsetWidth, opacity: 1 });
    }
  }, [mode]);

  // Measure the real available container width via ResizeObserver on the
  // invisible probe element. This correctly handles SidePanel open/close,
  // sidebar collapse, and window resize - unlike window.innerWidth.
  useEffect(() => {
    const el = probeRef.current?.parentElement;
    if (!el) return;
    const observer = new ResizeObserver((entries) => {
      for (const entry of entries) {
        setContainerWidth(entry.contentRect.width);
      }
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  // Derive isWideEnough from containerWidth - center the toggle only when
  // there is enough room so it won't collide with the run bar.
  const isWideEnough = containerWidth >= (showRunInfo ? 900 : 640);

  // Reset the epoch chip when toggling between edit/run so the view doesn't
  // carry over the previously pinned epoch.
  useEffect(() => {
    setSelectedEpoch(null);
    setViewingEpoch(null);
  }, [mode, setViewingEpoch]);

  const isEmbedded = isEmbeddedWorkflowCanvas(pathname, workflowId);

  const handleModeClick = async (newMode: 'edit' | 'run') => {
    if (!workflowId) return;

    // Allow re-clicking Run mode to load latest run (refresh)
    if (newMode === mode && newMode === 'edit') return;

    if (newMode === 'run') {
      // Show the most recent run regardless of version (debug/observation tool).
      try {
        const latestRun = await orchestratorApi.getLatestWorkflowRun(workflowId);
        if (latestRun) {
          const actualRunId = latestRun.runId || (latestRun as any).id;
          if (isEmbedded) {
            setRunId(actualRunId);
          } else {
            router.push(`/app/workflow/${workflowId}/run/${actualRunId}`);
          }
          onModeChange?.(newMode);
        } else {
          addToast({
            type: 'info',
            title: t('workflow.mode.noRunsTitle'),
            message: t('workflow.mode.noRunsMessage'),
          });
        }
      } catch (error) {
        console.error('[WorkflowModeToggle] Failed to load run:', error);
      }
    } else {
      if (isEmbedded) {
        setRunId(null);
      } else {
        // ALWAYS clear the binding, then navigate if there is a /run/ URL to
        // leave. Relying on the URL alone dead-ends: binding a run in place
        // (an agent-launched run, or picking one in the panel's history) latches
        // the provider's "programmatic" flag, which makes it ignore pathname
        // changes from then on - so the push landed on the edit URL while the
        // canvas stayed in run mode and the toggle snapped back to Run.
        setRunId(null);
        if (pathname?.includes('/run/')) {
          router.push(`/app/workflow/${workflowId}`);
        }
      }
      onModeChange?.(newMode);
    }
  };

  const openPanel = useCallback((view: 'history' | 'run') => {
    openRunPanel({ workflowId, view });
  }, [workflowId]);

  // Width bound for the run bar - it must never exceed the container (a narrow
  // side-panel canvas used to push it off-screen, out of reach).
  const { maxWidth: runInfoMaxWidth } = computeRunInfoPanelWidths(
    containerWidth,
    !hideToggle && !showReadOnlyBadge,
  );

  return (
    <>
      {/* Invisible probe - spans the full container width so ResizeObserver
          can measure the real available space (not window.innerWidth). */}
      <div ref={probeRef} className="absolute inset-x-0 top-0 h-0 pointer-events-none" aria-hidden />

      {/* Mode Toggle - Centered (hidden in application/preview mode) */}
      {!hideToggle && !showReadOnlyBadge && (
        <div className={`absolute top-4 z-[40] ${
          isWideEnough ? 'left-1/2 -translate-x-1/2' : 'left-4'
        }`}>
          <div className="relative inline-flex items-center gap-0.5 p-1 bg-white dark:bg-gray-800 rounded-full">
            {/* Animated background slider */}
            <div
              className="absolute top-1 bottom-1 rounded-full bg-theme-secondary transition-all duration-300 ease-out"
              style={{
                left: `${sliderStyle.left}px`,
                width: `${sliderStyle.width}px`,
                opacity: sliderStyle.opacity,
              }}
            />

            {/* Edit Mode Button */}
            <button
              ref={editButtonRef}
              onClick={() => handleModeClick('edit')}
              title={t('workflow.mode.edit')}
              className={`
                relative z-10 flex items-center justify-center w-7 h-7 rounded-full
                transition-all duration-200 focus-visible:outline-none focus-visible:ring-2
                focus-visible:ring-theme-tertiary outline-none
                ${mode === 'edit'
                  ? 'text-gray-900 dark:text-gray-100'
                  : 'text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-300 hover:bg-gray-100/50 dark:hover:bg-gray-700/50'
                }
              `}
            >
              <Edit3 className={`w-3.5 h-3.5 transition-colors duration-200 ${mode === 'edit' ? 'text-gray-900 dark:text-gray-100' : 'text-current'}`} />
            </button>

            {/* Run Mode Button */}
            <button
              ref={runButtonRef}
              onClick={() => handleModeClick('run')}
              title={t('workflow.mode.run')}
              className={`
                relative z-10 flex items-center justify-center w-7 h-7 rounded-full
                transition-all duration-200 focus-visible:outline-none focus-visible:ring-2
                focus-visible:ring-theme-tertiary outline-none
                ${mode === 'run'
                  ? 'text-gray-900 dark:text-gray-100'
                  : 'text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-300 hover:bg-gray-100/50 dark:hover:bg-gray-700/50'
                }
              `}
            >
              <Play className={`w-3.5 h-3.5 transition-colors duration-200 ${mode === 'run' ? 'text-gray-900 dark:text-gray-100' : 'text-current'}`} />
            </button>
          </div>
        </div>
      )}

      {/* Read-only badge (shown in preview mode instead of the toggle) */}
      {showReadOnlyBadge && (
        <div className={`absolute top-4 z-[40] ${
          isWideEnough ? 'left-1/2 -translate-x-1/2' : 'left-4'
        }`}>
          <div className="inline-flex items-center gap-1.5 px-3 py-1.5 bg-white dark:bg-gray-800 rounded-full text-sm font-medium text-gray-500 dark:text-gray-400">
            <Eye className="w-4 h-4" />
            <span>{t('workflow.mode.readOnly')}</span>
          </div>
        </div>
      )}

      {/* Run bar & history button - Right side */}
      {isRunMode && !isSettingsOpen && (
        <div className="absolute top-4 right-2 sm:right-4 z-[40] flex items-start gap-2 sm:gap-3 pointer-events-none" style={{ maxWidth: runInfoMaxWidth }}>
          {/* No run info (still loading, or its fetch failed): the bar cannot
              render, and with it went the ONLY canvas route into the run history -
              on dev the History button was a separate control that did not depend
              on it. This standalone chip keeps that route open. */}
          {!showRunInfo && (
            <button
              type="button"
              data-run-history-fallback
              onClick={() => openPanel('history')}
              title={t('runs.title')}
              className="pointer-events-auto flex items-center justify-center w-8 h-8 rounded-full bg-white dark:bg-gray-800 text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200 transition-colors"
            >
              <History className="w-3.5 h-3.5" />
            </button>
          )}
          {/* Compact run bar - identity only, the panel holds the detail */}
          {showRunInfo && (
            <div
              data-run-info-panel
              className="pointer-events-auto bg-white dark:bg-gray-800 rounded-full w-fit max-w-full min-w-0"
            >
              <RunSummaryBar
                currentRunInfo={currentRunInfo!}
                pinnedVersion={pinnedVersion}
                currentEpoch={currentEpoch}
                epochTimestamps={epochTimestamps}
                selectedEpoch={selectedEpoch}
                isStepByStep={isStepByStep}
                onStop={onStop}
                onCancel={onCancel}
                onReactivate={onReactivate}
                onVersionClick={() => openPanel('history')}
                responsiveChips
                showStepByStepLabel={isWideEnough}
                trailing={(
                  <button
                    type="button"
                    data-run-open-panel
                    onClick={(e) => { e.stopPropagation(); openPanel('run'); }}
                    title={t('workflow.runInfo.openInPanel')}
                    className="flex items-center justify-center w-5 h-5 rounded-full text-gray-400 dark:text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-700 hover:text-gray-700 dark:hover:text-gray-200 transition-colors"
                  >
                    <PanelRight className="w-3.5 h-3.5" />
                  </button>
                )}
              />
            </div>
          )}
        </div>
      )}

      <ToastContainer toasts={toasts} onRemoveToast={removeToast} />
    </>
  );
}
