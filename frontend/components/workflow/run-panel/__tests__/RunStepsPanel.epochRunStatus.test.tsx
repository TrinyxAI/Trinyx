/**
 * @vitest-environment jsdom
 *
 * The epoch selector cannot answer "is this epoch executing" from the epoch row alone
 * (the close is deferred), so it needs the RUN's status. That prop is the whole hinge:
 * drop it and every epoch of a resting run goes back to a permanent blue pulse, with
 * every other test still green.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/react';

const epochSelectorProps = vi.hoisted(() => ({ current: null as any }));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getEpochAggregatedSteps: vi.fn() } }));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getShowcaseAggregatedSteps: vi.fn() },
}));
vi.mock('@/contexts/PublicationSnapshotContext', () => ({ getActivePublicPreview: () => null }));
vi.mock('@/components/workflow/StepRowActions', () => ({ StepRowActions: () => null }));
vi.mock('@/app/workflows/builder/components/nodes/shared', () => ({
  getIconSlug: () => 'x',
  NodeIcon: () => null,
  nodeIconRadiusClass: () => 'rounded-md',
}));
// Capture what the panel hands the selector rather than re-testing the selector.
vi.mock('./../EpochSelector', () => ({
  EpochSelector: (props: any) => {
    epochSelectorProps.current = props;
    return null;
  },
}));

import { RunStepsPanel } from '@/components/workflow/run-panel/RunStepsPanel';

const EPOCHS = [
  { epoch: 1, startedAt: '2026-08-02T09:00:00Z', endedAt: null, status: 'FAILED' },
];

describe('RunStepsPanel - run status reaches the epoch selector', () => {
  afterEach(() => {
    epochSelectorProps.current = null;
    cleanup();
  });

  it('forwards the run status alongside the epochs', () => {
    render(
      <RunStepsPanel
        currentRunInfo={{ runId: 'run-1', status: 'WAITING_TRIGGER' }}
        streamedSteps={[]}
        epochTimestamps={EPOCHS as any}
        selectedEpoch={null}
        onSelectEpoch={() => {}}
      />,
    );

    expect(epochSelectorProps.current?.runStatus).toBe('WAITING_TRIGGER');
    expect(epochSelectorProps.current?.epochTimestamps).toEqual(EPOCHS);
  });

  it('passes undefined rather than throwing when there is no run info', () => {
    render(
      <RunStepsPanel
        currentRunInfo={null}
        streamedSteps={[]}
        epochTimestamps={EPOCHS as any}
        selectedEpoch={null}
        onSelectEpoch={() => {}}
      />,
    );

    expect(epochSelectorProps.current?.runStatus).toBeUndefined();
  });
});
