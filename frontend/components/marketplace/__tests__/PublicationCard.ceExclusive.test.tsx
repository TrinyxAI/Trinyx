// @vitest-environment jsdom
/**
 * CE-exclusive publications on the marketplace card.
 *
 * The badge renders on BOTH editions (it is the app's requirement, not the
 * viewer's), but the Install CTA disappears only on managed cloud, where the
 * acquire endpoint would answer 403 CE_EXCLUSIVE. Offering an Install that
 * cannot succeed is the regression this guards.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));
vi.mock('next/link', () => ({ default: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }));
vi.mock('@/components/marketplace/ShowcasePreview', () => ({ ShowcasePreview: () => null }));
vi.mock('@/components/marketplace/InterfacePreview', () => ({ InterfacePreview: () => null }));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => null }));
vi.mock('@/components/profile/UserActionMenu', () => ({ UserActionMenu: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }));
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => null }));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => null }));
vi.mock('@/lib/format-cost', () => ({ isCeMode: false }));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getLandingSnapshot: vi.fn().mockResolvedValue({ landing: null }) },
}));

function pub(overrides: Partial<WorkflowPublication> = {}): WorkflowPublication {
  return {
    id: 'pub-1',
    title: 'RAG Assistant',
    displayMode: 'WORKFLOW',
    creditsPerUse: 0,
    publisherId: 'someone-else',
    status: 'ACTIVE',
    visibility: 'PUBLIC',
    useCount: 0,
    totalCreditsEarned: 0,
    ...overrides,
  } as WorkflowPublication;
}

const MANAGED_CLOUD = { IS_MANAGED_CLOUD: true, IS_CLOUD: true, IS_CE: false };
const COMMUNITY_EDITION = { IS_MANAGED_CLOUD: false, IS_CLOUD: false, IS_CE: true };
/** Keycloak auth => IS_CLOUD true, yet the backend installs it (isManagedCloud false). */
const SELF_HOSTED_ENTERPRISE = { IS_MANAGED_CLOUD: false, IS_CLOUD: true, IS_CE: false };

/** Fresh module registry per edition: the edition constants are frozen at import time. */
async function renderCard(
  edition: { IS_MANAGED_CLOUD: boolean; IS_CLOUD: boolean; IS_CE: boolean },
  publication: WorkflowPublication,
) {
  vi.resetModules();
  vi.doMock('@/lib/edition', () => edition);
  const { PublicationCard } = await import('../PublicationCard');
  render(<PublicationCard publication={publication} currentUserId="me" ownedByMe={false} onAcquire={vi.fn()} />);
}

afterEach(() => {
  cleanup();
  vi.doUnmock('@/lib/edition');
  vi.resetModules();
});

describe('PublicationCard - CE-exclusive', () => {
  it('cloud: shows the badge and REMOVES the Install CTA', async () => {
    await renderCard(MANAGED_CLOUD, pub({ ceExclusive: true, ceExclusiveFeatures: ['CLI_AGENT'] }));

    expect(screen.getByTestId('ce-exclusive-badge')).toBeInTheDocument();
    expect(screen.queryByText('acquire')).toBeNull();
  });

  it('self-hosted: shows the badge AND keeps the Install CTA', async () => {
    await renderCard(COMMUNITY_EDITION, pub({ ceExclusive: true, ceExclusiveFeatures: ['CLI_AGENT'] }));

    expect(screen.getByTestId('ce-exclusive-badge')).toBeInTheDocument();
    expect(screen.getByText('acquire')).toBeInTheDocument();
  });

  it('self-hosted enterprise keeps the Install CTA (IS_CLOUD is true there, but the backend allows it)', async () => {
    await renderCard(SELF_HOSTED_ENTERPRISE, pub({ ceExclusive: true, ceExclusiveFeatures: ['CLI_AGENT'] }));

    expect(screen.getByTestId('ce-exclusive-badge')).toBeInTheDocument();
    expect(screen.getByText('acquire')).toBeInTheDocument();
  });

  it('a normal publication has no badge and keeps Install on cloud', async () => {
    await renderCard(MANAGED_CLOUD, pub({ ceExclusive: false }));

    expect(screen.queryByTestId('ce-exclusive-badge')).toBeNull();
    expect(screen.getByText('acquire')).toBeInTheDocument();
  });

  it('a publication predating the flag (field absent) is treated as installable', async () => {
    await renderCard(MANAGED_CLOUD, pub());

    expect(screen.queryByTestId('ce-exclusive-badge')).toBeNull();
    expect(screen.getByText('acquire')).toBeInTheDocument();
  });

  it('the badge is NOT inside the hover-only overlay - it is the reason the Install button is gone', async () => {
    // On cloud the card loses its Install button, so the badge is the only
    // explanation. Putting it in the `opacity-0 group-hover:opacity-100`
    // overlay would hide that explanation from every touch user, who never
    // hovers: they would see a card with no action and no reason.
    await renderCard(MANAGED_CLOUD, pub({ ceExclusive: true, ceExclusiveFeatures: ['CLI_AGENT'] }));

    const badge = screen.getByTestId('ce-exclusive-badge');
    expect(badge.closest('.group-hover\\:opacity-100')).toBeNull();
  });

  it('the badge tooltip names the features so the requirement is never a dead end', async () => {
    await renderCard(
      MANAGED_CLOUD,
      pub({ ceExclusive: true, ceExclusiveFeatures: ['CLI_AGENT', 'VECTOR_SEARCH'] }),
    );

    expect(screen.getByTestId('ce-exclusive-badge')).toHaveAttribute(
      'title',
      'ceExclusiveTooltip (ceExclusiveFeatureCliAgent, ceExclusiveFeatureVectorSearch)',
    );
  });
});
