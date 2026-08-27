/**
 * @vitest-environment jsdom
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';

/**
 * Sound on the application page.
 *
 * The application is live HTML in a sandboxed, cross-origin frame, so this page
 * can neither silence it nor see whether it has anything to play. It starts the
 * application MUTED - an app that talks the moment its page opens is startling,
 * and on a shared link the visitor had not decided to be there yet - and puts the
 * switch in the settings cog.
 *
 * The consequence this suite exists for: the cog's existence is now a
 * DISJUNCTION. An app with audio and no editable copy on offer still needs one,
 * because the cog is the only place its sound can be turned on. An app with
 * neither still gets none.
 */

const carouselProps = vi.hoisted(() => ({
  mediaMuted: undefined as boolean | undefined,
  announcePresence: null as null | ((hasAudio: boolean) => void),
}));
const cogProps = vi.hoisted(() => [] as Array<{
  canCreateEditableCopy?: boolean;
  soundMuted?: boolean;
  onToggleSound?: () => void;
}>);
const isPreviewOnly = vi.hoisted(() => ({ value: false }));
// The application's interfaces are discovered by a canvas that lives inside a
// SIDE-PANEL tab, not in the page tree. Swallowing addTab (as the sibling suites
// do) means the configs never arrive and the page renders an empty div where the
// carousel would be - so this suite captures the tab content and mounts it.
const panelContent = vi.hoisted(() => ({ node: null as unknown }));
const numericUserId = vi.hoisted(() => ({ value: 42 as number | null }));

vi.mock('@/lib/api', () => ({ orchestratorApi: { updatePublication: vi.fn() } }));
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({
    isAuthenticated: true,
    isAuthChecking: false,
    get numericUserId() { return numericUserId.value; },
  }),
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  WorkflowModeProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useWorkflowMode: () => ({
    setRunId: vi.fn(),
    get isPreviewOnly() { return isPreviewOnly.value; },
    setViewingEpoch: vi.fn(),
  }),
}));
vi.mock('@/contexts/SidePanelContext', () => ({
  useSidePanelSafe: () => ({
    addTab: (tab: { content?: unknown }) => { panelContent.node = tab.content ?? null; },
    setActiveTab: vi.fn(),
    open: vi.fn(),
    isOpen: true,
  }),
}));
vi.mock('@/components/app/WorkflowPanelContent', () => ({
  // Renders its canvas slot, which is where the interface configs come from.
  WorkflowPanelContent: ({ workflowCanvasSlot }: any) => <>{workflowCanvasSlot}</>,
  setPendingActivateTab: vi.fn(),
}));
// The canvas is what discovers the application's interfaces. Without at least
// one config the page renders an empty div instead of the carousel, so there
// would be no frame to mute and nothing to test.
vi.mock('@/components/workflow/WorkflowRunCanvas', async () => {
  const ReactMod = await import('react');
  return {
    WorkflowRunCanvas: ({ onApplicationConfigsChange }: any) => {
      ReactMod.useEffect(() => {
        onApplicationConfigsChange?.([{ interfaceId: 'iface-1', actionMapping: {} }]);
      }, [onApplicationConfigsChange]);
      return null;
    },
  };
});
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => null }));
vi.mock('@/components/marketplace/PublicationInfoPanel', () => ({ PublicationInfoPanel: () => null }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));
vi.mock('@/lib/stores/interface-pagination-store', () => ({
  useInterfacePaginationStore: { getState: () => ({ setCarouselIndex: vi.fn() }) },
}));
vi.mock('@/app/workflows/builder/utils/labelNormalizer', () => ({ normalizeLabel: (s: string) => s }));
vi.mock('../workflow/WorkflowLoadingState', () => ({ WorkflowLoadingState: () => null }));
vi.mock('../workflow/WorkflowUnauthorizedState', () => ({ WorkflowUnauthorizedState: () => null }));
vi.mock('../workflow/hooks', () => ({ useAutoCollapseSidebar: () => undefined }));

// Stand-in for the interface frames: records the mute state it is handed and
// exposes the presence callback, so a test can play the part of an application
// that turns out to contain media.
vi.mock('@/components/chat/ApplicationCarousel', () => ({
  ApplicationCarousel: (p: {
    mediaMuted?: boolean;
    onMediaAudioPresence?: (hasAudio: boolean) => void;
  }) => {
    carouselProps.mediaMuted = p.mediaMuted;
    carouselProps.announcePresence = p.onMediaAudioPresence ?? null;
    return <div data-testid="application-carousel" />;
  },
}));

// Real cog behaviour is covered by ApplicationSettingsMenu's own suite; here we
// only need what this page hands it, plus a button to drive the toggle.
vi.mock('@/components/marketplace/ApplicationSettingsMenu', () => ({
  ApplicationSettingsMenu: (p: {
    canCreateEditableCopy?: boolean;
    soundMuted?: boolean;
    onToggleSound?: () => void;
  }) => {
    cogProps.push({
      canCreateEditableCopy: p.canCreateEditableCopy,
      soundMuted: p.soundMuted,
      onToggleSound: p.onToggleSound,
    });
    return (
      <button type="button" data-testid="cog-sound" onClick={p.onToggleSound}>cog</button>
    );
  },
}));

import { ApplicationDetailView } from '@/components/views/application/ApplicationDetailView';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

function pub(over: Partial<WorkflowPublication> = {}): WorkflowPublication {
  return {
    id: 'p1',
    title: 'X',
    visibility: 'PRIVATE',
    creditsPerUse: 0,
    displayMode: 'APPLICATION',
    publisherId: '999',
    ...over,
  } as WorkflowPublication;
}

/**
 * Render the page AND the side-panel tab it registers, so the canvas inside that
 * tab reports the application's interfaces back and the carousel actually mounts.
 * The tab content is captured during the page's effect, hence the second render.
 */
function renderView(props: Partial<React.ComponentProps<typeof ApplicationDetailView>> = {}) {
  cogProps.length = 0;
  panelContent.node = null;
  const result = render(
    <ApplicationDetailView workflowId="wf-1" runId="run-1" publication={pub()} {...props} />,
  );
  if (panelContent.node) {
    act(() => { render(panelContent.node as React.ReactElement); });
  }
  return result;
}

/** Play the part of the frame reporting that it does contain media. */
function reportAudioPresent() {
  act(() => carouselProps.announcePresence?.(true));
}

const cog = () => screen.queryByTestId('cog-sound');

beforeEach(() => {
  cogProps.length = 0;
  carouselProps.mediaMuted = undefined;
  carouselProps.announcePresence = null;
  isPreviewOnly.value = false;
  numericUserId.value = 42;
});
afterEach(cleanup);

describe('ApplicationDetailView - the application starts silent', () => {
  it('mutes the application on FIRST render, before anything is known about it', () => {
    // Waiting for the frame to report back would mean the sound had already been
    // playing while it did.
    renderView();

    expect(carouselProps.mediaMuted).toBe(true);
  });

  it('tells the cog there is no sound entry to offer until the frame says there is media', () => {
    renderView();

    // undefined, not false: "nothing to hear" is a different statement from
    // "there is sound and it is currently on".
    expect(cogProps.at(-1)?.soundMuted).toBeUndefined();
  });

  it('offers the sound entry once the frame reports media', () => {
    renderView();

    reportAudioPresent();

    expect(cogProps.at(-1)?.soundMuted).toBe(true);
  });

  it('unmutes the application when the cog entry is used, and mutes it again', () => {
    renderView();
    reportAudioPresent();

    fireEvent.click(cog()!);
    expect(carouselProps.mediaMuted).toBe(false);

    fireEvent.click(cog()!);
    expect(carouselProps.mediaMuted).toBe(true);
  });
});

describe('ApplicationDetailView - audio alone justifies the cog', () => {
  it('mounts the cog for an app that has sound but no editable copy on offer', () => {
    // The publisher's own view: no clone to copy, so pre-fix there was no cog at
    // all - and therefore no way to turn the sound on.
    renderView({ publication: pub({ publisherId: '42' }) });
    expect(cog()).toBeNull();

    reportAudioPresent();

    expect(cog()).not.toBeNull();
    expect(cogProps.at(-1)?.canCreateEditableCopy).toBe(false);
    expect(cogProps.at(-1)?.soundMuted).toBe(true);
  });

  it('still mounts no cog for an app with neither sound nor a copy to offer', () => {
    renderView({ publication: pub({ publisherId: '42' }) });

    expect(cog()).toBeNull();
  });

  it('keeps passing canCreateEditableCopy so the copy entry is unaffected', () => {
    renderView();

    expect(cogProps.at(-1)?.canCreateEditableCopy).toBe(true);
  });
});
