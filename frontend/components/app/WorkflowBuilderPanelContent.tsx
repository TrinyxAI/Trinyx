'use client';

/**
 * WorkflowBuilderPanelContent - Mounts a workflow inside the SidePanel with
 * proper sub-tabs (AI Chat, Workflow canvas, Triggers, Application).
 *
 * Follows the same composition pattern as ApplicationDetailView:
 * WorkflowPanelContent receives the canvas as a `workflowCanvasSlot`,
 * so the user gets full sub-tab navigation instead of the old overlay.
 */

import React, { useCallback, useState, useEffect, useId, useRef } from 'react';
import { Table, Bot, Workflow } from 'lucide-react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '@/app/workflows/builder/types';
import type { TriggerDataForPanel } from '@/app/workflows/builder/components/WorkflowBuilder';
import type { ApplicationConfig } from '@/components/chat/ApplicationTabContent';
import type { AgentSnapshotConfig } from '@/app/workflows/builder/types/agentSnapshot';
import { WorkflowRunCanvas } from '@/components/workflow/WorkflowRunCanvas';
import { WorkflowPanelContent } from '@/components/app/WorkflowPanelContent';
import { WorkflowModeProvider, useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { WorkflowRunProvider } from '@/contexts/WorkflowRunContext';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { orchestratorApi } from '@/lib/api';
import { useOrgScopedReset } from '@/lib/hooks/useOrgScopedReset';
import { subscribeBindRun } from '@/components/workflow/run-panel/runPanelBus';

interface WorkflowBuilderPanelContentProps {
  workflowId: string;
  runId?: string;
  readOnly?: boolean;
}

/**
 * Binds the run picked in this tab's Run tab into the canvas' own
 * WorkflowModeProvider, one push per PICK - never as a running comparison
 * against whatever the canvas currently holds.
 *
 * The canvas writes to that same provider on its own: the Edit toggle clears the
 * run (an embedded canvas swaps mode in place instead of navigating), the Run
 * toggle rebinds the latest run, an agent-launched run binds in place. An effect
 * that pushed "whenever the two differ" fought all three - clicking Edit put the
 * run straight back, so the toggle did nothing. Reacting to the pick EVENT
 * instead leaves the canvas in charge between picks, and still honours picking
 * the same run again (which is how the user says "back to that one" after the
 * toggle moved the canvas elsewhere).
 */
function BindCanvasToRun({
  workflowId,
  surfaceId,
  onCanvasRunChange,
}: {
  workflowId: string;
  surfaceId: string;
  onCanvasRunChange: (runId: string | undefined) => void;
}) {
  const { runId: canvasRunId, setRunId } = useWorkflowMode();
  useEffect(
    () => subscribeBindRun(workflowId, (pickedRunId) => setRunId(pickedRunId), surfaceId),
    [workflowId, surfaceId, setRunId],
  );
  // ...and report back, so the rest of the tab follows the canvas wherever the
  // canvas goes on its own. Without this the Application tab kept rendering
  // against the run picked in the history after the Edit toggle had already
  // taken the canvas out of run mode.
  useEffect(() => {
    onCanvasRunChange(canvasRunId ?? undefined);
  }, [canvasRunId, onCanvasRunChange]);
  return null;
}

export function WorkflowBuilderPanelContent({ workflowId, runId, readOnly = false }: WorkflowBuilderPanelContentProps) {
  const sidePanel = useSidePanelSafe();
  const canvasNodesRef = useRef<Node<BuilderNodeData>[]>([]);
  /**
   * This tab's identity on the run bus. Two surfaces can show the SAME workflow
   * at once (a self-referencing sub-workflow node, or opening the current
   * workflow from the tab picker), so a pick has to name the surface as well as
   * the workflow - otherwise picking a run here would rewrite the page's URL and
   * move the canvas behind this panel.
   */
  const surfaceId = useId();

  const [triggerData, setTriggerData] = useState<TriggerDataForPanel | null>(null);
  const [applicationConfigs, setApplicationConfigs] = useState<ApplicationConfig[]>([]);
  const [agentConfigs, setAgentConfigs] = useState<AgentSnapshotConfig[]>([]);

  /**
   * Run this tab currently shows - the one it was opened on, until the user picks
   * another in the Run tab's history.
   *
   * The tab is opened on ONE run (the sub-workflow's pinned production run), and
   * that used to be the end of it: the panel had no history to walk back up to,
   * so its run detail was a dead end. Now that it does, the pick has to reach the
   * canvas - which lives under this component's OWN WorkflowModeProvider, not the
   * page's - so we adopt the same bind event the workflow page listens to, and
   * mirror the canvas back (see BindCanvasToRun) so the rest of the tab follows
   * it wherever its own toggles take it.
   */
  const [boundRunId, setBoundRunId] = useState<string | undefined>(runId);
  // A pick is NOT adopted here: it goes to the canvas (BindCanvasToRun owns the
  // bus subscription for this surface) and comes back through this mirror. One
  // direction, one subscriber - so the run THIS component passes down follows
  // the canvas, whether it moved by a pick or by its own mode toggle, instead of
  // leaving the Application tab rendered against a run the canvas has left.
  const handleCanvasRunChange = useCallback((canvasRunId: string | undefined) => {
    setBoundRunId(canvasRunId);
  }, []);

  /**
   * Run this tab is ANCHORED on - what its providers are seeded with, as opposed
   * to `boundRunId`, which is wherever the canvas has since gone.
   *
   * It moves for exactly two reasons, and both are a genuine reset rather than a
   * navigation inside the tab: the tab being re-opened on another run (openTab
   * replaces the content), and a workspace switch (below). Adjusted during
   * render, not in an effect, so the providers are never seeded with the
   * previous anchor for a frame first.
   */
  const [seedRunId, setSeedRunId] = useState<string | undefined>(runId);
  const [openedOnRunId, setOpenedOnRunId] = useState<string | undefined>(runId);
  if (openedOnRunId !== runId) {
    setOpenedOnRunId(runId);
    setSeedRunId(runId);
    setBoundRunId(runId);
  }

  /**
   * Bumped on every workspace switch, and part of both providers' keys.
   *
   * This tab is keepMounted, so a workspace switch leaves it rendering a canvas
   * still holding whatever run it had drifted to - and clearing this component's
   * own state does not reach it: BOTH providers hold a run of their own (the
   * canvas binds one in place, and most call sites open this tab with no run at
   * all), and theirs outranks the props. Re-keying is what actually drops them.
   *
   * The tab is rebuilt on its own ANCHOR rather than emptied. Dropping the run
   * as well is tempting (it belongs to the workspace we left) but it latches:
   * re-adoption is driven by the prop changing, and re-opening the tab on the
   * same run passes byte-identical props, so the tab would stay stuck in edit
   * mode for the life of the panel. Rebuilding is self-consistent instead: back
   * in the original workspace it is simply correct, and elsewhere the canvas
   * surfaces the failed fetch rather than silently showing nothing.
   */
  const [orgEpoch, setOrgEpoch] = useState(0);
  useOrgScopedReset(() => {
    setTriggerData(null);
    setApplicationConfigs([]);
    setAgentConfigs([]);
    // `boundRunId` is not touched: bumping the epoch re-keys both providers, so
    // BindCanvasToRun remounts and re-reports the anchor through the mirror.
    setOrgEpoch((n) => n + 1);
  });

  // ── Dispatch trigger data / applicationConfigs / agentConfigs to WorkflowPanelContent ──
  //
  // We forward the canvas's emitted configs AS-IS, NOT gated on the `runId` prop.
  // WorkflowBuilder already gates these on its own run mode (`if (!isRunMode)
  // return []` for applicationConfigs / waiting-trigger configs), so a non-empty
  // emission means the canvas IS in run mode. Gating again on the `runId` prop
  // suppressed the trigger + application sub-tabs whenever the panel was opened
  // WITHOUT a runId (the `+`-menu and chat workflow events both open in edit
  // mode) and the canvas later entered run mode IN PLACE - the run showed on the
  // canvas but the Triggers/Application tabs never appeared. Driving the dispatch
  // off the live canvas state fixes that; edit mode still emits empty → no tabs.
  useEffect(() => {
    window.dispatchEvent(new CustomEvent('workflowPanelTriggerDataChange', {
      detail: {
        workflowId,
        configs: triggerData?.configs ?? [],
        activeTriggerId: triggerData?.activeTriggerId,
        readySteps: triggerData?.readySteps ?? new Set(),
        runStatus: triggerData?.runStatus,
        isStepByStepMode: triggerData?.isStepByStepMode,
      },
    }));
  }, [triggerData, workflowId]);

  useEffect(() => {
    window.dispatchEvent(new CustomEvent('workflowPanelApplicationConfigsChange', {
      detail: { workflowId, configs: applicationConfigs },
    }));
  }, [applicationConfigs, workflowId]);

  useEffect(() => {
    window.dispatchEvent(new CustomEvent('workflowPanelAgentConfigsChange', {
      detail: { workflowId, configs: agentConfigs },
    }));
  }, [agentConfigs, workflowId]);

  // ── Listen for datasource tab open requests ──
  useEffect(() => {
    const handler = (event: CustomEvent<{ dataSourceId: string; label: string }>) => {
      const { dataSourceId, label } = event.detail;
      if (!sidePanel || !dataSourceId) return;
      import('@/components/app/DataSourcePanelContent').then(({ DataSourcePanelContent }) => {
        sidePanel.openTab({
          id: `datasource-${dataSourceId}`,
          label,
          icon: React.createElement(Table, { className: 'w-4 h-4' }),
          content: React.createElement(DataSourcePanelContent, { dataSourceId, readOnly }),
          preferredWidth: 0.35,
        });
      });
    };
    window.addEventListener('workflowOpenDatasourceTab', handler as EventListener);
    return () => window.removeEventListener('workflowOpenDatasourceTab', handler as EventListener);
  }, [sidePanel, readOnly]);

  // ── Listen for agent tab open requests ──
  useEffect(() => {
    const handler = (event: CustomEvent<{ agentId: string; label: string; conversationId?: string }>) => {
      const { agentId, label, conversationId: triggerConvId } = event.detail;
      if (!sidePanel || !agentId) return;
      import('@/components/app/AgentPanelContent').then(({ AgentPanelContent }) => {
        sidePanel.openTab({
          id: `agent-${agentId}`,
          label,
          icon: React.createElement(Bot, { className: 'w-4 h-4' }),
          content: React.createElement(AgentPanelContent, { agentId, conversationId: triggerConvId, readOnly }),
          preferredWidth: 0.35,
        });
      });
    };
    window.addEventListener('workflowOpenAgentTab', handler as EventListener);
    return () => window.removeEventListener('workflowOpenAgentTab', handler as EventListener);
  }, [sidePanel, readOnly]);

  // ── Listen for sub-workflow open requests ──
  useEffect(() => {
    const handler = async (event: CustomEvent<{ workflowId: string; workflowName: string; nodeId: string }>) => {
      const { workflowId: subWfId, workflowName: wfName } = event.detail;
      if (!sidePanel || !subWfId) return;

      let pinnedRunId: string | undefined;
      try {
        const pinnedRun = await orchestratorApi.getPinnedWorkflowRun(subWfId);
        if (pinnedRun?.runId) pinnedRunId = pinnedRun.runId;
      } catch { /* fall back to builder */ }

      if (pinnedRunId) {
        sidePanel.openTab({
          id: `workflow-run-${subWfId}-${pinnedRunId}`,
          label: wfName,
          icon: React.createElement(Workflow, { className: 'w-4 h-4' }),
          content: React.createElement(WorkflowBuilderPanelContent, { workflowId: subWfId, runId: pinnedRunId, readOnly }),
          preferredWidth: 0.5,
          keepMounted: true,
        });
      } else {
        sidePanel.openTab({
          id: `workflow-builder-${subWfId}`,
          label: wfName,
          icon: React.createElement(Workflow, { className: 'w-4 h-4' }),
          content: React.createElement(WorkflowBuilderPanelContent, { workflowId: subWfId, readOnly }),
          preferredWidth: 0.5,
          keepMounted: true,
        });
      }
    };
    window.addEventListener('workflowOpenSubWorkflow', handler as EventListener);
    return () => window.removeEventListener('workflowOpenSubWorkflow', handler as EventListener);
  }, [sidePanel, readOnly]);

  return (
    <WorkflowModeProvider
      /* Keyed on the ANCHOR, not on the run being shown: a pick must not rebuild
         anything (that is the visible "refresh" binding in place exists to
         avoid), while re-opening the tab on another run and switching workspace
         must rebuild everything. This provider holds a run of its own too -
         RunPanelContent writes the picked run into it - so it needs the same
         key as the canvas one. */
      key={`${seedRunId ?? 'edit'}-org-${orgEpoch}`}
      workflowId={workflowId}
      readOnly={readOnly}
      initialRunId={seedRunId}
    >
      <WorkflowRunProvider>
        <div className="relative w-full h-full overflow-hidden">
          <WorkflowPanelContent
            workflowId={workflowId}
            /* Fall back to the canvas's in-place run id (reported via triggerData)
               so the Application interface renders against the live run even when
               the panel was opened without a runId (the + menu / chat-event path,
               where the URL has no /run/<id>). Without this the interface tab shows
               "No template configured" because it has no run to render against. */
            runId={boundRunId ?? triggerData?.runId}
            /* This tab CAN change the run it shows (see boundRunId), so its Run
               tab keeps the back arrow into the run history. The default would
               deny it, like every other canvas embedded in a host that picked the
               run for it. */
            allowRunHistory
            runSurfaceId={surfaceId}
            workflowCanvasSlot={
              <WorkflowModeProvider key={`${seedRunId ?? 'edit'}-org-${orgEpoch}`} workflowId={workflowId} initialRunId={seedRunId} readOnly={readOnly}>
                {/* `initialRunId` only SEEDS the provider, and the provider's run
                    id outranks the canvas prop downstream, so a pick made in the
                    history has to be pushed into it. Re-keying per PICK would do
                    that too, but by destroying the canvas - the visible "refresh"
                    that binding a run in place exists to avoid. */}
                <BindCanvasToRun workflowId={workflowId} surfaceId={surfaceId} onCanvasRunChange={handleCanvasRunChange} />
                <div className="h-full w-full relative overflow-x-auto">
                  <WorkflowRunCanvas
                    workflowId={workflowId}
                    runId={boundRunId}
                    onTriggerConfigsChange={setTriggerData}
                    onApplicationConfigsChange={setApplicationConfigs}
                    onAgentConfigsChange={setAgentConfigs}
                    nodesRef={canvasNodesRef}
                  />
                </div>
              </WorkflowModeProvider>
            }
          />
        </div>
      </WorkflowRunProvider>
    </WorkflowModeProvider>
  );
}
