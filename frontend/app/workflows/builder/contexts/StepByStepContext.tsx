'use client';

import * as React from 'react';
import type { WorkflowRunState, StepState, CoreExecutionResponse, StepRerunResponse } from '@/lib/api';
import type { PendingSignal } from '@/lib/websocket/ws-types';
import { normalizeLabel, extractLabelFromKey } from '../utils/labelNormalizer';
import { getPrefixForKind } from '../registry/nodeRegistry';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { RerunConfirmModal } from '../components/RerunConfirmModal';

export interface StepByStepContextValue {
  // Mode
  isStepByStepMode: boolean;
  /**
   * The run's persisted execution mode, WITHOUT folding in terminality the way
   * {@link isStepByStepMode} does. A finished step-by-step run has
   * {@code isStepByStepMode === false} but is still stepped, so anything describing what a
   * rerun will DO (the backend keys off the persisted mode) must read this instead: on a
   * stepped run a rerun executes nothing and waits for the user.
   */
  isSteppedRun: boolean;
  isPaused: boolean;

  // State
  readySteps: Set<string>;
  completedSteps: Set<string>;
  failedSteps: Set<string>;
  skippedSteps: Set<string>;
  runningSteps: Set<string>;
  awaitingSignalSteps: Set<string>;
  evaluatedCores: Set<string>;

  // Step state details
  getStepState: (stepId: string) => StepState | undefined;
  /** Resolve a React Flow node ID to its backend step ID using the backend-provided mapping */
  resolveNodeId: (nodeId: string, nodeData?: { label?: string; kind?: string; crudOperation?: string }) => string;

  // Actions
  executeStep: (stepId: string, epoch?: number) => Promise<void>;
  executeCore: (coreId: string) => Promise<CoreExecutionResponse | null>;
  canExecuteStep: (stepId: string) => boolean;
  canExecuteCore: (coreId: string) => boolean;
  isCore: (nodeId: string) => boolean;

  // Re-run actions
  rerunStep: (stepId: string) => Promise<StepRerunResponse | null>;
  canRerunStep: (stepId: string) => boolean;
  isRerunning: boolean;

  // Approval actions
  resolveApproval: (nodeId: string, resolution: 'APPROVED' | 'REJECTED', epoch?: number, itemId?: string) => Promise<void>;
  getPendingSignalCount: (nodeId: string) => number;
  getPendingSignalsForNode: (nodeId: string) => PendingSignal[];
  /** ALL pending USER_APPROVAL signals across every node (run-wide queue). */
  getAllPendingSignals: () => PendingSignal[];

  // Loading
  isExecutingStep: string | null;

  // Last decision result (for UI display)
  lastDecisionResult: CoreExecutionResponse | null;

  // Epoch data for parallel SBS support
  activeEpochs: number[];

  /**
   * The run's newest epoch. Used to tell "reading the live state through its
   * epoch" apart from "reading a historical epoch" - only the latter is
   * read-only. See the `isInteractive` note in {@link useNodeExecutionStatus}.
   */
  currentEpoch: number;
}

const StepByStepContext = React.createContext<StepByStepContextValue | null>(null);

interface StepByStepProviderProps {
  children: React.ReactNode;
  isEnabled: boolean;
  isPaused: boolean;
  isRunTerminal?: boolean;
  /**
   * The run was deliberately put down (stopped/cancelled) or timed out. Distinct from
   * {@link isRunTerminal}, which also covers runs that simply FINISHED and stay rerunnable.
   */
  isRunUnrevivable?: boolean;
  readySteps: Set<string>;
  completedSteps: Set<string>;
  failedSteps: Set<string>;
  skippedSteps?: Set<string>;
  runningSteps?: Set<string>;
  awaitingSignalSteps?: Set<string>;
  evaluatedCores?: Set<string>;
  stepStates?: Map<string, StepState>;
  /** Backend-provided mapping: React Flow node ID → backend step ID */
  nodeIdToStepId?: Map<string, string>;
  lastDecisionResult?: CoreExecutionResponse | null;
  onExecuteStep: (stepId: string, epoch?: number) => Promise<void>;
  onExecuteCore?: (coreId: string) => Promise<CoreExecutionResponse | null>;
  // Re-run support
  onRerunStep?: (stepId: string) => Promise<StepRerunResponse | null>;
  isRerunning?: boolean;
  // Approval support
  onResolveApproval?: (nodeId: string, resolution: 'APPROVED' | 'REJECTED', epoch?: number, itemId?: string) => Promise<void>;
  pendingSignals?: PendingSignal[];
  // Epoch data for parallel SBS support
  activeEpochs?: number[];
  /** The run's newest epoch (0 when the run has not fired yet). */
  currentEpoch?: number;
}

/**
 * A rerun waiting for the user's answer on an AUTOMATIC run: the caller's promise is
 * parked here until the confirmation is accepted or dismissed.
 */
interface PendingRerun {
  stepId: string;
  resolve: (value: StepRerunResponse | null) => void;
  reject: (reason: unknown) => void;
}

/**
 * Human-readable name for a backend step id (`mcp:my_tool` -> `my tool`), shown in the
 * rerun confirmation so the user can vet WHICH node the restart starts from. Falls back
 * to the id itself when it carries no prefix.
 */
function stepDisplayLabel(stepId: string): string {
  const label = extractLabelFromKey(stepId);
  return label ? label.replace(/_/g, ' ') : stepId;
}

export function StepByStepProvider({
  children,
  isEnabled,
  isPaused,
  isRunTerminal = false,
  isRunUnrevivable = false,
  readySteps,
  completedSteps,
  failedSteps,
  skippedSteps = new Set(),
  runningSteps = new Set(),
  awaitingSignalSteps = new Set(),
  evaluatedCores = new Set(),
  stepStates = new Map(),
  nodeIdToStepId = new Map(),
  lastDecisionResult = null,
  onExecuteStep,
  onExecuteCore,
  onRerunStep,
  isRerunning = false,
  onResolveApproval,
  pendingSignals = [],
  activeEpochs = [],
  currentEpoch = 0,
}: StepByStepProviderProps) {
  const [executingStep, setExecutingStep] = React.useState<string | null>(null);
  const [pendingRerun, setPendingRerun] = React.useState<PendingRerun | null>(null);
  // Mirror of `pendingRerun` readable from callbacks and from the unmount cleanup, where
  // the state value captured at render time would already be stale.
  const pendingRerunRef = React.useRef<PendingRerun | null>(null);
  React.useEffect(() => {
    pendingRerunRef.current = pendingRerun;
  }, [pendingRerun]);
  // Never leave a caller awaiting a promise nobody can answer any more.
  React.useEffect(() => () => {
    pendingRerunRef.current?.resolve(null);
    pendingRerunRef.current = null;
  }, []);

  const executeStep = React.useCallback(async (stepId: string, epoch?: number) => {
    setExecutingStep(stepId);
    try {
      await onExecuteStep(stepId, epoch);
    } finally {
      setExecutingStep(null); // Clear when API returns (step processed)
    }
  }, [onExecuteStep]);

  const executeCore = React.useCallback(async (coreId: string): Promise<CoreExecutionResponse | null> => {
    if (!onExecuteCore) return null;
    setExecutingStep(coreId);
    try {
      return await onExecuteCore(coreId);
    } finally {
      setExecutingStep(null); // Clear when API returns (step processed)
    }
  }, [onExecuteCore]);

  const canExecuteStep = React.useCallback((stepId: string): boolean => {
    // Terminal runs (CANCELLED, COMPLETED, FAILED, TIMEOUT) - no interaction allowed
    if (isRunTerminal) return false;

    // In automatic mode, manual/chat triggers can still be executed when ready (WAITING_TRIGGER state)
    // They need user interaction to start the workflow
    const isTriggerNode = stepId.startsWith('trigger:');
    if (isTriggerNode && readySteps.has(stepId)) {
      return true; // Manual/chat triggers are always executable when ready
    }

    // For other steps, require step-by-step mode or paused state
    if (!isEnabled && !isPaused) return false;

    // Allow all ready steps including core nodes (decisions/switches).
    // Core nodes now route through the V2 engine on the backend,
    // getting the same execution pipeline as regular steps.
    return readySteps.has(stepId);
  }, [isEnabled, isPaused, isRunTerminal, readySteps]);

  const canExecuteCore = React.useCallback((coreId: string): boolean => {
    if (isRunTerminal) return false;
    if (!isEnabled && !isPaused) return false;
    return readySteps.has(coreId) && !evaluatedCores.has(coreId);
  }, [isEnabled, isPaused, isRunTerminal, readySteps, evaluatedCores]);

  const isCore = React.useCallback((nodeId: string): boolean => {
    return isCoreNodeId(nodeId);
  }, []);

  const getStepState = React.useCallback((stepId: string): StepState | undefined => {
    return stepStates.get(stepId);
  }, [stepStates]);

  // Resolve React Flow node ID → backend step ID using backend-provided mapping.
  // Falls back to computeBackendStepId only if the mapping doesn't have the ID yet.
  const resolveNodeId = React.useCallback((nodeId: string, nodeData?: { label?: string; kind?: string; crudOperation?: string }): string => {
    const mapped = nodeIdToStepId.get(nodeId);
    if (mapped) return mapped;
    // Fallback for nodes not yet in the mapping (e.g. before first execution)
    return nodeData ? computeBackendStepId(nodeId, nodeData) : normalizeNodeId(nodeId);
  }, [nodeIdToStepId]);

  // Re-run a step (and reset all downstream steps)
  // For triggers: rerun = selective reset (same as other nodes), NOT re-fire.
  // This resets the trigger and all downstream to READY, showing PLAY buttons again.
  // The user then clicks PLAY to actually fire the trigger (new epoch).
  //
  // AUTOMATIC runs go through a confirmation first. On a stepped run the rerun stops at the
  // target and waits for the user, so it costs nothing; in automatic mode the same click
  // reruns the target AND lets the whole downstream chain run again unattended, which can
  // spend paid calls and send real messages. That asymmetry is invisible on the button, so
  // the gate lives here rather than on each surface: the canvas bar, the context menu and
  // the inspector all call this one function and inherit it.
  const rerunStep = React.useCallback(async (stepId: string): Promise<StepRerunResponse | null> => {
    if (!onRerunStep) return null;
    if (isEnabled) return await onRerunStep(stepId);
    return await new Promise<StepRerunResponse | null>((resolve, reject) => {
      setPendingRerun((previous) => {
        // A second rerun click while the gate is open supersedes the first; the superseded
        // caller must not be left awaiting forever.
        previous?.resolve(null);
        return { stepId, resolve, reject };
      });
    });
  }, [onRerunStep, isEnabled]);

  const confirmPendingRerun = React.useCallback(async () => {
    const pending = pendingRerunRef.current;
    if (!pending) return;
    pendingRerunRef.current = null;
    setPendingRerun(null);
    try {
      pending.resolve(onRerunStep ? await onRerunStep(pending.stepId) : null);
    } catch (err) {
      // Surface the failure to whoever awaited the rerun, exactly as the ungated path does.
      pending.reject(err);
    }
  }, [onRerunStep]);

  const cancelPendingRerun = React.useCallback(() => {
    const pending = pendingRerunRef.current;
    pendingRerunRef.current = null;
    setPendingRerun(null);
    // A dismissed rerun is a no-op, not an error: resolve with the same "nothing happened"
    // value the callers already handle.
    pending?.resolve(null);
  }, []);

  // Check if a step can be re-run (must be COMPLETED, FAILED, or RUNNING)
  // NOTE: SKIPPED steps cannot be retried (branch wasn't taken - retry from decision instead)
  // RUNNING check is kept for while loops and long-running agents.
  // NOTE: In the simplified split system, split completes immediately after spawning items,
  // so split nodes will be in completedSteps, not runningSteps.
  // Triggers use the same rerun logic as other nodes (selective reset).
  //
  // NOT gated on step-by-step mode: the backend rerun path is mode-blind, and in AUTOMATIC
  // mode it reruns the target then drives the rest of the chain itself. Gating here was what
  // made "restart from a node" unreachable outside step-by-step.
  //
  // Still refused on a run the user (or a timeout) put down: reviving one is a re-trigger
  // decision, not a rerun. A run that merely FINISHED stays rerunnable, which is the whole
  // point - restarting mid-graph on a completed run is the common case.
  const canRerunStep = React.useCallback((stepId: string): boolean => {
    if (isRunUnrevivable) return false;
    if (completedSteps.has(stepId) || failedSteps.has(stepId)) return true;
    // A node still RUNNING is only an escape hatch for a run the user drives by hand (a stuck
    // while-loop, a long agent). On an automatic run the invocation is genuinely in flight and
    // will write its own completion, and the backend accepts the rerun anyway when an EARLIER
    // epoch completed the node - so offering it here buys a double execution and a lost write,
    // not a clean refusal.
    return isEnabled && runningSteps.has(stepId);
  }, [isEnabled, isRunUnrevivable, completedSteps, failedSteps, runningSteps]);

  // Resolve a user approval signal
  const resolveApproval = React.useCallback(async (nodeId: string, resolution: 'APPROVED' | 'REJECTED', epoch?: number, itemId?: string) => {
    if (!onResolveApproval) return;
    setExecutingStep(nodeId);
    try {
      await onResolveApproval(nodeId, resolution, epoch, itemId);
    } finally {
      setExecutingStep(null); // Clear when API returns (approval processed)
    }
  }, [onResolveApproval]);

  // Count pending USER_APPROVAL signals for a specific node
  const getPendingSignalCount = React.useCallback((nodeId: string): number => {
    return pendingSignals.filter(
      s => s.nodeId === nodeId && s.signalType === 'USER_APPROVAL'
    ).length;
  }, [pendingSignals]);

  // Get pending signals for a specific node (for per-item approval UI)
  const getPendingSignalsForNode = React.useCallback((nodeId: string): PendingSignal[] => {
    return pendingSignals.filter(
      s => s.nodeId === nodeId && s.signalType === 'USER_APPROVAL'
    );
  }, [pendingSignals]);

  // ALL pending USER_APPROVAL signals across every node - feeds the run-wide
  // approval queue so the ApprovalReviewBar can navigate between approvals that
  // belong to different nodes, not just items of the inspected one.
  const getAllPendingSignals = React.useCallback((): PendingSignal[] => {
    return pendingSignals.filter(s => s.signalType === 'USER_APPROVAL');
  }, [pendingSignals]);

  // Clear executingStep when backend confirms status change via batch-update
  React.useEffect(() => {
    if (!executingStep) return;
    const isConfirmed =
      completedSteps.has(executingStep) ||
      failedSteps.has(executingStep) ||
      runningSteps.has(executingStep) ||
      skippedSteps.has(executingStep) ||
      awaitingSignalSteps.has(executingStep);
    if (isConfirmed) {
      setExecutingStep(null);
    }
  }, [executingStep, completedSteps, failedSteps, runningSteps, skippedSteps, awaitingSignalSteps]);

  const value: StepByStepContextValue = React.useMemo(() => ({
    isStepByStepMode: isEnabled && !isRunTerminal,
    isSteppedRun: isEnabled,
    isPaused,
    readySteps,
    completedSteps,
    failedSteps,
    skippedSteps,
    runningSteps,
    awaitingSignalSteps,
    evaluatedCores,
    getStepState,
    resolveNodeId,
    executeStep,
    executeCore,
    canExecuteStep,
    canExecuteCore,
    isCore,
    rerunStep,
    canRerunStep,
    isRerunning,
    resolveApproval,
    getPendingSignalCount,
    getPendingSignalsForNode,
    getAllPendingSignals,
    isExecutingStep: executingStep,
    lastDecisionResult,
    activeEpochs,
    currentEpoch,
  }), [
    isEnabled,
    isRunTerminal,
    isPaused,
    readySteps,
    completedSteps,
    failedSteps,
    skippedSteps,
    runningSteps,
    awaitingSignalSteps,
    evaluatedCores,
    getStepState,
    resolveNodeId,
    executeStep,
    executeCore,
    canExecuteStep,
    canExecuteCore,
    isCore,
    rerunStep,
    canRerunStep,
    isRerunning,
    resolveApproval,
    getPendingSignalCount,
    getPendingSignalsForNode,
    getAllPendingSignals,
    executingStep,
    lastDecisionResult,
    activeEpochs,
    currentEpoch,
  ]);

  return (
    <StepByStepContext.Provider value={value}>
      {children}
      {pendingRerun && (
        <RerunConfirmModal
          stepLabel={stepDisplayLabel(pendingRerun.stepId)}
          onConfirm={confirmPendingRerun}
          onCancel={cancelPendingRerun}
        />
      )}
    </StepByStepContext.Provider>
  );
}

export function useStepByStep(): StepByStepContextValue | null {
  return React.useContext(StepByStepContext);
}

/**
 * Hook to get execution status for a specific node (step or core node)
 * @param nodeId - The frontend node ID
 * @param nodeData - Optional node data containing label and kind for accurate backend ID mapping
 */
export function useNodeExecutionStatus(nodeId: string, nodeData?: { label?: string; kind?: string; crudOperation?: string }) {
  const ctx = useStepByStep();
  const { viewingEpoch } = useWorkflowMode();

  // Interactive in the "All epochs" view (viewingEpoch == null) AND while reading
  // the run's NEWEST epoch - only a HISTORICAL epoch is read-only.
  //
  // This used to require "All epochs" alone, which was correct while a run opened
  // unselected. Since the run history moved into the side panel, every run surface
  // seeds a default epoch on the shared provider (useDefaultEpochSelection: "a run
  // is always read THROUGH an epoch"), so the canvas is never on "All" by default.
  // With the old rule that silently hid EVERY play and rerun button on non-trigger
  // nodes, which made a step-by-step run impossible to step from the canvas - the
  // one surface that drives it. (Triggers looked fine only because FlowNode already
  // re-exposes a focus-epoch play button.)
  //
  // Reading the live state through its own epoch is not history, so it stays
  // interactive; an older epoch is a record of what happened and stays read-only.
  const isInteractive = viewingEpoch == null
    || (ctx != null && ctx.currentEpoch > 0 && viewingEpoch === ctx.currentEpoch);

  if (!ctx) {
    return {
      isStepByStepMode: false,
      isSteppedRun: false,
      canExecute: false,
      isReady: false,
      canExecuteRaw: false,
      isCompleted: false,
      isFailed: false,
      isSkipped: false,
      isRunning: false,
      isAwaitingSignal: false,
      isExecuting: false,
      isCore: false,
      isEvaluated: false,
      // Outside a run there is no backend step id to give - a React Flow node id
      // here would be a lie that silently reaches cross-component events.
      stepId: undefined as string | undefined,
      executeStep: async () => {},
      fireFromAnyEpoch: async () => {},
      executeCore: async () => null,
      // Re-run
      canRerun: false,
      isRerunning: false,
      rerunStep: async () => null,
      // Approval
      resolveApproval: async () => {},
      pendingSignalCount: 0,
      pendingSignals: [],
    };
  }

  // Resolve React Flow node ID → backend step ID.
  // Primary: backend-provided mapping (nodeIdToStepId). Fallback: computeBackendStepId.
  const normalizedId = ctx.resolveNodeId(nodeId, nodeData);
  const isControl = ctx.isCore(normalizedId);

  // SSE sets are the PRIMARY source - they update in real-time during streaming.
  // Backend stepStates (REST) may be stale during SSE streaming.
  // deriveNodeStatus() handles priority: running > failed > skipped > completed > ready > pending
  const isRunning = ctx.runningSteps.has(normalizedId);
  const isFailed = ctx.failedSteps.has(normalizedId);
  const isSkipped = ctx.skippedSteps.has(normalizedId);
  const isCompleted = ctx.completedSteps.has(normalizedId);
  const isReady = ctx.readySteps.has(normalizedId);
  const isAwaitingSignal = ctx.awaitingSignalSteps.has(normalizedId);

  return {
    // Only true if explicitly in step-by-step mode AND interactive.
    // Historical epoch viewing disables all controls - epoch data determines visuals.
    isStepByStepMode: ctx.isStepByStepMode && isInteractive,
    isSteppedRun: ctx.isSteppedRun,
    canExecute: isInteractive && (isControl ? ctx.canExecuteCore(normalizedId) : ctx.canExecuteStep(normalizedId)),
    isReady: isInteractive && isReady,
    /**
     * Executability ignoring the focus-epoch interactive gate - but NOT the run's
     * own state: `canExecuteStep` still returns false on a terminal run, which the
     * backend dispatcher would refuse anyway.
     *
     * Lets the focus view offer a trigger's play button (which returns to
     * all-epochs and fires that trigger) while normal controls stay hidden. Read
     * this, never a bare readiness flag: a terminal run keeps its steps in
     * `readySteps`, so readiness alone promises a run that cannot happen.
     */
    canExecuteRaw: isControl ? ctx.canExecuteCore(normalizedId) : ctx.canExecuteStep(normalizedId),
    isCompleted,
    isFailed,
    isSkipped,
    isRunning,
    isAwaitingSignal,
    isExecuting: isInteractive && ctx.isExecutingStep === normalizedId,
    isCore: isControl,
    isEvaluated: ctx.evaluatedCores.has(normalizedId),
    // Backend step id (`trigger:<label>` for a trigger). Surfaced so callers can
    // name THIS node in cross-component events instead of letting the receiver
    // guess from the node type - several triggers can share one type.
    stepId: normalizedId,
    executeStep: () => ctx.executeStep(normalizedId, viewingEpoch ?? undefined),
    // Fire this step against ALL-epochs (epoch=undefined) regardless of the
    // currently-viewed epoch - used by the focus-epoch trigger play after it
    // returns to the all-epochs view, so the clicked trigger actually fires.
    fireFromAnyEpoch: () => ctx.executeStep(normalizedId, undefined),
    executeCore: () => ctx.executeCore(normalizedId),
    // Re-run: available for COMPLETED, FAILED steps (only in live view)
    // For triggers: rerun = fire again (new epoch), determined by canRerunStep using backend state
    canRerun: isInteractive && ctx.canRerunStep(normalizedId),
    isRerunning: ctx.isRerunning,
    rerunStep: () => ctx.rerunStep(normalizedId),
    // Approval - epochOverride lets per-signal UIs (item rows, ApprovalReviewBar)
    // target the signal's OWN epoch: in 'All epochs' view viewingEpoch is null,
    // which would otherwise leave the backend to guess when several epochs are pending.
    resolveApproval: (resolution: 'APPROVED' | 'REJECTED', itemId?: string, epochOverride?: number) =>
      ctx.resolveApproval(normalizedId, resolution, epochOverride ?? viewingEpoch ?? undefined, itemId),
    pendingSignalCount: ctx.getPendingSignalCount(normalizedId),
    pendingSignals: ctx.getPendingSignalsForNode(normalizedId),
  };
}

/**
 * Check if an ID is a core node (decision or switch ONLY)
 * Core nodes require special handling in step-by-step mode (evaluated, not executed)
 *
 * IMPORTANT: Loop and Split are NOT core nodes - they are executed like regular steps
 */
/**
 * Check if an ID is a core node.
 * Core nodes use the unified core: prefix.
 */
function isCoreNodeId(nodeId: string): boolean {
  return nodeId.startsWith('core:');
}

/**
 * Normalize node ID to match backend step IDs.
 * This is a fallback when nodeData is not available.
 *
 * === SIMPLIFIED PREFIX SYSTEM (4 categories) ===
 *
 * | Prefix     | Category | Applies To                                              |
 * |------------|----------|--------------------------------------------------------|
 * | trigger:   | Entry    | All triggers (webhook, chat, schedule, etc.)            |
 * | mcp:       | Action   | Tools, CRUD (external API calls)                        |
 * | agent:     | AI       | Agent, Guardrail, Classify                              |
 * | core:      | Control  | Loop, Split, Decision, Switch, Merge, Transform, Wait, Fork, Stop, Response, Download File, HTTP Request, Data Input, User Approval |
 */
function normalizeNodeId(nodeId: string): string {
  // Already has a valid prefix - return as is
  if (nodeId.startsWith('trigger:') ||
      nodeId.startsWith('mcp:') ||
      nodeId.startsWith('agent:') ||
      nodeId.startsWith('core:') ||
      nodeId.startsWith('table:') ||
      nodeId.startsWith('interface:')) {
    return nodeId;
  }

  // Extract label and normalize it
  const label = nodeId.replace(/-\d+$/, ''); // Remove trailing numbers like "-123"
  const normalized = normalizeLabel(label) || label;

  // Determine prefix based on node ID pattern
  // Triggers - must check before 'agent' since 'tables-trigger' doesn't contain 'agent'
  if (nodeId.startsWith('trigger-') || nodeId.startsWith('trigger:') || nodeId.startsWith('tables-trigger-')) {
    return `trigger:${normalized}`;
  }

  // CRUD table nodes - check before generic patterns to avoid false matches
  if (nodeId.startsWith('create-') || nodeId.startsWith('read-') || nodeId.startsWith('update-') ||
      nodeId.startsWith('delete-') || nodeId.startsWith('find-') || nodeId.startsWith('list-') ||
      nodeId.startsWith('table-')) {
    return `table:${normalized}`;
  }

  // Control flow nodes (decision, switch, loop, split, merge, transform, wait, fork, exit, response, download_file, http_request, data_input)
  if (nodeId.includes('if-else') || nodeId.includes('decision') || nodeId.includes('switch') ||
      nodeId.includes('loop') || nodeId.includes('while') ||
      nodeId.includes('split') ||
      nodeId.includes('merge') || nodeId.includes('transform') ||
      nodeId.includes('wait') || nodeId.includes('fork') ||
      nodeId.includes('exit') || nodeId.includes('response') ||
      nodeId.includes('download_file') || nodeId.includes('download-file') ||
      nodeId.includes('public_link') || nodeId.includes('public-link') ||
      // 'media' must be a PREFIX match: MCP tool ids like create_media_container-123
      // CONTAIN "media" but are mcp: nodes, not core: nodes.
      nodeId === 'media' || nodeId.startsWith('media-') ||
      nodeId.includes('http_request') || nodeId.includes('http-request') ||
      nodeId.includes('data_input') || nodeId.includes('data-input')) {
    return `core:${normalized}`;
  }

  // Agent nodes
  if (nodeId.includes('ai-agent') || nodeId.startsWith('agent-') || nodeId.startsWith('agent:')) {
    return `agent:${normalized}`;
  }

  // Interface nodes
  if (nodeId.startsWith('interface-') || nodeId.startsWith('interface:')) {
    return `interface:${normalized}`;
  }

  // Default: mcp (tool call)
  return `mcp:${normalized}`;
}

/**
 * Compute the backend step ID from node data
 * Used for mapping frontend node IDs to backend step IDs
 *
 * === PREFIX SYSTEM (7 categories) ===
 *
 * | Prefix     | Category  | Applies To                                              |
 * |------------|-----------|--------------------------------------------------------|
 * | trigger:   | Entry     | All triggers (webhook, chat, schedule, etc.)            |
 * | mcp:       | MCP       | Tools (MCP tool calls)                                  |
 * | table:     | Table     | CRUD operations (database tables)                       |
 * | agent:     | AI        | Agent, Guardrail, Classify                              |
 * | core:      | Core      | Loop, Split, Decision, Switch, Merge, Transform, Wait, Fork, Stop, Response, Download File, HTTP Request, Data Input, User Approval |
 * | note:      | Note      | Notes                                                   |
 * | interface: | Interface | Interfaces                                              |
 */
export function computeBackendStepId(nodeId: string, nodeData?: { label?: string; kind?: string; crudOperation?: string }): string {
  if (nodeData?.label) {
    const normalizedLabelValue = normalizeLabel(nodeData.label);

    if (normalizedLabelValue) {
      const kind = nodeData.kind;

      // CRUD/table nodes have kind='action' but crudOperation identifies them as table: prefix
      if (nodeData.crudOperation) return `table:${normalizedLabelValue}`;

      // Lookup prefix from nodeRegistry - single source of truth, zero config needed for new nodes
      if (kind) {
        const prefix = getPrefixForKind(kind);
        if (prefix) return `${prefix}:${normalizedLabelValue}`;
      }

      // Fallback for nodes without kind - derive from nodeId pattern (use startsWith for precision)
      if (nodeId.startsWith('while-') || nodeId.startsWith('while:')) return `core:${normalizedLabelValue}`;
      if (nodeId.startsWith('trigger-') || nodeId.startsWith('trigger:') || nodeId.startsWith('tables-trigger-')) return `trigger:${normalizedLabelValue}`;
      if (nodeId.startsWith('agent-') || nodeId.startsWith('ai-agent-') || nodeId.startsWith('agent:')) return `agent:${normalizedLabelValue}`;
      if (nodeId.startsWith('interface-') || nodeId.startsWith('interface:')) return `interface:${normalizedLabelValue}`;
      if (nodeId.startsWith('create-') || nodeId.startsWith('read-') || nodeId.startsWith('update-') ||
          nodeId.startsWith('delete-') || nodeId.startsWith('find-') || nodeId.startsWith('list-') ||
          nodeId.startsWith('table-')) return `table:${normalizedLabelValue}`;
      return `mcp:${normalizedLabelValue}`;
    }
  }

  return normalizeNodeId(nodeId);
}
