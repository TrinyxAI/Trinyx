'use client';

import React, { useState, useCallback, useRef, useEffect } from 'react';
import { useTranslations } from 'next-intl';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '@/app/workflows/builder/types';
import type { AgentSnapshotConfig } from '@/app/workflows/builder/types/agentSnapshot';
import {
  WorkflowBuilder,
  type TriggerDataForPanel,
  type RunInfoData,
} from '@/app/workflows/builder/components/WorkflowBuilder';
import type { ApplicationConfig } from '@/components/chat/ApplicationTabContent';
import { WorkflowModeToggle } from '@/components/workflow/WorkflowModeToggle';
import { orchestratorApi } from '@/lib/api';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { useWorkflowRunContext } from '@/contexts/WorkflowRunContext';
import { useWorkflowEventBridge } from '@/components/views/workflow/hooks';
import { streamDebug } from '@/contexts/workflow-run/streamingDebug';
import {
  makeEmptyRunPanelData,
  publishRunPanelData,
  RUN_PANEL_ACTION_EVENT,
  type RunPanelActionDetail,
} from '@/components/workflow/run-panel/runPanelBus';
import { useDefaultEpochSelection } from '@/components/workflow/run-panel/useDefaultEpochSelection';

// ============================================
// Types
// ============================================

/** Data forwarded to parent when run info changes. */
export interface RunInfoChangeData {
  runInfo: any | null;
  isStepByStep: boolean;
  currentEpoch: number;
  epochTimestamps: Array<{ epoch: number; startedAt: string; endedAt: string | null }>;
  streamedSteps?: any[];
}

export interface WorkflowRunCanvasProps {
  workflowId: string;
  runId?: string;
  /** Hide the edit/run mode toggle (application mode: always in run) */
  hideToggle?: boolean;
  /** Publication snapshot plan to use instead of fetching */
  planOverride?: any;

  // Edit mode pass-through to WorkflowBuilder
  onDirtyChange?: (isDirty: boolean) => void;
  onRefreshBlocked?: () => void;
  saveRef?: React.MutableRefObject<(() => Promise<void>) | null>;

  // Lifecycle callbacks to parent
  onWorkflowLoaded?: (info: { name?: string; id?: string }) => void;
  onRunInfoChange?: (data: RunInfoChangeData) => void;
  onTriggerConfigsChange?: (data: TriggerDataForPanel | null) => void;
  onApplicationConfigsChange?: (configs: ApplicationConfig[]) => void;
  onAgentConfigsChange?: (configs: AgentSnapshotConfig[]) => void;
  onHasChatFormTrigger?: (has: boolean) => void;

  // Refs (parent may need access)
  executeTriggerRef?: React.MutableRefObject<((triggerId: string, triggerType: 'chat' | 'form' | 'webhook', payload: Record<string, any>) => Promise<string[] | undefined>) | null>;
  applicationActionRef?: React.MutableRefObject<((triggerRef: string, data: Record<string, unknown>) => Promise<void>) | null>;
  nodesRef?: React.MutableRefObject<Node<BuilderNodeData>[]>;
}

// ============================================
// Component
// ============================================

/**
 * WorkflowRunCanvas - Shared component encapsulating WorkflowModeToggle + WorkflowBuilder + run state.
 * Used by both WorkflowDetailView and ApplicationDetailView to avoid duplicating
 * run state management, event bridges, and toggle/builder rendering.
 */
export function WorkflowRunCanvas({
  workflowId,
  runId,
  hideToggle = false,
  planOverride,
  onDirtyChange,
  onRefreshBlocked,
  saveRef,
  onWorkflowLoaded,
  onRunInfoChange,
  onTriggerConfigsChange,
  onApplicationConfigsChange,
  onAgentConfigsChange,
  onHasChatFormTrigger,
  executeTriggerRef: externalExecuteTriggerRef,
  applicationActionRef: externalApplicationActionRef,
  nodesRef: externalNodesRef,
}: WorkflowRunCanvasProps) {
  const t = useTranslations('common');
  const { mode: workflowMode, isPreviewOnly, viewingEpoch, setViewingEpoch, runId: contextRunId } = useWorkflowMode();
  const runContext = useWorkflowRunContext();

  // ── Diagnostic: confirm whether this component renders on the marketplace
  // preview route. If MOUNT log fires but no WorkflowBuilder MOUNT follows,
  // the gap is between RunCanvas and Builder (rare). If MOUNT never fires,
  // the gap is upstream (WorkflowPanelContent slot, or SidePanel keepMounted). ──
  useEffect(() => {
    console.log('[AppDebug] WorkflowRunCanvas MOUNT', { workflowId, runId, isPreviewOnly });
    return () => console.log('[AppDebug] WorkflowRunCanvas UNMOUNT', { workflowId });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ── Internal run state ──
  const [currentRunInfo, setCurrentRunInfo] = useState<any | null>(null);
  const [isStepByStep, setIsStepByStep] = useState(false);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [currentEpoch, setCurrentEpoch] = useState(0);
  const [epochTimestamps, setEpochTimestamps] = useState<Array<{ epoch: number; startedAt: string; endedAt: string | null }>>([]);
  const [streamedSteps, setStreamedSteps] = useState<any[] | undefined>(undefined);
  const [pinnedVersion, setPinnedVersion] = useState<number | null>(null);

  // ── Fetch pinnedVersion when entering run mode (drives the pin badge) ──
  useEffect(() => {
    if (workflowMode !== 'run' || !workflowId) return;
    orchestratorApi.listVersions(workflowId)
      .then((vData) => setPinnedVersion(vData.pinnedVersion ?? null))
      .catch(() => {});
  }, [workflowMode, workflowId]);

  // ── Listen for pin/unpin changes from WorkflowVersionHistory ──
  useEffect(() => {
    const handler = (e: Event) => {
      setPinnedVersion((e as CustomEvent).detail?.pinnedVersion ?? null);
    };
    window.addEventListener('workflowPinnedVersionChange', handler);
    return () => window.removeEventListener('workflowPinnedVersionChange', handler);
  }, []);

  // ── Internal refs (use external if provided) ──
  const internalExecuteTriggerRef = useRef<((triggerId: string, triggerType: 'chat' | 'form' | 'webhook', payload: Record<string, any>) => Promise<string[] | undefined>) | null>(null);
  const internalApplicationActionRef = useRef<((triggerRef: string, data: Record<string, unknown>) => Promise<void>) | null>(null);
  const internalNodesRef = useRef<Node<BuilderNodeData>[]>([]);

  const executeTriggerRef = externalExecuteTriggerRef ?? internalExecuteTriggerRef;
  const applicationActionRef = externalApplicationActionRef ?? internalApplicationActionRef;
  const canvasNodesRef = externalNodesRef ?? internalNodesRef;

  // ── Shared event bridge (trigger exec, application actions, __continue) ──
  useWorkflowEventBridge(executeTriggerRef, applicationActionRef, runContext);

  // ── Handle run info changes from WorkflowBuilder ──
  const handleRunInfoChange = useCallback((data: RunInfoData) => {
    setCurrentRunInfo(data.runInfo);
    setIsStepByStep(data.isStepByStep);
    setCurrentEpoch(data.currentEpoch);
    setEpochTimestamps(data.epochTimestamps);
    setStreamedSteps((prev) => {
      const next = data.streamedSteps;
      if (prev !== next) {
        streamDebug.log('WorkflowRunCanvas', 'streamedSteps CHANGED:', {
          prevLength: prev?.length ?? 0,
          nextLength: next?.length ?? 0,
          nextSteps: next?.map((s: any) => `${s.alias}:c=${s.statusCounts?.completed},f=${s.statusCounts?.failed},s=${s.statusCounts?.skipped}`),
        });
      }
      return next;
    });

    // Forward to parent
    onRunInfoChange?.({
      runInfo: data.runInfo,
      isStepByStep: data.isStepByStep,
      currentEpoch: data.currentEpoch,
      epochTimestamps: data.epochTimestamps,
      streamedSteps: data.streamedSteps,
    });
  }, [onRunInfoChange]);

  // ── Handle graceful stop (RUNNING/PAUSED → WAITING_TRIGGER) ──
  const handleStopRun = useCallback(async () => {
    const activeRunId = currentRunInfo?.runId || currentRunInfo?.id || runId;
    if (!activeRunId || !runContext) return;
    try {
      await runContext.cancelRun(activeRunId);
    } catch (err) {
      console.error('[WorkflowRunCanvas] Failed to stop workflow:', err);
      window.dispatchEvent(new CustomEvent('workflowToast', {
        detail: { type: 'error', message: 'Failed to stop workflow' },
      }));
    }
  }, [currentRunInfo, runId, runContext]);

  // ── Handle hard cancel (WAITING_TRIGGER → terminal CANCELLED) ──
  const handleCancelRun = useCallback(async () => {
    const activeRunId = currentRunInfo?.runId || currentRunInfo?.id || runId;
    if (!activeRunId || !runContext) return;
    try {
      await runContext.hardCancelRun(activeRunId);
    } catch (err) {
      console.error('[WorkflowRunCanvas] Failed to cancel workflow:', err);
      window.dispatchEvent(new CustomEvent('workflowToast', {
        detail: { type: 'error', message: 'Failed to cancel workflow' },
      }));
    }
  }, [currentRunInfo, runId, runContext]);

  // ── Handle reactivate (CANCELLED → WAITING_TRIGGER) ──
  const handleReactivateRun = useCallback(async () => {
    const activeRunId = currentRunInfo?.runId || currentRunInfo?.id || runId;
    if (!activeRunId || !runContext) return;
    try {
      await runContext.reactivateRun(activeRunId);
    } catch (err) {
      console.error('[WorkflowRunCanvas] Failed to reactivate workflow:', err);
      window.dispatchEvent(new CustomEvent('workflowToast', {
        detail: { type: 'error', message: 'Failed to reactivate workflow' },
      }));
    }
  }, [currentRunInfo, runId, runContext]);

  // ── The run the surfaces are bound to (URL run, in-place run, or the one the
  // run info itself reports) ──
  const activeRunId = currentRunInfo?.runId || currentRunInfo?.id || runId || contextRunId || null;

  // ── Publish the run snapshot to the side-panel Run tab ──
  // The panel lives in the app-layout tree (no shared provider), so the state
  // travels through the run-panel bus, which also caches it for a panel that
  // mounts later. Mirrors how trigger/application configs already reach it.
  useEffect(() => {
    if (!workflowId) return;
    publishRunPanelData({
      workflowId,
      runId: activeRunId,
      runInfo: currentRunInfo,
      isStepByStep,
      currentEpoch,
      epochTimestamps,
      streamedSteps,
      pinnedVersion,
      isPreviewOnly,
    });
  }, [workflowId, activeRunId, currentRunInfo, isStepByStep, currentEpoch, epochTimestamps, streamedSteps, pinnedVersion, isPreviewOnly]);

  // Drop the cached snapshot when this canvas goes away. The cache exists so a
  // panel mounting LATER is not empty; kept past the canvas's life it answers the
  // next visit to the same workflow with the previous run, and the Run tab and the
  // application carousel render that stale run for a frame before the new canvas
  // publishes.
  useEffect(() => {
    if (!workflowId) return;
    return () => { publishRunPanelData(makeEmptyRunPanelData(workflowId)); };
  }, [workflowId]);

  // ── Run actions requested from the panel (it cannot call these handlers) ──
  useEffect(() => {
    const handler = (event: Event) => {
      const detail = (event as CustomEvent<RunPanelActionDetail>).detail;
      if (!detail) return;
      if (detail.workflowId && workflowId && detail.workflowId !== workflowId) return;
      if (isPreviewOnly) return;
      if (detail.action === 'stop') handleStopRun();
      else if (detail.action === 'cancel') handleCancelRun();
      else if (detail.action === 'reactivate') handleReactivateRun();
    };
    window.addEventListener(RUN_PANEL_ACTION_EVENT, handler);
    return () => window.removeEventListener(RUN_PANEL_ACTION_EVENT, handler);
  }, [workflowId, isPreviewOnly, handleStopRun, handleCancelRun, handleReactivateRun]);

  // ── A run is always read through an epoch: seed the selection with the newest
  // one and keep following new epochs until the user picks one explicitly ──
  //
  // This deliberately changes the canvas default from "all epochs" (aggregate)
  // to "the latest epoch". Node badges therefore show that epoch's status rather
  // than the cumulative counts, and the run panel reads its steps per epoch. That
  // is the intent: a run is a sequence of fires, and "which fire am I looking at"
  // should never be implicit. "All epochs" stays one click away in the selector.
  useDefaultEpochSelection({
    runId: activeRunId,
    epochTimestamps,
    selectedEpoch: viewingEpoch,
    onSelectEpoch: setViewingEpoch,
    enabled: workflowMode === 'run',
  });

  return (
    <>
      {/* Workflow Mode Toggle (stop button hidden in preview) */}
      <WorkflowModeToggle
        mode={workflowMode}
        workflowId={workflowId}
        hideToggle={hideToggle}
        showReadOnlyBadge={isPreviewOnly}
        currentRunInfo={currentRunInfo}
        isStepByStep={isStepByStep}
        onStop={isPreviewOnly ? undefined : handleStopRun}
        onCancel={isPreviewOnly || hideToggle ? undefined : handleCancelRun}
        onReactivate={isPreviewOnly ? undefined : handleReactivateRun}
        currentEpoch={currentEpoch}
        epochTimestamps={epochTimestamps}
        pinnedVersion={pinnedVersion}
        isSettingsOpen={isSettingsOpen}
      />

      <WorkflowBuilder
        workflowId={workflowId}
        runId={runId}
        planOverride={planOverride}
        onDirtyChange={onDirtyChange}
        onRefreshBlocked={onRefreshBlocked}
        onWorkflowLoaded={onWorkflowLoaded}
        onRunInfoChange={handleRunInfoChange}
        onTriggerConfigsChange={onTriggerConfigsChange}
        onApplicationConfigsChange={onApplicationConfigsChange}
        onAgentConfigsChange={onAgentConfigsChange}
        onHasChatFormTrigger={onHasChatFormTrigger}
        executeTriggerRef={executeTriggerRef}
        applicationActionRef={applicationActionRef}
        saveRef={saveRef}
        nodesRef={canvasNodesRef}
        onSettingsOpenChange={setIsSettingsOpen}
      />
    </>
  );
}
