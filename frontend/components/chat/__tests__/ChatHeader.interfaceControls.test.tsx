// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * The controls button beside Edit, on the page being viewed at /app/interface/<id>.
 *
 * What is pinned: it appears only when the page has said there is something to control. An
 * inert button next to Edit would be worse than none - it promises a panel and opens onto
 * nothing - so the handler is what decides, and the header is given one only while the viewer
 * is offering something. Both layouts carry it: the desktop row and the mobile row are separate
 * JSX, and a control added to one of them is missing on half the devices.
 */
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  usePathname: () => '/en/app/c/conv-1',
}));
vi.mock('next/image', () => ({
  // eslint-disable-next-line @next/next/no-img-element
  default: ({ src, alt }: { src: string; alt: string }) => <img src={src} alt={alt} />,
}));
vi.mock('@/hooks/useModels', () => ({
  modelMatches: () => false,
  selectedModelFromAIModel: (m: unknown) => m,
}));
vi.mock('@/components/ai/ModelInfo', () => ({ ModelOptionDisplay: () => null, ModelInfoPopover: () => null }));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ user: null, isAuthenticated: true, isLoading: false }),
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => ({ isRunMode: false }) }));
vi.mock('@/lib/api', () => ({ orchestratorApi: {} }));
vi.mock('@/components/chat/workflowUtils', () => ({ isWorkflowMessage: () => false }));
vi.mock('@/components/chat/DataSourceMessage', () => ({ isDataSourceMessage: () => false }));
vi.mock('@/app/workflows/builder/components/inspector/StepDataTable', () => ({ StepDataTable: () => null }));
vi.mock('@/components/WorkflowRunResultModalContent', () => ({ WorkflowRunResultModalContent: () => null }));
vi.mock('@/components/workflow/ShareWorkflowModal', () => ({ PublishWorkflowModal: () => null }));
vi.mock('@/components/workflow/WorkflowVersionHistory', () => ({ WorkflowSaveWithVersions: () => null }));
vi.mock('@/components/marketplace/MarketplaceHeaderActions', () => ({ MarketplaceHeaderActions: () => null }));
vi.mock('@/components/chat/NotificationBell', () => ({ NotificationBell: () => null }));
vi.mock('@/components/applications/ApplicationActivationButton', () => ({ ApplicationActivationButton: () => null }));
vi.mock('@/components/agents/AvatarPicker', () => ({
  AvatarDisplay: ({ name }: { name?: string }) => <div>{name}</div>,
}));

import { ChatHeader } from '../ChatHeader';
import { SidePanelLayoutProvider } from '@/contexts/SidePanelLayoutContext';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';

const baseProps = {
  selectedModel: { provider: 'openai', id: 'gpt-4' },
  onModelChange: vi.fn(),
  availableModels: [],
  showModelSelector: false,
  onShowModelSelector: vi.fn(),
  sidebarOpen: false,
  onSidebarToggle: vi.fn(),
  onToggleAgentConfigPanel: vi.fn(),
  showAgentConfigPanel: false,
  isInterfacePage: true,
  interfaceId: 'i1',
  onEditInterface: vi.fn(),
} as unknown as React.ComponentProps<typeof ChatHeader>;

beforeEach(() => {
  window.localStorage.clear();
  act(() => useCurrentOrgStore.getState().clear());
});
afterEach(cleanup);

function renderHeader(props: Partial<React.ComponentProps<typeof ChatHeader>> = {}) {
  render(
    <SidePanelLayoutProvider>
      <ChatHeader {...baseProps} {...props} />
    </SidePanelLayoutProvider>,
  );
}

describe('ChatHeader - the page controls button', () => {
  it('is absent while the page has nothing to control', () => {
    renderHeader({ onToggleInterfaceControls: undefined });

    expect(screen.queryAllByTestId('interface-controls-toggle')).toHaveLength(0);
  });

  it('appears once the page offers something', () => {
    renderHeader({ onToggleInterfaceControls: vi.fn() });

    expect(screen.getAllByTestId('interface-controls-toggle').length).toBeGreaterThan(0);
  });

  it('is carried by BOTH layouts, so it is not missing on half the devices', () => {
    renderHeader({ onToggleInterfaceControls: vi.fn() });

    // The desktop row and the mobile row are separate JSX branches, both in the tree.
    expect(screen.getAllByTestId('interface-controls-toggle')).toHaveLength(2);
  });

  it('opens the controls when pressed', () => {
    const onToggleInterfaceControls = vi.fn();
    renderHeader({ onToggleInterfaceControls });

    fireEvent.click(screen.getAllByTestId('interface-controls-toggle')[0]);

    expect(onToggleInterfaceControls).toHaveBeenCalledTimes(1);
  });

  it('sits beside Edit rather than replacing it', () => {
    renderHeader({ onToggleInterfaceControls: vi.fn() });

    expect(screen.getAllByTestId('interface-controls-toggle').length).toBeGreaterThan(0);
    expect(screen.getAllByTitle('actions.edit').length).toBeGreaterThan(0);
  });

  it('stays away on a page that is not an interface', () => {
    renderHeader({ isInterfacePage: false, onToggleInterfaceControls: vi.fn() });

    expect(screen.queryAllByTestId('interface-controls-toggle')).toHaveLength(0);
  });
});

describe('ChatHeader - what the controls button says', () => {
  it('says the panel is closed', () => {
    renderHeader({ onToggleInterfaceControls: vi.fn(), interfaceControlsOpen: false });

    expect(screen.getAllByTestId('interface-controls-toggle')[0])
      .toHaveAttribute('aria-expanded', 'false');
  });

  it('says the panel is open, so pressing it again reads as closing it', () => {
    renderHeader({ onToggleInterfaceControls: vi.fn(), interfaceControlsOpen: true });

    expect(screen.getAllByTestId('interface-controls-toggle')[0])
      .toHaveAttribute('aria-expanded', 'true');
  });

  it('names the sound as playing, rather than leaving it to a colour', () => {
    // The panel can be dismissed with the sound still going. A tint says nothing to a screen
    // reader, and nothing at all to someone who cannot tell the two shades apart.
    renderHeader({ onToggleInterfaceControls: vi.fn(), interfaceSoundOn: true });

    expect(screen.getAllByTestId('interface-controls-toggle')[0])
      .toHaveAttribute('aria-label', 'actions.interfaceControlsSoundOn');
  });

  it('names it plainly while the page is silent', () => {
    renderHeader({ onToggleInterfaceControls: vi.fn(), interfaceSoundOn: false });

    expect(screen.getAllByTestId('interface-controls-toggle')[0])
      .toHaveAttribute('aria-label', 'actions.interfaceControls');
  });

  it('also tints, for the reader who takes it in at a glance', () => {
    renderHeader({ onToggleInterfaceControls: vi.fn(), interfaceSoundOn: true });

    expect(screen.getAllByTestId('interface-controls-toggle')[0].className)
      .toContain('text-[var(--accent-primary)]');
  });

  it('points at the panel it opens', () => {
    renderHeader({ onToggleInterfaceControls: vi.fn() });

    expect(screen.getAllByTestId('interface-controls-toggle')[0])
      .toHaveAttribute('aria-controls', 'interface-viewer-controls');
  });
});
