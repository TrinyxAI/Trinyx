// @vitest-environment jsdom
/**
 * The run-mode canvas toolbar reuses the trigger pin button rather than
 * re-implementing the pin flow. These tests pin the toolbar rendering variant:
 * flat chrome (no node status border), and the SAME confirmation flow, gating
 * and API call as the badge under a trigger node.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';

const mockListVersions = vi.fn();
const mockPinVersion = vi.fn();
let mockMode: Record<string, unknown>;

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => mockMode }));
vi.mock('@/contexts/WorkflowRunContext', () => ({ useRun: () => [{ rawRunState: { planVersion: 3 } }, null] }));
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    listVersions: (...a: unknown[]) => mockListVersions(...a),
    pinVersion: (...a: unknown[]) => mockPinVersion(...a),
  },
}));

import { TriggerNodePinButton } from '../TriggerNodePinButton';
import { canvasChromeCompactButtonClass } from '@/components/ui/canvas-chrome';

const toolbarButton = () => screen.getByTestId('canvas-toolbar-pin-button');

beforeEach(() => {
  mockListVersions.mockReset().mockResolvedValue({ currentVersion: 3, pinnedVersion: null });
  mockPinVersion.mockReset().mockResolvedValue({ success: true, pinnedVersion: 3, productionRunIdPublic: null });
  // Run mode viewing plan v3, nothing pinned yet - the offer to pin applies.
  mockMode = { isRunMode: true, runId: 'run-1', currentVersion: 3, activeVersion: 3, pinnedVersion: null, workflowDirty: false };
});

describe('TriggerNodePinButton - toolbar variant', () => {
  it('renders flat toolbar chrome instead of the node status badge', () => {
    render(<TriggerNodePinButton workflowId="wf1" variant="toolbar" borderColor="#ff0000" />);
    const button = toolbarButton();
    // The node variant paints a 2px status border; the toolbar copy must not.
    expect(button.getAttribute('style')).toBeNull();
    expect(button.className).not.toContain('nodrag');
    // The shared compact chrome, size included: a control nested in a chrome
    // card must land on the same row as every other one. Asserted against the
    // helper rather than a hardcoded height, so the two cannot drift.
    for (const cls of canvasChromeCompactButtonClass().split(' ')) {
      expect(button.className, `missing chrome class ${cls}`).toContain(cls);
    }
  });

  it('keeps the node badge unchanged (no toolbar test id, status border applied)', () => {
    const { container } = render(<TriggerNodePinButton workflowId="wf1" nodeId="n1" borderColor="#ff0000" />);
    expect(screen.queryByTestId('canvas-toolbar-pin-button')).toBeNull();
    expect(container.querySelector('button')?.getAttribute('style')).toContain('rgb(255, 0, 0)');
  });

  it('keeps a visible border when no node status colour is passed', () => {
    // The run-step popover renders this variant with no borderColor, on a white
    // tooltip. The button is white and flat (no drop shadow), so the hairline
    // border is the ONLY thing separating it from that surface - and a class
    // concatenated onto the shared one, rather than merged, silently loses to
    // the `border-transparent` the Button base carries.
    const { container } = render(<TriggerNodePinButton workflowId="wf1" nodeId="n1" />);
    const classes = container.querySelector('button')!.className.split(/\s+/);
    // Width AND colour: a colour token on a zero-width border is invisible.
    expect(classes).toContain('border');
    expect(classes).toContain('border-[var(--border-color)]');
    expect(classes).not.toContain('border-transparent');
  });

  it('opens the same pin confirmation on click and pins the run version', async () => {
    render(<TriggerNodePinButton workflowId="wf1" variant="toolbar" />);
    fireEvent.click(toolbarButton());

    // Fresh truth is re-read before choosing which confirmation to show.
    await waitFor(() => expect(mockListVersions).toHaveBeenCalledWith('wf1'));
    const confirm = await screen.findByText('versionHistory.pin', { selector: 'button' });
    fireEvent.click(confirm);

    // Run mode pins the version the RUN is on, not workflow HEAD.
    await waitFor(() => expect(mockPinVersion).toHaveBeenCalledWith('wf1', 3));
  });

  it('announces the new production version so the rest of the UI resyncs', async () => {
    const events: unknown[] = [];
    const listener = (e: Event) => events.push((e as CustomEvent).detail);
    window.addEventListener('workflowPinnedVersionChange', listener);
    try {
      render(<TriggerNodePinButton workflowId="wf1" variant="toolbar" />);
      fireEvent.click(toolbarButton());
      fireEvent.click(await screen.findByText('versionHistory.pin', { selector: 'button' }));
      await waitFor(() => expect(events).toEqual([{ pinnedVersion: 3 }]));
    } finally {
      window.removeEventListener('workflowPinnedVersionChange', listener);
    }
  });

  it('offers nothing once the run is already the pinned production one', () => {
    // Same gating as the node badge: in run mode there is nothing left to do.
    mockMode = { ...mockMode, pinnedVersion: 3 };
    render(<TriggerNodePinButton workflowId="wf1" variant="toolbar" />);
    expect(screen.queryByTestId('canvas-toolbar-pin-button')).toBeNull();
  });

  it('replaces an existing pin rather than pinning fresh when another version is production', async () => {
    mockMode = { ...mockMode, pinnedVersion: 2 };
    mockListVersions.mockResolvedValue({ currentVersion: 3, pinnedVersion: 2 });
    render(<TriggerNodePinButton workflowId="wf1" variant="toolbar" />);
    fireEvent.click(toolbarButton());

    expect(await screen.findByText('versionHistory.pinReplaceTitle')).toBeTruthy();
  });
});
