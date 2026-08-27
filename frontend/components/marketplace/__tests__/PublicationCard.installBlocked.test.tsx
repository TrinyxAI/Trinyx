// @vitest-environment jsdom
/**
 * Single-flight install guard on {@link PublicationCard}.
 *
 * The install machine runs ONE install at a time (marketplace-install store), so while
 * another publication is installing, clicking Install on a different card would be
 * refused with no visible effect at all. The card must show that refusal up front
 * instead of offering a button that silently does nothing.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, cleanup, fireEvent } from '@testing-library/react';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

const routerPush = vi.hoisted(() => vi.fn());
const trackMock = vi.hoisted(() => vi.fn());

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: routerPush }) }));
vi.mock('@/lib/analytics/analytics', () => ({ track: trackMock }));
vi.mock('next/link', () => ({ default: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }));
vi.mock('@/components/marketplace/ShowcasePreview', () => ({ ShowcasePreview: () => null }));
vi.mock('@/components/marketplace/InterfacePreview', () => ({ InterfacePreview: () => null }));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => null }));
vi.mock('@/components/profile/UserActionMenu', () => ({ UserActionMenu: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }));
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => null }));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => null }));
vi.mock('@/lib/format-cost', () => ({ isCeMode: false }));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({ publicationService: { getLandingSnapshot: vi.fn().mockResolvedValue({ landing: null }) } }));

import { PublicationCard } from '../PublicationCard';

function pub(overrides: Partial<WorkflowPublication> = {}): WorkflowPublication {
  return {
    id: 'pub-1',
    title: 'Gallery',
    displayMode: 'APPLICATION',
    creditsPerUse: 0,
    publisherId: 'pub-user',
    status: 'ACTIVE',
    ...overrides,
  } as WorkflowPublication;
}

beforeEach(() => {
  vi.clearAllMocks();
  cleanup();
});

describe('PublicationCard - concurrent install guard', () => {
  it('disables Install while another publication is installing, and says why', () => {
    render(<PublicationCard publication={pub()} onAcquire={() => {}} installBlocked />);

    const acquire = screen.getByTestId('publication-card-acquire');
    expect(acquire).toBeDisabled();
    expect(acquire).toHaveAttribute('title', 'installBusy');
  });

  it('a blocked Install cannot reach the acquire handler at all', () => {
    // Pins the user-visible contract (a click does nothing). The handler ALSO guards on
    // installBlocked, which is what protects a programmatic caller; the browser refusing
    // the event on a disabled control is the first of the two lines of defence.
    const onAcquire = vi.fn();
    render(<PublicationCard publication={pub()} onAcquire={onAcquire} installBlocked />);

    fireEvent.click(screen.getByTestId('publication-card-acquire'));

    expect(onAcquire).not.toHaveBeenCalled();
  });

  it('leaves Install fully usable when nothing else is installing', () => {
    const onAcquire = vi.fn();
    render(<PublicationCard publication={pub()} onAcquire={onAcquire} />);

    const acquire = screen.getByTestId('publication-card-acquire');
    expect(acquire).toBeEnabled();
    expect(acquire).not.toHaveAttribute('title');

    fireEvent.click(acquire);
    expect(onAcquire).toHaveBeenCalledTimes(1);
  });
});
