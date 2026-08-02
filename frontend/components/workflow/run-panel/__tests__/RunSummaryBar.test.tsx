/**
 * @vitest-environment jsdom
 *
 * The run identity bar is the ONE component behind both run surfaces: the
 * compact pill on the canvas and the header of the side-panel Run tab. Whatever
 * it decides - which action a run is offered, whether the history is reachable -
 * is decided for both at once, so these are the rules worth pinning:
 *
 *  - a live run can be stopped, a resting reusable run cancelled, a finished one
 *    reactivated, and never two of those at the same time;
 *  - the version chip is the canvas's route into the run history, so it must
 *    survive a run whose version is unknown.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));

import { RunSummaryBar } from '@/components/workflow/run-panel/RunSummaryBar';

const NOOP = () => undefined;

function renderBar(status: string, props: Record<string, unknown> = {}) {
  return render(
    <RunSummaryBar
      currentRunInfo={{ runId: 'run-1', status, planVersion: 3 } as never}
      pinnedVersion={null}
      currentEpoch={1}
      epochTimestamps={[]}
      selectedEpoch={null}
      onStop={NOOP}
      onCancel={NOOP}
      onReactivate={NOOP}
      {...props}
    />,
  );
}

/** The action button carries its intent in the title (an icon-only control). */
const actionTitles = () =>
  Array.from(document.querySelectorAll('button[title]')).map(b => b.getAttribute('title'));

afterEach(cleanup);

describe('RunSummaryBar - which action a run is offered', () => {
  it('offers stop while the run is executing', () => {
    renderBar('RUNNING');
    expect(actionTitles()).toContain('workflow.mode.stopWorkflow');
    expect(actionTitles()).not.toContain('workflow.reactivateRun.title');
  });

  it('offers cancel - not stop - for a reusable run resting between fires', () => {
    // WAITING_TRIGGER is alive but idle: stopping it is not the same act as
    // cancelling it, and the confirm exists because cancelling ends the run.
    renderBar('WAITING_TRIGGER');
    expect(actionTitles()).toContain('workflow.cancelRun.title');
    expect(actionTitles()).not.toContain('workflow.mode.stopWorkflow');
  });

  it('offers reactivate on every terminal status, including SKIPPED', () => {
    // The dispatcher refuses to fire into a terminal run, so the only way back is
    // this button. `skipped` was missing from the terminal set once, and the run
    // was then offered a stop the backend would refuse instead.
    for (const status of ['COMPLETED', 'FAILED', 'PARTIAL_SUCCESS', 'CANCELLED', 'TIMEOUT', 'SKIPPED']) {
      cleanup();
      renderBar(status);
      expect(actionTitles(), `${status} should be reactivatable`).toContain('workflow.reactivateRun.title');
      expect(actionTitles(), `${status} is finished - nothing to stop`).not.toContain('workflow.mode.stopWorkflow');
    }
  });

  it('offers nothing at all when the surface passes no handlers (preview)', () => {
    renderBar('RUNNING', { onStop: undefined, onCancel: undefined, onReactivate: undefined });
    expect(actionTitles()).not.toContain('workflow.mode.stopWorkflow');
    expect(actionTitles()).not.toContain('workflow.reactivateRun.title');
  });
});

describe('RunSummaryBar - reaching the run history', () => {
  it('makes the version chip the way in', () => {
    const onVersionClick = vi.fn();
    renderBar('RUNNING', { onVersionClick });
    const chip = document.querySelector('[data-run-version-chip]') as HTMLElement;
    expect(chip.textContent).toContain('v3');
    fireEvent.click(chip);
    expect(onVersionClick).toHaveBeenCalled();
  });

  it('keeps a way in when the run has no version to show', () => {
    // An older run, or one whose info has not loaded: without this the chip is
    // absent and the canvas has NO route into the history at all.
    const onVersionClick = vi.fn();
    renderBar('RUNNING', { onVersionClick, currentRunInfo: { runId: 'run-1', status: 'RUNNING' } });
    const chip = document.querySelector('[data-run-version-chip]') as HTMLElement;
    expect(chip).toBeTruthy();
    fireEvent.click(chip);
    expect(onVersionClick).toHaveBeenCalled();
  });

  it('shows a plain version with no way in when the surface forbids history', () => {
    // Marketplace preview / embedded canvas: the run is frozen, so the version is
    // information, not navigation.
    renderBar('RUNNING');
    expect(document.querySelector('[data-run-version-chip]')).toBeNull();
    expect(screen.getByText('v3')).toBeTruthy();
  });
});
