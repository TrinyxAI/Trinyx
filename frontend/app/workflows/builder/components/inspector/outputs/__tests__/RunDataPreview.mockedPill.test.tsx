// @vitest-environment jsdom
/**
 * Tests for the "Mocked" pill of {@link RunDataPreview} - the only place the
 * inspector tells the user that the output column shows a MOCK and not a real
 * execution result. The engine dual-stamps `__mocked__` / `__mock_source__` on
 * a mocked step output and StepPayloadService re-injects them after schema
 * transformation; the pill is the front-end end of that chain, so it must show
 * exactly when the loaded OUTPUT object carries `__mocked__: true` and never
 * otherwise (a false badge on a real result is worse than no badge).
 */
import '@testing-library/jest-dom/vitest';
import * as React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

vi.mock('@/contexts/PublicationSnapshotContext', () => ({
  getActivePublicPreview: () => null,
}));
vi.mock('@/lib/api/api-client', () => ({
  apiClient: { getTokenProvider: () => null },
}));

const hookState: any = {};
vi.mock('../../../../hooks/useRunData', () => ({
  useRunData: () => hookState,
}));

import { RunDataPreview } from '../RunDataPreview';

const PILL = 'run-data-mocked-pill';

const messages = {
  workflowBuilder: {
    inspector: { item: 'Item', loading: 'Loading', noData: 'No data' },
    mock: { mockedBadge: 'Mocked' },
  },
  dataTable: { allStatuses: 'All' },
};

function renderWithIntl(ui: React.ReactNode) {
  return render(
    <NextIntlClientProvider locale="en" messages={messages as any}>
      {ui}
    </NextIntlClientProvider>,
  );
}

function resetHookState() {
  Object.assign(hookState, {
    totalItems: 1,
    isLoading: false,
    error: null,
    currentIndex: 0,
    currentItem: { id: 100 },
    goToIndex: vi.fn(),
    getObjectAtPath: vi.fn(),
    availableStatuses: [],
    items: [],
    hasNext: false,
    hasPrev: false,
    goToNext: vi.fn(),
    goToPrev: vi.fn(),
    skeleton: null,
    isLoadingSkeleton: false,
    getValueAtPath: vi.fn(),
    refetch: vi.fn(),
  });
}

/** Renders the preview and waits for the async object fetch to land. */
async function renderLoaded(output: unknown, props: Record<string, unknown> = {}) {
  hookState.getObjectAtPath = vi.fn().mockResolvedValue(output);
  const view = renderWithIntl(
    <RunDataPreview
      workflowId="wf"
      runId="run"
      stepAlias="prep"
      dataType="output"
      {...(props as any)}
    />,
  );
  await waitFor(() => expect(hookState.getObjectAtPath).toHaveBeenCalled());
  return view;
}

describe('RunDataPreview - mocked pill', () => {
  beforeEach(resetHookState);

  it('shows the localized Mocked pill when the loaded output carries __mocked__: true', async () => {
    await renderLoaded({ __mocked__: true, __mock_source__: 'static', result: { score: 42 } });

    const pill = await screen.findByTestId(PILL);
    expect(pill).toHaveTextContent('Mocked');
  });

  it('shows the pill for a catalog-example mock too (any __mock_source__ value)', async () => {
    await renderLoaded({ __mocked__: true, __mock_source__: 'catalog_example', items: [] });

    expect(await screen.findByTestId(PILL)).toBeInTheDocument();
  });

  it('hides the pill for a REAL execution output (no marker key)', async () => {
    await renderLoaded({ result: { score: 42 } });

    // The data tree rendered, so the absence below is a real negative and not
    // an assertion racing the fetch.
    await waitFor(() => expect(screen.getByText(/result/i)).toBeInTheDocument());
    expect(screen.queryByTestId(PILL)).not.toBeInTheDocument();
  });

  it('hides the pill when __mocked__ is present but false (marker must be strictly true)', async () => {
    await renderLoaded({ __mocked__: false, result: { score: 42 } });

    await waitFor(() => expect(screen.getByText(/result/i)).toBeInTheDocument());
    expect(screen.queryByTestId(PILL)).not.toBeInTheDocument();
  });

  it('hides the pill on the INPUT column even when the object carries the marker', async () => {
    // Only an OUTPUT is ever mock-produced; an input object that happens to
    // carry the key (e.g. a predecessor's mocked output fed in) must not
    // brand the input column as mocked.
    await renderLoaded({ __mocked__: true, result: { score: 42 } }, { dataType: 'input' });

    await waitFor(() => expect(screen.getByText(/result/i)).toBeInTheDocument());
    expect(screen.queryByTestId(PILL)).not.toBeInTheDocument();
  });

  it('hides the pill when the step has zero items (nothing loaded to qualify)', async () => {
    hookState.totalItems = 0;
    hookState.currentItem = undefined;
    hookState.getObjectAtPath = vi.fn().mockResolvedValue({ __mocked__: true });

    renderWithIntl(
      <RunDataPreview workflowId="wf" runId="run" stepAlias="prep" dataType="output" />,
    );

    expect(await screen.findByText('No data')).toBeInTheDocument();
    expect(screen.queryByTestId(PILL)).not.toBeInTheDocument();
  });

  it('drops the pill when navigating from a mocked item to a real one', async () => {
    // The preview instance is REUSED across items/nodes: a stale pill would
    // label a real result as mocked.
    hookState.getObjectAtPath = vi
      .fn()
      .mockResolvedValueOnce({ __mocked__: true, result: { a: 1 } })
      .mockResolvedValueOnce({ result: { a: 2 } });

    const view = renderWithIntl(
      <RunDataPreview workflowId="wf" runId="run" stepAlias="prep" dataType="output" />,
    );
    expect(await screen.findByTestId(PILL)).toBeInTheDocument();

    // Next item of the same step: same instance, new row id.
    hookState.currentIndex = 1;
    hookState.currentItem = { id: 101 };
    view.rerender(
      <NextIntlClientProvider locale="en" messages={messages as any}>
        <RunDataPreview workflowId="wf" runId="run" stepAlias="prep" dataType="output" />
      </NextIntlClientProvider>,
    );

    await waitFor(() => expect(screen.queryByTestId(PILL)).not.toBeInTheDocument());
  });
});
