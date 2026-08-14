'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Play, Check, X, Clock, RotateCcw, Zap, MessageSquare, FileText, Webhook, CalendarClock, Workflow, Database, AlertTriangle } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { cn } from '@/lib/utils';
import { canvasNodeButtonClass, canvasNodeButtonShimmerRadiusClass } from '@/components/ui/canvas-chrome';
import { dispatchOpenTriggerTab, type TriggerPanelTab } from '@/lib/workflow/triggerTabEvent';

export type NodeExecutionStatus =
  | 'pending'    // Not yet executed, waiting for dependencies
  | 'ready'      // Dependencies met, can be executed
  | 'running'    // Currently executing
  | 'completed'  // Successfully completed
  | 'failed'     // Execution failed
  | 'skipped';   // Skipped (e.g., condition not met)

export type TriggerButtonVariant = 'play' | 'lightning' | 'message' | 'form' | 'webhook' | 'schedule' | 'workflow' | 'table' | 'error';

/**
 * Trigger variants that CANNOT be fired blind: the user types a message, fills a
 * form or crafts a request first, so the play button opens that trigger's
 * side-panel tab instead. Every other trigger variant fires immediately.
 *
 * <p>Single source of truth: the play button under a node and the run-mode
 * toolbar trigger picker both read it, so a new payload trigger can never end up
 * fired without its input on one surface only.
 */
export const PANEL_TAB_BY_TRIGGER_VARIANT: Partial<Record<TriggerButtonVariant, TriggerPanelTab>> = {
  message: 'chat',
  form: 'form',
  webhook: 'webhook',
};

/**
 * Unified "runnable now" shimmer color. A subtle blue scan on every
 * ready-to-run play button - triggers in run mode and every ready node in
 * step-by-step mode - so one colour carries one meaning: a blue shimmering
 * button means "you can run this now". Replaces the former per-trigger palette
 * and the green step-by-step play shimmer.
 */
const RUNNABLE_SHIMMER = 'rgba(59, 130, 246, 0.35)';

interface NodePlayButtonProps {
  nodeId: string;
  status: NodeExecutionStatus;
  canExecute: boolean;
  isLoading?: boolean;
  onExecute: (nodeId: string) => void;
  onRerun?: (nodeId: string) => void;
  className?: string;
  /** Button variant: 'play' (default), 'lightning' (manual trigger), 'message' (chat trigger), 'webhook' (webhook trigger - non-clickable), 'schedule' (schedule trigger - non-clickable) */
  variant?: TriggerButtonVariant;
  /**
   * Backend step id of this trigger (`trigger:<label>`). Carried in the
   * open-tab event so the side panel activates THIS trigger's tab: a workflow
   * can hold several chat/form/webhook triggers, and matching on type alone
   * always lands on the first one.
   */
  triggerId?: string;
  /** Custom title for the button */
  title?: string;
  /**
   * Called right before the button launches, on BOTH paths - the immediate fire
   * and the "open this trigger's side-panel tab" one.
   *
   * Exists because those two paths leave through different doors: `onExecute` for
   * a blind-fireable trigger, an open-tab event for a payload trigger. A caller
   * that has to do something before EITHER (the run-step popover leaves the
   * focused epoch, so the epoch it launches is visible when it arrives) cannot
   * hang it off `onExecute` alone - that hook never fires for chat, form or
   * webhook, which is exactly where the need is.
   */
  onBeforeLaunch?: () => void;
  /** Whether in automatic execution mode (hides pending state for non-triggers) */
  isAutoMode?: boolean;
  /**
   * Whether the run is stepped rather than driven automatically. Only affects the rerun
   * tooltip: in automatic mode the rerun does not stop at this node, the downstream chain
   * re-runs unattended, and the label has to say so.
   */
  isStepByStepMode?: boolean;
  /** Position style: 'top-right' (legacy corner badge) or 'bottom-center' (persistent below node) */
  position?: 'top-right' | 'bottom-center';
  /** Border color for bottom-center position (matches node status) */
  borderColor?: string;
}

/**
 * Play button overlay for workflow nodes in step-by-step execution mode.
 * Shows different states based on node execution status.
 */
export function NodePlayButton({
  nodeId,
  status,
  canExecute,
  isLoading = false,
  onExecute,
  onRerun,
  className,
  variant = 'play',
  triggerId,
  title,
  onBeforeLaunch,
  isAutoMode = false,
  isStepByStepMode = true,
  position = 'top-right',
  borderColor: borderColorProp,
}: NodePlayButtonProps) {
  const t = useTranslations('workflowBuilder.canvas');
  // Determine if this is a trigger variant
  const isTriggerVariant = ['lightning', 'message', 'form', 'webhook', 'schedule', 'workflow', 'table', 'error'].includes(variant);
  const isBottom = position === 'bottom-center';

  // Position classes
  const positionCls = isBottom
    ? 'relative'
    : 'absolute -top-3 -right-3 z-20';

  // Base style for bottom-center: the shared square node button, so the play sits
  // in the same row as the agent/sub-workflow/interface buttons without restating
  // their look (the copy of this literal is exactly how the two used to drift).
  const bottomBaseCls = canvasNodeButtonClass;
  const bottomBaseStyle = isBottom && borderColorProp ? { borderWidth: 2, borderStyle: 'solid' as const, borderColor: borderColorProp } : undefined;
  // Get icon based on variant
  const getIcon = () => {
    switch (variant) {
      case 'lightning':
        return <Zap className="h-4 w-4" />;
      case 'message':
        return <MessageSquare className="h-4 w-4" />;
      case 'form':
        return <FileText className="h-4 w-4" />;
      case 'webhook':
        return <Webhook className="h-4 w-4" />;
      case 'schedule':
        return <CalendarClock className="h-4 w-4" />;
      case 'workflow':
        return <Workflow className="h-4 w-4" />;
      case 'table':
        return <Database className="h-4 w-4" />;
      case 'error':
        return <AlertTriangle className="h-4 w-4" />;
      default:
        return <Play className="h-4 w-4 ml-0.5" fill="currentColor" />;
    }
  };

  // Get default title based on variant
  const getDefaultTitle = () => {
    switch (variant) {
      case 'lightning':
        return t('fireManualTrigger');
      case 'message':
        return t('sendChatMessage');
      case 'form':
        return t('fillForm');
      case 'webhook':
        return t('listeningWebhook');
      case 'schedule':
        return t('waitingSchedule');
      case 'workflow':
        return t('waitingWorkflow');
      case 'table':
        return t('simulateTableTrigger');
      case 'error':
        return t('waitingError');
      default:
        return t('executeStep');
    }
  };

  const buttonTitle = title || getDefaultTitle();
  const handleClick = React.useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    if (canExecute && !isLoading && status === 'ready') {
      // Payload triggers open their side-panel tab instead (their own render
      // block owns that); read the shared map rather than re-listing variants,
      // which is how this guard drifted out of sync with `webhook`.
      if (PANEL_TAB_BY_TRIGGER_VARIANT[variant]) {
        return;
      }
      onBeforeLaunch?.();
      onExecute(nodeId);
    }
  }, [nodeId, canExecute, isLoading, status, onExecute, onBeforeLaunch, variant]);

  const handleRerun = React.useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    if (onRerun && !isLoading) {
      onRerun(nodeId);
    }
  }, [nodeId, onRerun, isLoading]);

  // ── Helper: shimmer overlay for bottom-center buttons ──
  const shimmerOverlay = (color: string) => (
    <span
      className={`absolute inset-0 ${canvasNodeButtonShimmerRadiusClass} pointer-events-none`}
      style={{
        background: `linear-gradient(90deg, transparent 0%, ${color} 50%, transparent 100%)`,
        backgroundSize: '200% 100%',
        animation: 'shimmer-scan 4s ease-in-out infinite',
      }}
    />
  );

  // ── Rerun button (completed/failed with onRerun) ──
  if ((status === 'completed' || status === 'failed') && onRerun) {
    if (isBottom) {
      return (
        <button
          onClick={handleRerun}
          className={cn(bottomBaseCls, 'cursor-pointer', className)}
          style={bottomBaseStyle}
          title={t(isStepByStepMode ? 'rerunStep' : 'rerunStepAuto')}
          data-testid="node-rerun-button"
        >
          <span className="relative z-10"><RotateCcw className="h-3 w-3" strokeWidth={2} /></span>
        </button>
      );
    }
    return (
      <button
        onClick={handleRerun}
        className={cn(
          positionCls,
          'w-8 h-8 rounded-xl',
          status === 'failed'
            ? 'bg-red-500 hover:bg-red-600 text-white'
            : 'bg-white dark:bg-slate-700 text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-600',
          'flex items-center justify-center',
          'shadow-lg cursor-pointer transition-all duration-200 hover:scale-110',
          'border-2 border-white',
          'focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1',
          className
        )}
        title={t(isStepByStepMode ? 'rerunStep' : 'rerunStepAuto')}
        data-testid="node-rerun-button"
      >
        <RotateCcw className="h-4 w-4" />
      </button>
    );
  }

  // Hide for completed/failed/skipped if no rerun handler
  if (status === 'completed' || status === 'failed' || status === 'skipped') {
    return null;
  }

  // ── Running state - spinner ──
  if (status === 'running' || isLoading) {
    if (isBottom) {
      return (
        <div className={cn(bottomBaseCls, className)} style={bottomBaseStyle}>
          {shimmerOverlay('rgba(59, 130, 246, 0.3)')}
          <span className="relative z-10"><LoadingSpinner size="xs" /></span>
        </div>
      );
    }
    return (
      <div
        className={cn(positionCls, 'w-8 h-8 rounded-xl bg-blue-500 text-white flex items-center justify-center shadow-lg border-2 border-white', className)}
      >
        <LoadingSpinner size="xs" />
      </div>
    );
  }

  // ── Ready state ──
  if (status === 'ready' && canExecute) {
    const iconSize = isBottom ? 'h-3 w-3' : 'h-3.5 w-3.5';

    // Trigger simulate buttons - unified Play icon under every trigger node.
    // Behavior differs by variant: webhook/chat/form open the right-side
    // TriggerPanel tab; every other trigger (manual, schedule, workflow, table,
    // error) fires immediately via onExecute. All share the single blue
    // "runnable" shimmer (RUNNABLE_SHIMMER) applied at the render site below.
    const triggerSimulate = (() => {
      const panelTab = PANEL_TAB_BY_TRIGGER_VARIANT[variant];
      if (panelTab) return { tab: panelTab };
      // Manual (amber-icon), schedule, workflow, table and error triggers fire
      // their run immediately; the error trigger reuses this path as the manual
      // bootstrap of the failure-handling run.
      if (isTriggerVariant) return { onClick: handleClick };
      return null;
    })();

    if (triggerSimulate) {
      const onClick = 'onClick' in triggerSimulate
        ? triggerSimulate.onClick
        : (e: React.MouseEvent) => {
            e.stopPropagation();
            // Before the panel takes over: the run this opens starts later, from
            // the panel, which cannot reach back to whatever the caller has to
            // settle first.
            onBeforeLaunch?.();
            dispatchOpenTriggerTab({ nodeId, triggerId, triggerType: triggerSimulate.tab });
          };
      const playIcon = <Play className={iconSize} strokeWidth={2} fill="currentColor" />;
      if (isBottom) {
        return (
          <button
            onClick={onClick}
            className={cn(bottomBaseCls, 'cursor-pointer', className)}
            style={bottomBaseStyle}
            title={buttonTitle}
          >
            {shimmerOverlay(RUNNABLE_SHIMMER)}
            <span className="relative z-10">{playIcon}</span>
          </button>
        );
      }
      return (
        <button
          onClick={onClick}
          className={cn(positionCls, 'w-8 h-8 rounded-xl flex items-center justify-center cursor-pointer bg-white border-2 border-slate-200 shadow-lg overflow-hidden hover:scale-110 transition-transform duration-200', className)}
          title={buttonTitle}
        >
          {shimmerOverlay(RUNNABLE_SHIMMER)}
          <span className="relative z-10 text-slate-700">{playIcon}</span>
        </button>
      );
    }

    // Play variant (step-by-step non-trigger) - clickable action button.
    // Lightning/manual is now handled by the unified triggerSimulate block above.
    if (isBottom) {
      return (
        <button
          onClick={handleClick}
          className={cn(bottomBaseCls, 'cursor-pointer', className)}
          style={bottomBaseStyle}
          title={buttonTitle}
        >
          {shimmerOverlay(RUNNABLE_SHIMMER)}
          <span className="relative z-10">
            <Play className={iconSize} strokeWidth={2} fill="currentColor" />
          </span>
        </button>
      );
    }

    const bgColor = 'bg-green-500 hover:bg-green-600';
    const ringColor = 'focus:ring-green-400';
    return (
      <button
        onClick={handleClick}
        className={cn(positionCls, 'w-8 h-8 rounded-xl', bgColor, 'text-white flex items-center justify-center shadow-lg cursor-pointer transition-all duration-200 hover:scale-110 border-2 border-white focus:outline-none focus:ring-2 focus:ring-offset-1', ringColor, className)}
        title={buttonTitle}
      >
        {getIcon()}
      </button>
    );
  }

  // ── Pending state ──
  if (status === 'pending') {
    if (!isTriggerVariant) return null;
    if (isAutoMode) return null;
    if (isBottom) {
      return (
        <div className={cn(bottomBaseCls, 'cursor-not-allowed opacity-60', className)} style={bottomBaseStyle} title={t('waitingDependencies')}>
          <span className="relative z-10"><Clock className="h-3 w-3" strokeWidth={2} /></span>
        </div>
      );
    }
    return (
      <div
        className={cn(positionCls, 'w-8 h-8 rounded-xl bg-slate-300 dark:bg-slate-600 text-slate-500 dark:text-slate-400 flex items-center justify-center shadow-lg border-2 border-white cursor-not-allowed', className)}
        title={t('waitingDependencies')}
      >
        <Clock className="h-4 w-4" />
      </div>
    );
  }

  return null;
}

/**
 * Determines the execution status for a node based on workflow state.
 * @deprecated Use deriveNodeStatus() with backend-driven boolean flags instead.
 * This function derives status from flat sets which can conflict with the backend's
 * authoritative StepState (e.g., ready vs completed priority issues).
 */
export function getNodeExecutionStatus(
  nodeId: string,
  completedSteps: Set<string>,
  failedSteps: Set<string>,
  readySteps: Set<string>,
  runningSteps?: Set<string>,
  skippedSteps?: Set<string>
): NodeExecutionStatus {
  // Running has highest priority (currently executing)
  if (runningSteps?.has(nodeId)) return 'running';
  // Ready has priority over completed (e.g., loop ready for next iteration)
  // This is important for loops that are "completed" for previous iteration
  // but "ready" for the next iteration
  if (readySteps.has(nodeId)) return 'ready';
  // Then check terminal states
  if (failedSteps.has(nodeId)) return 'failed';
  if (skippedSteps?.has(nodeId)) return 'skipped';
  if (completedSteps.has(nodeId)) return 'completed';
  return 'pending';
}

/**
 * Derives the button status from backend-driven boolean flags.
 * The flags come from useNodeExecutionStatus() which reads the backend's
 * StepState.status as the single source of truth.
 *
 * Priority: running > failed > skipped > completed > ready > pending
 * Note: completed takes priority over ready. When the backend marks a node
 * as COMPLETED + canExecute=true, the UI shows a RERUN button (not PLAY).
 */
export function deriveNodeStatus(flags: {
  isRunning: boolean;
  isFailed: boolean;
  isSkipped: boolean;
  isCompleted: boolean;
  isReady: boolean;
}): NodeExecutionStatus {
  if (flags.isRunning) return 'running';
  if (flags.isFailed) return 'failed';
  if (flags.isSkipped) return 'skipped';
  if (flags.isCompleted) return 'completed';
  if (flags.isReady) return 'ready';
  return 'pending';
}
