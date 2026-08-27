// @vitest-environment jsdom
/**
 * The install-time opt-in for the editable WORKFLOW copy.
 *
 * The copy re-clones the app's whole snapshot (a second set of its interfaces, tables and
 * agents), which is why it stopped being minted on every install. It is offered here as an
 * UNCHECKED box so the cheap install stays the default, while someone who installs an app
 * in order to edit it does not have to come back for it through the app's settings cog.
 *
 * The copy is best-effort on purpose: the application is installed either way, so a failed
 * copy is a note on the success screen, never an error screen.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, args?: Record<string, unknown>) =>
    args ? `${key}:${JSON.stringify(args)}` : key,
}));
const push = vi.hoisted(() => vi.fn());
vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));
vi.mock('@/lib/format-cost', () => ({ isCeMode: false }));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => null }));

const svc = vi.hoisted(() => ({
  acquireRemotePublication: vi.fn(),
  acquireAgentPublication: vi.fn(),
  acquireResourcePublication: vi.fn(),
  acquirePublication: vi.fn(),
  createEditableWorkflowCopy: vi.fn(),
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({ publicationService: svc }));

import AcquirePublicationModal from '../AcquirePublicationModal';
import { useMarketplaceInstallStore } from '@/lib/stores/marketplace-install-store';

function pub(overrides: Partial<WorkflowPublication> = {}): WorkflowPublication {
  return {
    id: 'pub-1',
    title: 'Cloud Thing',
    creditsPerUse: 0,
    publicationType: 'WORKFLOW',
    displayMode: 'APPLICATION',
    ...overrides,
  } as WorkflowPublication;
}

const checkbox = () => screen.getByTestId('acquire-editable-copy-checkbox');

describe('AcquirePublicationModal - editable copy opt-in', () => {
  let nowValue = 0;

  beforeEach(() => {
    vi.clearAllMocks();
    useMarketplaceInstallStore.setState({ active: null });
    nowValue = 0;
    vi.useFakeTimers();
    vi.spyOn(Math, 'random').mockReturnValue(0); // deterministic 5000ms budget
    vi.spyOn(performance, 'now').mockImplementation(() => nowValue);
    svc.acquirePublication.mockResolvedValue({ workflowId: 'w1', resources: {} });
    svc.createEditableWorkflowCopy.mockResolvedValue({ workflowId: 'wf-copy', created: true });
  });

  afterEach(() => {
    useMarketplaceInstallStore.getState().clear();
    cleanup();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  async function confirmAndFinish() {
    fireEvent.click(screen.getByRole('button', { name: 'addToApplications' }));
    nowValue = 6000;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(6000);
    });
  }

  it('is offered UNCHECKED, so the default install stays the cheap one', () => {
    render(<AcquirePublicationModal isOpen publication={pub()} onClose={() => {}} />);

    expect(checkbox()).toBeInTheDocument();
    expect(checkbox()).toHaveAttribute('data-state', 'unchecked');
  });

  it('does not request a copy when the box is left untouched', async () => {
    render(<AcquirePublicationModal isOpen publication={pub()} onClose={() => {}} />);

    await confirmAndFinish();

    expect(svc.acquirePublication).toHaveBeenCalled();
    expect(svc.createEditableWorkflowCopy).not.toHaveBeenCalled();
  });

  it('requests the copy after the install succeeds and links to it from the success screen', async () => {
    render(<AcquirePublicationModal isOpen publication={pub()} onClose={() => {}} />);

    fireEvent.click(checkbox());
    await confirmAndFinish();

    expect(svc.createEditableWorkflowCopy).toHaveBeenCalledWith('pub-1', false);
    expect(screen.getByText('successTitle')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('installed-open-editable-copy'));
    expect(push).toHaveBeenCalledWith('/app/workflow/wf-copy');
  });

  it('a FAILED copy leaves the install successful and says so, instead of an error screen', async () => {
    // The application is installed either way: turning a copy failure into an error
    // screen would tell the user the install failed when it did not.
    svc.createEditableWorkflowCopy.mockRejectedValue(new Error('quota'));
    render(<AcquirePublicationModal isOpen publication={pub()} onClose={() => {}} />);

    fireEvent.click(checkbox());
    await confirmAndFinish();

    expect(screen.getByText('successTitle')).toBeInTheDocument();
    expect(screen.getByTestId('installed-editable-copy-failed')).toBeInTheDocument();
    expect(screen.queryByTestId('installed-open-editable-copy')).not.toBeInTheDocument();
    expect(useMarketplaceInstallStore.getState().active?.status).toBe('success');
  });

  it('routes a cloud-linked CE install through the remote copy endpoint', async () => {
    svc.acquireRemotePublication.mockResolvedValue({ workflowId: 'w1', resources: {} });
    render(<AcquirePublicationModal isOpen publication={pub()} onClose={() => {}} ceMode />);

    fireEvent.click(checkbox());
    await confirmAndFinish();

    expect(svc.createEditableWorkflowCopy).toHaveBeenCalledWith('pub-1', true);
  });

  it('starts unchecked again on a fresh open (a tick never carries over to another install)', async () => {
    const { rerender } = render(
      <AcquirePublicationModal isOpen publication={pub()} onClose={() => {}} />,
    );
    fireEvent.click(checkbox());
    expect(checkbox()).toHaveAttribute('data-state', 'checked');

    rerender(<AcquirePublicationModal isOpen={false} publication={pub()} onClose={() => {}} />);
    rerender(<AcquirePublicationModal isOpen publication={pub()} onClose={() => {}} />);

    expect(checkbox()).toHaveAttribute('data-state', 'unchecked');
    await confirmAndFinish();
    expect(svc.createEditableWorkflowCopy).not.toHaveBeenCalled();
  });
});

describe('AcquirePublicationModal - the opt-in is only shown where a copy can exist', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useMarketplaceInstallStore.setState({ active: null });
    svc.acquirePublication.mockImplementation(() => new Promise(() => {}));
  });
  afterEach(() => {
    useMarketplaceInstallStore.getState().clear();
    cleanup();
  });

  it('is hidden for a plain WORKFLOW publication (it is already editable once acquired)', () => {
    render(<AcquirePublicationModal isOpen publication={pub({ displayMode: 'WORKFLOW' })} onClose={() => {}} />);
    expect(screen.queryByTestId('acquire-editable-copy-checkbox')).not.toBeInTheDocument();
  });

  it('is hidden for an AGENT publication (no application clone to decouple)', () => {
    render(
      <AcquirePublicationModal
        isOpen
        publication={pub({ publicationType: 'AGENT', displayMode: 'AGENT' })}
        onClose={() => {}}
      />,
    );
    expect(screen.queryByTestId('acquire-editable-copy-checkbox')).not.toBeInTheDocument();
  });

  it('is hidden for a TABLE publication', () => {
    render(
      <AcquirePublicationModal
        isOpen
        publication={pub({ publicationType: 'TABLE', displayMode: 'APPLICATION' })}
        onClose={() => {}}
      />,
    );
    expect(screen.queryByTestId('acquire-editable-copy-checkbox')).not.toBeInTheDocument();
  });

  it('is hidden on the OWN application of the publisher (they own the source workflow already)', () => {
    render(<AcquirePublicationModal isOpen publication={pub({ ownedByMe: true })} onClose={() => {}} />);
    expect(screen.queryByTestId('acquire-editable-copy-checkbox')).not.toBeInTheDocument();
  });

  it('is shown for an APPLICATION published by someone else', () => {
    render(<AcquirePublicationModal isOpen publication={pub()} onClose={() => {}} />);
    expect(screen.getByTestId('acquire-editable-copy-checkbox')).toBeInTheDocument();
  });
});
