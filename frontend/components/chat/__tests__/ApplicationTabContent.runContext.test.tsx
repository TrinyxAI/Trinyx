/**
 * @vitest-environment jsdom
 *
 * Run-context semantics in the application toolbar:
 *   - When pinned to ONE epoch with several rendered pages, the bare
 *     "{page+1} / {totalPages}" counter becomes "Item X/Y" derived from the
 *     CURRENT page's item triple (itemIndex is 0-based in the API → 1-based
 *     for humans).
 *   - A "Re-execution N" badge is appended ONLY when the displayed item is a
 *     re-run (spawn > 0) - first executions (spawn 0) stay badge-free.
 *   - In "All epochs" mode pages span epochs, so the bare counter is kept.
 *   - The Continue button's tooltip carries "Epoch X · Item Y" so the user
 *     knows exactly what will be continued.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, cleanup, act } from '@testing-library/react';
import * as React from 'react';

async function flushEffects() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

// Mutable render-API response - the item triple under test is changed per test.
const renderDataRef = vi.hoisted(() => ({
  current: {
    htmlTemplate: '<div>app</div>',
    items: [{ epoch: 4, spawn: 0, itemIndex: 1, data: { foo: 'bar' } }],
    pagination: { totalPages: 3 },
  } as Record<string, any>,
}));

const runStateRef = vi.hoisted(() => ({
  current: { runStatus: 'awaiting_signal', executionTotal: 0, pendingSignals: [] } as Record<string, unknown>,
}));

vi.mock('@/contexts/WorkflowRunContext', () => ({
  useRun: () => [runStateRef.current, { executeStep: vi.fn() }],
}));

vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isRunMode: true, isPreviewOnly: false }),
}));

vi.mock('@/app/workflows/builder/hooks/useInterfaces', () => ({
  useInterfaceById: () => ({ data: undefined }),
  useInterfaceRender: () => ({
    data: renderDataRef.current,
    isLoading: false,
    isFetching: false,
    isPlaceholderData: false,
    refetch: vi.fn(),
  }),
}));

vi.mock('@/lib/stores/interface-pagination-store', () => ({
  useSharedInterfacePage: () => [0, () => undefined],
}));

vi.mock('@/lib/api/api-client', () => ({
  apiClient: { getTokenProvider: () => null },
}));

vi.mock('@/lib/api', () => ({ orchestratorApi: {} }));
vi.mock('@/lib/api/orchestrator/execution.service', () => ({ executionService: {} }));
vi.mock('@/lib/api/orchestrator/workflow.service', () => ({
  workflowService: { getWorkflow: vi.fn().mockResolvedValue({ plan: { triggers: [] } }) },
}));

// The Continue button only renders for a BLOCKING interface awaiting its
// signal with the displayed item still pending - force both helpers true.
vi.mock('../interfaceAwaitingSignal', () => ({
  computeIsAwaitingSignal: () => true,
  isCurrentInterfaceItemPending: () => true,
}));

// Surface the new pageLabel/pageBadge props (and the extraControls hosting the
// Continue button) instead of re-testing InterfaceToolbar internals here.
vi.mock('@/app/workflows/builder/components/interface/InterfaceToolbar', () => ({
  InterfaceToolbar: (props: any) => (
    <div data-testid="toolbar-stub">
      <span data-testid="page-label">
        {props.pageLabel ?? `${props.currentPage + 1} / ${props.totalPages}`}
      </span>
      {props.pageBadge && <span data-testid="page-badge">{props.pageBadge}</span>}
      {props.extraControls}
    </div>
  ),
}));

vi.mock('@/app/workflows/builder/components/interface/InterfaceIframe', () => ({
  InterfaceIframe: () => <div data-testid="iframe-stub" />,
}));

vi.mock('@/components/LoadingSpinner', () => ({
  default: () => <span data-testid="loading-spinner" />,
}));

vi.mock('@/app/workflows/builder/components/TriggerPanel', () => ({
  TriggerPanel: () => <div data-testid="trigger-panel-stub" />,
}));

vi.mock('@/app/workflows/builder/utils/interfaceHtmlUtils', () => ({
  mergeTriggerDataIntoResolved: () => ({ foo: 'bar' }),
}));

vi.mock('@/app/workflows/builder/utils/safeCenteringCss', () => ({ SAFE_CENTERING_CSS: '', centeringCssFor: () => '' }));

vi.mock('@/lib/utils/dateFormatters', () => ({
  parseUtcAware: (s: string) => new Date(s),
  formatUtcTime: (s: string) => s,
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) => {
    const templates: Record<string, string> = {
      itemOfTotal: 'Item {number}/{total}',
      reExecutionBadge: 'Re-execution {number}',
      epochBadge: 'Epoch {number}',
      itemBadge: 'Item {number}',
      continueWorkflow: 'Continue',
      continueItemRemaining: 'Continue this item ({count} pending)',
      itemAlreadyResolved: 'itemAlreadyResolved',
    };
    const tpl = templates[key] ?? key;
    return tpl.replace(/\{(\w+)\}/g, (_m, k: string) => String(values?.[k] ?? ''));
  },
}));

import { ApplicationTabContent } from '../ApplicationTabContent';

const baseConfig = {
  interfaceId: 'iface-1',
  label: 'tab',
  actionMapping: { submit: '__continue' },
  nodeId: 'interface:app',
};

function renderApp(viewingEpoch: number | null) {
  return render(
    <ApplicationTabContent
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      config={baseConfig as any}
      runId="run_1"
      workflowId="wf-1"
      onAction={() => undefined}
      viewingEpoch={viewingEpoch}
      onViewingEpochChange={() => undefined}
      toolbarOpen
      onToolbarOpenChange={() => undefined}
    />,
  );
}

describe('ApplicationTabContent - which epoch the tab opens on', () => {
  beforeEach(() => {
    runStateRef.current = {
      runStatus: 'awaiting_signal',
      executionTotal: 0,
      pendingSignals: [],
      // Three closed fires: the tab used to seed the newest one on mount.
      epochTimestamps: [
        { epoch: 1, startedAt: '2026-08-01T10:00:00Z', endedAt: '2026-08-01T10:01:00Z' },
        { epoch: 2, startedAt: '2026-08-01T11:00:00Z', endedAt: '2026-08-01T11:01:00Z' },
        { epoch: 3, startedAt: '2026-08-01T12:00:00Z', endedAt: null },
      ],
    };
  });
  afterEach(cleanup);

  it('selects no epoch on mount, and tells no other surface to', async () => {
    // The seeding was not just local: `handleViewEpoch` broadcasts the epoch to
    // every surface of the run, so merely opening an application dragged the
    // canvas and the Run tab off the cumulative view.
    const onViewingEpochChange = vi.fn();
    const broadcasts: unknown[] = [];
    const listener = (e: Event) => broadcasts.push((e as CustomEvent).detail);
    window.addEventListener('viewingEpochChanged', listener);

    render(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        viewingEpoch={null}
        onViewingEpochChange={onViewingEpochChange}
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();

    window.removeEventListener('viewingEpochChanged', listener);
    expect(onViewingEpochChange, 'the tab must not pick an epoch for the user').not.toHaveBeenCalled();
    expect(broadcasts, 'and must not push one onto the other surfaces').toEqual([]);
  });

  it('opens on the newest fire where the application IS the product', async () => {
    // A published app or a shared link: its visitors came for the latest
    // result, not a pager spanning every fire the workflow ever had.
    const onViewingEpochChange = vi.fn();
    const broadcasts: unknown[] = [];
    const listener = (e: Event) => broadcasts.push((e as CustomEvent).detail);
    window.addEventListener('viewingEpochChanged', listener);

    render(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        viewingEpoch={null}
        onViewingEpochChange={onViewingEpochChange}
        openOnLatestEpoch
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();

    window.removeEventListener('viewingEpochChanged', listener);
    expect(onViewingEpochChange).toHaveBeenCalledWith(3);
    // Locally: the workflow surfaces of the same run stay on all epochs.
    expect(broadcasts, 'the seeding must not reach the other surfaces').toEqual([]);
  });

  it('records no pick when it seeds, so the canvas keeps its own view', async () => {
    const { getPickedEpoch, resetEpochSelectionState } = await import(
      '@/components/workflow/run-panel/useDefaultEpochSelection'
    );
    resetEpochSelectionState();

    render(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        viewingEpoch={null}
        onViewingEpochChange={() => undefined}
        openOnLatestEpoch
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();

    expect(getPickedEpoch('run_1'), 'a seed is not a choice').toBeUndefined();
    resetEpochSelectionState();
  });

  it('offers a way back to All epochs, and says which view it is on', async () => {
    // The reversibility half of the seeding: pinning a published app to its
    // newest fire is only acceptable because the user can undo it here.
    const { getPickedEpoch, resetEpochSelectionState } = await import(
      '@/components/workflow/run-panel/useDefaultEpochSelection'
    );
    resetEpochSelectionState();
    const onViewingEpochChange = vi.fn();

    const { getByTestId } = render(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        viewingEpoch={3}
        onViewingEpochChange={onViewingEpochChange}
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();

    const control = getByTestId('application-epoch-selector');
    expect(control.getAttribute('data-all-epochs'), 'pinned to one fire').toBeNull();

    act(() => { control.click(); });
    act(() => { getByTestId('application-epoch-option-all').click(); });

    expect(onViewingEpochChange).toHaveBeenCalledWith(null);
    expect(getPickedEpoch('run_1'), 'and it is recorded, so nothing restores the epoch').toBeNull();
    resetEpochSelectionState();
  });

  it('says "All epochs" in words when it is showing all of them', async () => {
    const { resetEpochSelectionState } = await import(
      '@/components/workflow/run-panel/useDefaultEpochSelection'
    );
    resetEpochSelectionState();

    const { getByTestId } = render(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        viewingEpoch={null}
        onViewingEpochChange={() => undefined}
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();

    const control = getByTestId('application-epoch-selector');
    expect(control.getAttribute('data-all-epochs')).toBe('true');
    // A bare number there could only be read as "you are on epoch N".
    expect(control.textContent).toContain('allEpochs');
    resetEpochSelectionState();
  });

  it('seeds its own state when no parent owns the epoch (the app side-panel tab)', async () => {
    // ApplicationSidePanel renders the tab uncontrolled: the seed has to land
    // on the local state, or a published app opened from a card shows the
    // cumulative pager after all.
    const { resetEpochSelectionState } = await import(
      '@/components/workflow/run-panel/useDefaultEpochSelection'
    );
    resetEpochSelectionState();

    const { getByTestId } = render(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        openOnLatestEpoch
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();

    const control = getByTestId('application-epoch-selector');
    expect(control.getAttribute('data-all-epochs'), 'seeded to the newest fire').toBeNull();
    expect(control.textContent).toContain('3');
    resetEpochSelectionState();
  });

  it('carries a REAL pin to the new fire, and records the move', async () => {
    // The other half of the attribution rule: a user who pinned epoch 3 is
    // carried to 4, and the remembered pick moves with them - otherwise the
    // next surface to mount empty restores the epoch they were carried off.
    const { markEpochPickedByUser, getPickedEpoch, resetEpochSelectionState } = await import(
      '@/components/workflow/run-panel/useDefaultEpochSelection'
    );
    resetEpochSelectionState();
    markEpochPickedByUser('run_1', 3);

    const { rerender } = render(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        viewingEpoch={3}
        onViewingEpochChange={() => undefined}
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();

    runStateRef.current = {
      ...runStateRef.current,
      epochTimestamps: [
        ...(runStateRef.current.epochTimestamps as unknown[]),
        { epoch: 4, startedAt: '2026-08-01T13:00:00Z', endedAt: null },
      ],
    };
    rerender(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        viewingEpoch={3}
        onViewingEpochChange={() => undefined}
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();

    expect(getPickedEpoch('run_1'), 'the pin followed the user').toBe(4);
    resetEpochSelectionState();
  });

  it('does not turn an explicit "All epochs" into a pin when the next fire lands', async () => {
    // The user chose All epochs elsewhere (recorded as null), then opens the
    // published app, which seeds the newest fire LOCALLY. When the next fire
    // closes, that seed must not be promoted into a pick and broadcast: it
    // would drag the canvas onto an epoch the user explicitly left.
    const { markEpochPickedByUser, getPickedEpoch, resetEpochSelectionState } = await import(
      '@/components/workflow/run-panel/useDefaultEpochSelection'
    );
    resetEpochSelectionState();
    markEpochPickedByUser('run_1', null);

    const { rerender } = render(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        viewingEpoch={3}
        onViewingEpochChange={() => undefined}
        openOnLatestEpoch
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();

    // A fourth fire closes.
    runStateRef.current = {
      ...runStateRef.current,
      epochTimestamps: [
        ...(runStateRef.current.epochTimestamps as unknown[]),
        { epoch: 4, startedAt: '2026-08-01T13:00:00Z', endedAt: null },
      ],
    };
    rerender(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        viewingEpoch={3}
        onViewingEpochChange={() => undefined}
        openOnLatestEpoch
        toolbarOpen
        onToolbarOpenChange={() => undefined}
      />,
    );
    await flushEffects();

    expect(getPickedEpoch('run_1'), 'the All-epochs choice survives the new fire').toBeNull();
    resetEpochSelectionState();
  });
});

describe('ApplicationTabContent - run-context pagination semantics', () => {
  beforeEach(() => {
    runStateRef.current = { runStatus: 'awaiting_signal', executionTotal: 0, pendingSignals: [] };
    renderDataRef.current = {
      htmlTemplate: '<div>app</div>',
      items: [{ epoch: 4, spawn: 0, itemIndex: 1, data: { foo: 'bar' } }],
      pagination: { totalPages: 3 },
    };
  });
  afterEach(cleanup);

  it('shows "Item X/Y" (1-based itemIndex) instead of the bare page counter when pinned to an epoch', async () => {
    const { getByTestId } = renderApp(4);
    expect(getByTestId('page-label').textContent).toBe('Item 2/3');
    await flushEffects();
  });

  it('keeps the bare page counter in "All epochs" mode (pages span epochs there)', async () => {
    const { getByTestId } = renderApp(null);
    expect(getByTestId('page-label').textContent).toBe('1 / 3');
    await flushEffects();
  });

  it('appends a 1-based "Re-execution N" badge when the displayed item is a re-run (spawn > 0)', async () => {
    renderDataRef.current = {
      ...renderDataRef.current,
      items: [{ epoch: 4, spawn: 1, itemIndex: 1, data: { foo: 'bar' } }],
    };
    const { getByTestId } = renderApp(4);
    expect(getByTestId('page-badge').textContent).toBe('Re-execution 2');
    await flushEffects();
  });

  it('hides the re-execution badge for a first execution (spawn = 0)', async () => {
    const { queryByTestId } = renderApp(4);
    expect(queryByTestId('page-badge')).toBeNull();
    await flushEffects();
  });

  it('Continue button tooltip carries the "Epoch X · Item Y" context of what will be continued', async () => {
    const { getByTitle } = renderApp(4);
    // Raw epoch (matches the epoch selector numbers) + 1-based item.
    expect(getByTitle('Continue - Epoch 4 · Item 2')).toBeTruthy();
    await flushEffects();
  });
});
