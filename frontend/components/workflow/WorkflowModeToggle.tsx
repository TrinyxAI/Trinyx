'use client';

import { useRef, useEffect, useState, useCallback } from 'react';
import { Edit3, History, Play, Eye } from 'lucide-react';
import { formatCost } from '@/lib/format-cost';
import { useTranslations } from 'next-intl';
import { useRouter, usePathname } from 'next/navigation';
import { orchestratorApi, type WorkflowRun } from '@/lib/api';
import { useToast } from '@/components/Toast';
import ToastContainer from '@/components/ToastContainer';
import { canvasChromeButtonClass, canvasChromeSurfaceClass, canvasChromeCompactButtonClass } from '@/components/ui/canvas-chrome';
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
 * The run bar is IDENTITY ONLY - status · version · epoch · start ·
 * step-by-step, with stop/cancel at the far right. Everything that used to
 * expand out of it (epoch selector, step list, cost) now lives in the side
 * panel's Run tab, so nothing floats over the ReactFlow canvas any more.
 *
 * The bar IS the entry point - there is no separate history button and no panel
 * icon next to it: clicking the BAR opens the CURRENT run, clicking the version
 * chip inside it opens the run HISTORY.
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

  /** Invisible probe element to measure the real available container width
   *  (accounts for SidePanel, sidebar, etc. - not just window.innerWidth). */
  const probeRef = useRef<HTMLDivElement>(null);
  /** Available width of the canvas container (updated via ResizeObserver). */
  const [containerWidth, setContainerWidth] = useState(
    typeof window !== 'undefined' ? window.innerWidth : 1200,
  );

  const isRunMode = mode === 'run';
  const showRunInfo = isRunMode && !!currentRunInfo;

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

  // Derive isWideEnough from containerWidth - center the toggle only when there
  // is enough room so it won't collide with the run bar. Without a run bar the
  // only thing sharing the top edge is the add-node button in the corner, so a
  // phone-width canvas can still centre the ~88px toggle: the old 640 floor
  // pushed it into the left corner on every mobile EDIT canvas for no reason.
  const isWideEnough = containerWidth >= (showRunInfo ? 900 : 420);
  /** Centred when it fits, left-anchored otherwise (the run bar owns the right
   *  edge). The tighter inset on a phone keeps it clear of the canvas border. */
  const toggleAnchorClass = isWideEnough ? 'left-1/2 -translate-x-1/2' : 'left-2 sm:left-4';

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

      {/* Mode Toggle - Centered (hidden in application/preview mode). A segmented
          pair of canvas-chrome controls: the active one wears the same resting
          fill a selected panel tab does, which is why the old absolutely
          positioned slider is gone - the state now lives on the button itself. */}
      {!hideToggle && !showReadOnlyBadge && (
        <div className={`absolute top-4 z-[40] ${toggleAnchorClass}`}>
          <div className={`inline-flex items-center gap-0.5 p-1 ${canvasChromeSurfaceClass}`}>
            {/* Edit Mode Button */}
            <button
              type="button"
              aria-pressed={mode === 'edit'}
              data-testid="workflow-mode-edit"
              onClick={() => handleModeClick('edit')}
              title={t('workflow.mode.edit')}
              className={canvasChromeCompactButtonClass(mode === 'edit')}
            >
              <Edit3 className="w-3.5 h-3.5" />
            </button>

            {/* Run Mode Button */}
            <button
              type="button"
              aria-pressed={mode === 'run'}
              data-testid="workflow-mode-run"
              onClick={() => handleModeClick('run')}
              title={t('workflow.mode.run')}
              className={canvasChromeCompactButtonClass(mode === 'run')}
            >
              <Play className="w-3.5 h-3.5" />
            </button>
          </div>
        </div>
      )}

      {/* Read-only badge (shown in preview mode instead of the toggle) */}
      {showReadOnlyBadge && (
        <div className={`absolute top-4 z-[40] ${toggleAnchorClass}`}>
          <div className={`inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-[var(--text-secondary)] ${canvasChromeSurfaceClass}`}>
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
              // A standalone floating control, so it carries the chrome surface
              // itself instead of sitting on a card. Passed through the helper's
              // className slot (not concatenated) so twMerge resolves the two
              // background/border layers instead of leaving both in the class list.
              className={canvasChromeButtonClass(false, 'pointer-events-auto border-[var(--border-color)] bg-[var(--bg-primary)]/95 backdrop-blur')}
            >
              <History className="w-4 h-4" />
            </button>
          )}
          {/* Compact run bar - identity only, the panel holds the detail.
              The WHOLE bar is the way into the run panel: a dedicated icon for
              that was one more glyph competing with the chips for room in a pill
              that already overflows on a narrow canvas. Its inner controls
              (version chip -> history, stop/cancel/reactivate, scroll arrows)
              stop the click, so they keep their own behaviour. */}
          {showRunInfo && (
            <div
              data-run-info-panel
              data-run-open-panel
              role="button"
              tabIndex={0}
              title={t('workflow.runInfo.openInPanel')}
              onClick={() => openPanel('run')}
              onKeyDown={(e) => {
                // The bar ONLY - never a key pressed on a control inside it.
                // The inner buttons stop the click, but a keydown has no such
                // guard: Enter on the version chip would open the history AND
                // this run level, and the preventDefault below would swallow
                // Space on the stop button before the browser could activate it.
                if (e.target !== e.currentTarget) return;
                if (e.key !== 'Enter' && e.key !== ' ') return;
                e.preventDefault();
                openPanel('run');
              }}
              // The shared chrome surface, plus the affordances of a control:
              // the whole bar is clickable. Hover and ring go through the theme
              // tokens the rest of the chrome uses rather than a fixed gray.
              className={`pointer-events-auto w-fit max-w-full min-w-0 cursor-pointer transition-colors hover:bg-[var(--bg-secondary)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] ${canvasChromeSurfaceClass}`}
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
              />
            </div>
          )}
        </div>
      )}

      <ToastContainer toasts={toasts} onRemoveToast={removeToast} />
    </>
  );
}
