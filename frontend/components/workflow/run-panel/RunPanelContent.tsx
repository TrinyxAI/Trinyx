'use client';

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslations } from 'next-intl';
import { ArrowLeft, History, Loader2, Play } from 'lucide-react';
import { Button } from '@/components/ui/button';
import type { WorkflowRun } from '@/lib/api/orchestrator';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { RunSummaryBar } from './RunSummaryBar';
import { RunStepsPanel } from './RunStepsPanel';
import { RunHistoryList } from './RunHistoryList';
import { isRunStatusActive } from './runFormatting';
import { markEpochPickedByUser, useDefaultEpochSelection } from './useDefaultEpochSelection';
import {
  getCachedRunPanelData,
  requestBindRun,
  requestRunAction,
  subscribeRunPanelData,
  type RunPanelData,
} from './runPanelBus';

export type RunPanelView = 'history' | 'run';

export interface RunPanelContentProps {
  workflowId: string;
  /**
   * Run history is the PARENT level: picking a run drills into its epochs/steps.
   * Disabled where the run is fixed by the surface itself - the marketplace
   * preview (frozen on a published version) and the application panel, which is
   * bound to the run its page opened.
   */
  allowHistory?: boolean;
  /**
   * Level requested from the outside (version chip → history, panel button →
   * run). Carries a sequence number so re-requesting the SAME level - e.g.
   * clicking the history button again after the user navigated away from it -
   * still applies. Must be a stable object identity per request.
   */
  viewRequest?: { view: RunPanelView; seq: number };
}

/**
 * The Run tab of the workflow / application panel.
 *
 * Two levels, one hierarchy:
 *   history (which run?) → run (which epoch? which step?)
 * The run level always shows the run identity bar on top - the same component
 * the canvas pill uses - so the user never loses track of which run the epochs
 * and steps below belong to, and can walk back up with one click.
 */
export function RunPanelContent({ workflowId, allowHistory = false, viewRequest }: RunPanelContentProps) {
  const t = useTranslations();
  const { runId: contextRunId, setRunId, viewingEpoch, setViewingEpoch } = useWorkflowMode();

  const [data, setData] = useState<RunPanelData>(() => getCachedRunPanelData(workflowId));
  useEffect(() => {
    setData(getCachedRunPanelData(workflowId));
    return subscribeRunPanelData(workflowId, setData);
  }, [workflowId]);

  /**
   * Run the user just picked in the history, until the canvas catches up.
   *
   * The canvas is the source of truth for `data.runId`, but rebinding it is a
   * round trip: it recomputes its run from freshly fetched run info, not on the
   * next tick. Preferring the bus during that window bounced the panel straight
   * back to the previous run - and the provider with it, which clears the
   * interface stores on every run change, so the application carousel reset
   * twice for one click.
   */
  const justPickedRunRef = useRef<string | null>(null);
  if (data.runId && data.runId === justPickedRunRef.current) justPickedRunRef.current = null;

  const runId = justPickedRunRef.current ?? data.runId ?? contextRunId ?? null;
  const canBrowseHistory = allowHistory && !data.isPreviewOnly;
  /**
   * A run is bound as soon as an id is known, even before the canvas has
   * published its first snapshot. The two are distinct on purpose: the run
   * level stays open (showing a placeholder) while the details load, instead of
   * bouncing back to the history the moment the tab mounts.
   */
  const hasRun = !!data.runInfo || !!runId;

  const [view, setView] = useState<RunPanelView>(() => {
    if (!canBrowseHistory) return 'run';
    if (viewRequest) return viewRequest.view;
    return hasRun ? 'run' : 'history';
  });

  // A surface that cannot browse history is always on the run level.
  useEffect(() => {
    if (!canBrowseHistory && view !== 'run') setView('run');
  }, [canBrowseHistory, view]);

  // Honour an external "open on this level" request (version chip / history button).
  // Keyed on the request sequence so the same level can be re-requested.
  useEffect(() => {
    if (!viewRequest) return;
    if (!canBrowseHistory && viewRequest.view === 'history') return;
    setView(viewRequest.view);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- react to explicit requests only
  }, [viewRequest?.seq]);

  // With no run at ALL there is nothing to detail - fall back to the history
  // list. Gated on `hasRun`, not on `runInfo`: a run whose snapshot has not
  // arrived yet is still a run, and bouncing to the history there is exactly
  // what made "open the run I just launched" land on the wrong level.
  useEffect(() => {
    if (canBrowseHistory && !hasRun && view === 'run') setView('history');
  }, [canBrowseHistory, hasRun, view]);

  /**
   * Tell THIS panel's WorkflowModeProvider which run it is showing.
   *
   * The side panel mounts its own provider, and the workflow-panel tab does not
   * name a run when it does - the run arrives later, over the bus. Until the
   * provider knows it, its `viewingEpoch` broadcast carries `runId: null`, which
   * `shouldAdoptEpochEvent` treats as "for everyone": picking an epoch here
   * dragged every other mounted canvas (a sub-workflow tab, an application tab)
   * to the same epoch. Scoping is the whole point of that event's runId.
   */
  useEffect(() => {
    // Never while a freshly picked run is waiting for the canvas: `data.runId` is
    // still the PREVIOUS run then, and adopting it would undo the pick.
    if (justPickedRunRef.current) return;
    if (data.runId && data.runId !== contextRunId) setRunId(data.runId);
  }, [data.runId, contextRunId, setRunId]);

  const handleSelectEpoch = useCallback((epoch: number | null) => {
    // Remember the choice for this run so the other surface, and this panel
    // after it is closed and reopened, show the same thing. `null` (All epochs)
    // is a real choice, not an absence of one.
    markEpochPickedByUser(runId, epoch);
    setViewingEpoch(epoch);
  }, [runId, setViewingEpoch]);

  /**
   * The panel opens on the same view as the canvas beside it: all epochs, unless
   * the user picked one. Nothing is selected here either - this only restores a
   * pick made on the other surface, since the panel body does not exist while
   * the side panel is closed and `viewingEpoch` dies with its provider.
   *
   * Gated on the provider having ADOPTED this run (`contextRunId === runId`),
   * not merely on knowing it: `setRunId` above is a state update, so restoring in
   * the same commit would broadcast the epoch with `runId: null`, which every
   * other mounted provider adopts - the cross-talk this panel's scoping exists
   * to prevent, on the very first selection.
   */
  useDefaultEpochSelection({
    runId,
    selectedEpoch: viewingEpoch,
    onSelectEpoch: setViewingEpoch,
    enabled: hasRun && !!runId && contextRunId === runId,
  });

  const handleSelectRun = useCallback((run: WorkflowRun) => {
    const nextRunId = run.runId || run.id;
    if (!nextRunId) return;
    // Bind the panel immediately so the run level renders without waiting for
    // the canvas to publish its own snapshot, and remember the pick so the
    // still-stale bus snapshot cannot pull us back to the previous run...
    justPickedRunRef.current = nextRunId;
    setRunId(nextRunId);
    setView('run');
    // ...then ask the page to rebind the canvas IN PLACE. No router.push: that
    // remounted the whole route and read as a full-page refresh just to look at
    // another run. The page also realigns the address bar (it owns the route;
    // this panel is mounted on surfaces whose URL says nothing about a run), so
    // a reload comes back on the run that was picked here.
    //
    // Sent even for the run already on screen: an agent-launched run is bound
    // in place on the edit URL, and picking it here is how a user says "keep
    // this one". The page no-ops when there is nothing left to align.
    requestBindRun({ workflowId, runId: nextRunId });
  }, [workflowId, setRunId]);

  const runAction = useCallback((action: 'stop' | 'cancel' | 'reactivate') => {
    requestRunAction({ action, workflowId, runId });
  }, [workflowId, runId]);

  const isRunActive = useMemo(() => isRunStatusActive(data.runInfo?.status), [data.runInfo?.status]);

  // ── History level ──
  if (view === 'history') {
    return (
      <div className="flex-1 min-h-0 flex flex-col">
        <RunHistoryList
          workflowId={workflowId}
          currentRunId={runId}
          onSelectRun={handleSelectRun}
        />
      </div>
    );
  }

  // ── Run level ──
  if (!data.runInfo) {
    // A run id without a snapshot = the canvas is still attaching to it (the
    // usual case right after pressing Run). Say "loading", not "no runs".
    return (
      <div className="flex-1 min-h-0 flex flex-col items-center justify-center gap-2 p-6 text-center" data-run-panel-detail>
        {runId ? (
          <>
            <Loader2 className="w-8 h-8 text-theme-secondary opacity-40 animate-spin" />
            <p className="text-sm text-theme-secondary">{t('runs.loading')}</p>
          </>
        ) : (
          <>
            <Play className="w-10 h-10 text-theme-secondary opacity-40" />
            <p className="text-sm text-theme-secondary">{t('runs.noRuns')}</p>
          </>
        )}
        {canBrowseHistory && (
          <Button variant="outline" size="sm" onClick={() => setView('history')}>
            <History className="w-3.5 h-3.5 mr-1.5" />
            {t('runs.title')}
          </Button>
        )}
      </div>
    );
  }

  return (
    <div className="flex-1 min-h-0 flex flex-col" data-run-panel-detail>
      <RunSummaryBar
        currentRunInfo={data.runInfo}
        pinnedVersion={data.pinnedVersion}
        currentEpoch={data.currentEpoch}
        selectedEpoch={viewingEpoch}
        isStepByStep={data.isStepByStep}
        onStop={data.isPreviewOnly ? undefined : () => runAction('stop')}
        onCancel={data.isPreviewOnly ? undefined : () => runAction('cancel')}
        onReactivate={data.isPreviewOnly ? undefined : () => runAction('reactivate')}
        onVersionClick={canBrowseHistory ? () => setView('history') : undefined}
        size="panel"
        className="border-b border-theme"
        leading={canBrowseHistory ? (
          /* Arrow + history icon: the arrow alone says "back", the icon says
             back TO WHAT - the list of runs this one was picked from. */
          <button
            type="button"
            data-run-panel-back
            onClick={() => setView('history')}
            title={t('runs.title')}
            className="flex items-center gap-0.5 h-5 pl-1 pr-1.5 rounded-full text-theme-secondary hover:bg-theme-secondary hover:text-theme-primary transition-colors flex-shrink-0"
          >
            <ArrowLeft className="w-3 h-3" />
            <History className="w-3.5 h-3.5" />
          </button>
        ) : undefined}
      />

      <RunStepsPanel
        currentRunInfo={data.runInfo}
        streamedSteps={data.streamedSteps}
        epochTimestamps={data.epochTimestamps}
        selectedEpoch={viewingEpoch}
        onSelectEpoch={handleSelectEpoch}
        workflowId={workflowId}
        isStepByStep={data.isStepByStep}
        isRunActive={isRunActive}
      />
    </div>
  );
}
