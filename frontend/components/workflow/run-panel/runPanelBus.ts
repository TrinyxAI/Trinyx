'use client';

/**
 * Cross-tree bridge for the side-panel Run tab.
 *
 * The run state (run info, streamed steps, epochs, pinned version) is owned by
 * `WorkflowRunCanvas`, which lives in the page tree. The Run tab lives in the
 * SidePanel, mounted from the app layout - a different React tree with no common
 * provider. Same situation as the trigger / application configs, so we reuse the
 * exact same pattern those already use: a window CustomEvent plus a module-level
 * per-workflow cache so a panel that mounts late (or remounts when the user
 * reopens the panel) immediately has the latest snapshot instead of an empty one.
 *
 * Actions travel the other way through {@link requestRunAction}: the panel cannot
 * call the canvas' stop/cancel/reactivate handlers directly, so it names the
 * action and the canvas performs it.
 */

import type { EpochTimestamp, StepEntry } from './runFormatting';

export const RUN_PANEL_DATA_EVENT = 'workflowPanelRunDataChange' as const;
export const RUN_PANEL_ACTION_EVENT = 'workflowRunAction' as const;
/** Focus the Run tab of the workflow/application panel (and open the panel). */
export const OPEN_RUN_PANEL_EVENT = 'workflowOpenRunPanel' as const;
/** Focus the Add Node tab of the workflow panel (and open the panel). */
export const OPEN_NODE_CREATOR_EVENT = 'workflowOpenNodeCreator' as const;
/** Bind the canvas to another run of the same workflow, IN PLACE (no navigation). */
export const BIND_RUN_EVENT = 'workflowBindRun' as const;

export interface RunPanelData {
  workflowId: string;
  /** Run currently bound to the canvas (null in edit mode). */
  runId: string | null;
  runInfo: any | null;
  isStepByStep: boolean;
  currentEpoch: number;
  epochTimestamps: EpochTimestamp[];
  /** undefined = steps not streamed yet (loading), [] = streamed and empty. */
  streamedSteps?: StepEntry[];
  pinnedVersion: number | null;
  /** Marketplace preview: the run is frozen, no history navigation. */
  isPreviewOnly: boolean;
}

export type RunPanelAction = 'stop' | 'cancel' | 'reactivate';

export interface RunPanelActionDetail {
  action: RunPanelAction;
  workflowId?: string;
  runId?: string | null;
}

export interface OpenRunPanelDetail {
  workflowId?: string;
  /** Which level of the Run tab to land on. Defaults to the run detail. */
  view?: 'history' | 'run';
}

export interface RunPanelViewRequest {
  workflowId?: string;
  view: 'history' | 'run';
  /** Increments per request so re-asking for the SAME level still applies. */
  seq: number;
}

export function makeEmptyRunPanelData(workflowId: string): RunPanelData {
  return {
    workflowId,
    runId: null,
    runInfo: null,
    isStepByStep: false,
    currentEpoch: 0,
    epochTimestamps: [],
    streamedSteps: undefined,
    pinnedVersion: null,
    isPreviewOnly: false,
  };
}

const cacheByWorkflow = new Map<string, RunPanelData>();

/** Latest snapshot for a workflow (empty defaults when nothing was published yet). */
export function getCachedRunPanelData(workflowId: string): RunPanelData {
  return cacheByWorkflow.get(workflowId) ?? makeEmptyRunPanelData(workflowId);
}

/**
 * The run the canvas of this workflow is bound to.
 *
 * This is the id every run surface keys its per-run state off (the epoch the
 * user picked, above all), and the canvas resolves it from more than the
 * provider knows: the run info it fetched, then the URL run, then the in-place
 * one. A component deep in the canvas that only has the provider's run id would
 * key the SAME state under a different id - or under none at all where the
 * provider was mounted without one - so it reads it back from here instead.
 */
export function boundRunId(
  workflowId: string | null | undefined,
  fallback?: string | null,
): string | null {
  if (!workflowId) return fallback ?? null;
  return getCachedRunPanelData(workflowId).runId ?? fallback ?? null;
}

/** Publish a fresh snapshot (canvas → panel). Also fills the late-mount cache. */
export function publishRunPanelData(data: RunPanelData): void {
  if (typeof window === 'undefined' || !data.workflowId) return;
  cacheByWorkflow.set(data.workflowId, data);
  window.dispatchEvent(new CustomEvent<RunPanelData>(RUN_PANEL_DATA_EVENT, { detail: data }));
}

/** Subscribe to snapshots for one workflow. Returns the unsubscribe function. */
export function subscribeRunPanelData(
  workflowId: string,
  onData: (data: RunPanelData) => void,
): () => void {
  if (typeof window === 'undefined') return () => {};
  const handler = (event: Event) => {
    const detail = (event as CustomEvent<RunPanelData>).detail;
    if (!detail || detail.workflowId !== workflowId) return;
    onData(detail);
  };
  window.addEventListener(RUN_PANEL_DATA_EVENT, handler);
  return () => window.removeEventListener(RUN_PANEL_DATA_EVENT, handler);
}

/** Ask the canvas to stop / cancel / reactivate the run (panel → canvas). */
export function requestRunAction(detail: RunPanelActionDetail): void {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent<RunPanelActionDetail>(RUN_PANEL_ACTION_EVENT, { detail }));
}

/**
 * Last requested level, kept at module scope because the panel body is UNMOUNTED
 * while the side panel is closed: the click that opens the panel would otherwise
 * have no listener, and the Run tab would mount on the wrong level.
 */
let lastViewRequest: RunPanelViewRequest | null = null;
let viewRequestSeq = 0;

/** Open (and focus) the Run tab of the workflow / application panel. */
export function openRunPanel(detail: OpenRunPanelDetail = {}): void {
  if (typeof window === 'undefined') return;
  lastViewRequest = {
    workflowId: detail.workflowId,
    view: detail.view ?? 'run',
    seq: ++viewRequestSeq,
  };
  window.dispatchEvent(new CustomEvent<OpenRunPanelDetail>(OPEN_RUN_PANEL_EVENT, { detail }));
}

/** The pending level request for a workflow, or null when it targets another one. */
export function getRunPanelViewRequest(workflowId: string): RunPanelViewRequest | null {
  if (!lastViewRequest) return null;
  if (lastViewRequest.workflowId && lastViewRequest.workflowId !== workflowId) return null;
  return lastViewRequest;
}

/**
 * Open (and focus) the Add Node tab of the workflow panel.
 *
 * <p>Unlike the Run tab, the palette needs no module-level pending request: it has
 * a single level, so "which sub-tab" is the whole state and the page-level handler
 * already carries it across an unmounted panel via `setPendingActivateTab`. What
 * this event exists for is the case that handler CANNOT serve - the panel already
 * showing the workflow tab, where nothing remounts and only an in-panel listener
 * can react.
 */
export function openNodeCreatorPanel(detail: { workflowId?: string } = {}): void {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent(OPEN_NODE_CREATOR_EVENT, { detail }));
}

export interface BindRunDetail {
  workflowId?: string;
  runId: string;
}

/**
 * Show another run of this workflow WITHOUT navigating.
 *
 * Picking a run used to `router.push('/app/workflow/<id>/run/<runId>')`, which
 * tears down and rebuilds the whole route - the canvas, the panel and every
 * fetch - so the app visibly "refreshes" just to look at a sibling run. The page
 * already knows how to bind a run in place (that is how an agent-launched run
 * takes over the canvas), so the panel asks for that instead and the URL is
 * realigned silently.
 */
export function requestBindRun(detail: BindRunDetail): void {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent<BindRunDetail>(BIND_RUN_EVENT, { detail }));
}

/**
 * Workspace switch must not leak one org's run snapshots into the next. Also
 * drops the pending level request: replaying one minted before the switch would
 * yank the freshly-mounted panel to a level the user never asked for here.
 */
export function clearRunPanelCache(): void {
  cacheByWorkflow.clear();
  lastViewRequest = null;
}

/**
 * Forget the pending level request once a panel has acted on it, so a much later
 * remount does not replay a stale "history" (or "run") the user asked for one
 * navigation ago.
 */
export function consumeRunPanelViewRequest(workflowId: string): void {
  if (!lastViewRequest) return;
  if (lastViewRequest.workflowId && lastViewRequest.workflowId !== workflowId) return;
  lastViewRequest = null;
}
