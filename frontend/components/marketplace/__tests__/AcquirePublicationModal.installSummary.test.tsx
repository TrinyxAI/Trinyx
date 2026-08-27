// @vitest-environment jsdom
/**
 * Two things the acquire modal must now say out loud:
 *
 * 1. WHAT THE INSTALL CREATED. An install clones the application plus, silently, its
 *    interfaces, tables and agents into three different lists. The success screen lists
 *    them from the backend's own counts (the inline-progress consumers get the same list
 *    from InstallSummaryModal, so no install path leaves the user guessing).
 * 2. WHY A SECOND INSTALL IS REFUSED. The install machine is single-flight: without a
 *    message, the disabled confirm button reads as a bug.
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
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));
vi.mock('@/lib/format-cost', () => ({ isCeMode: false }));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => null }));

const svc = vi.hoisted(() => ({
  acquireRemotePublication: vi.fn(),
  acquireAgentPublication: vi.fn(),
  acquireResourcePublication: vi.fn(),
  acquirePublication: vi.fn(),
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

beforeEach(() => {
  vi.clearAllMocks();
  useMarketplaceInstallStore.setState({ active: null });
  svc.acquirePublication.mockImplementation(() => new Promise(() => {})); // stays installing unless a test overrides
});

afterEach(() => {
  useMarketplaceInstallStore.getState().clear();
  cleanup();
});

// The success screen is only reachable by driving a REAL install through the store
// (a pre-seeded terminal state is deliberately dropped on open, so seeding it would
// test nothing). Same fake-timer setup as the state-machine sibling.
describe('AcquirePublicationModal - the success screen names what was installed', () => {
  let nowValue = 0;

  beforeEach(() => {
    useMarketplaceInstallStore.getState().clear();
    nowValue = 0;
    vi.useFakeTimers();
    vi.spyOn(Math, 'random').mockReturnValue(0); // deterministic 5000ms budget
    vi.spyOn(performance, 'now').mockImplementation(() => nowValue);
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  async function installAndFinish() {
    fireEvent.click(screen.getByRole('button', { name: 'addToApplications' }));
    nowValue = 6000;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(6000);
    });
  }

  it('lists the resources the backend reported creating, not just the application', async () => {
    svc.acquirePublication.mockResolvedValue({
      workflowId: 'w1',
      resources: { interfaces: 2, tables: 1 },
    });
    render(<AcquirePublicationModal isOpen publication={pub()} onClose={() => {}} />);

    await installAndFinish();

    expect(screen.getByText('successTitle')).toBeInTheDocument();
    expect(screen.getByText('installedPrimaryApplication')).toBeInTheDocument();
    expect(screen.getByText('installedInterfaces:{"count":2}')).toBeInTheDocument();
    expect(screen.getByText('installedTables:{"count":1}')).toBeInTheDocument();
  });

  it('shows the application alone when the install created nothing else', async () => {
    svc.acquirePublication.mockResolvedValue({ workflowId: 'w1', resources: {} });
    render(<AcquirePublicationModal isOpen publication={pub()} onClose={() => {}} />);

    await installAndFinish();

    expect(screen.getAllByRole('listitem')).toHaveLength(1);
    expect(screen.getByText('installedPrimaryApplication')).toBeInTheDocument();
  });
});

describe('AcquirePublicationModal - one install at a time', () => {
  it('explains the refusal and disables confirm while ANOTHER publication is installing', () => {
    useMarketplaceInstallStore.getState().startInstall(pub({ id: 'pub-other', title: 'Other' }));

    render(<AcquirePublicationModal isOpen publication={pub()} onClose={() => {}} />);

    expect(screen.getByText('installBusy')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'addToApplications' })).toBeDisabled();
  });

  it('says nothing and stays clickable when no other install is running', () => {
    render(<AcquirePublicationModal isOpen publication={pub()} onClose={() => {}} />);

    expect(screen.queryByText('installBusy')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'addToApplications' })).toBeEnabled();
  });

  it('a blocked confirm click cannot start a second install', () => {
    useMarketplaceInstallStore.getState().startInstall(pub({ id: 'pub-other', title: 'Other' }));
    svc.acquirePublication.mockClear();

    render(<AcquirePublicationModal isOpen publication={pub()} onClose={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: 'addToApplications' }));

    expect(svc.acquirePublication).not.toHaveBeenCalled();
    expect(useMarketplaceInstallStore.getState().active?.publication.id).toBe('pub-other');
  });
});
