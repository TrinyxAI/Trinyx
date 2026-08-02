// @vitest-environment jsdom
/**
 * The agent panel's bottom sub-tab bar must use the SHARED panel tab style
 * (`panelTabClass`), on a `bg-theme-primary` bar - the surface the inactive
 * hover and the Button focus-ring offset both assume.
 */
import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/api', () => ({
  orchestratorApi: { getAgent: vi.fn(), getAgentAvatars: () => Promise.resolve([]) },
}));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => null }));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => <span /> }));
vi.mock('@/components/agent-fleet/AgentFleetPanelContent', () => ({
  AgentFleetPanelContent: () => <div data-testid="fleet-panel" />,
}));
vi.mock('../AgentConversationPanelContent', () => ({ AgentConversationPanelContent: () => <div /> }));
vi.mock('../ConversationPanelContent', () => ({ ConversationPanelContent: () => <div /> }));

import { panelTabClass } from '@/components/ui/panel-tab';
import { AgentPanelContent } from '../AgentPanelContent';

afterEach(cleanup);

function subTabs(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>('[data-testid="panel-sub-tab"]'));
}
/**
 * Whole-token membership, NOT substring: `toContain('bg-[var(--bg-hover)]')`
 * is also satisfied by `hover:bg-[var(--bg-hover)]`, so a tab that lost its
 * resting fill would pass with no visible active state.
 */
function tokens(el: HTMLElement): string[] {
  return el.className.split(/\s+/).filter(Boolean);
}

describe('AgentPanelContent bottom sub-tabs', () => {
  it('styles both sub-tabs with the shared compact panel tab class', () => {
    const { container } = render(<AgentPanelContent agentId="a1" />);
    const [configuration, conversation] = subTabs(container);
    expect(configuration.className).toContain(panelTabClass(true, 'sm'));
    expect(conversation.className).toContain(panelTabClass(false, 'sm'));
  });

  it('moves the active style and aria state to the tab that was clicked', () => {
    const { container } = render(<AgentPanelContent agentId="a1" />);
    fireEvent.click(subTabs(container)[1]);
    const [configuration, conversation] = subTabs(container);
    expect(conversation.getAttribute('aria-pressed')).toBe('true');
    expect(tokens(conversation)).toContain('bg-[var(--bg-hover)]');
    expect(configuration.getAttribute('aria-pressed')).toBe('false');
    expect(tokens(configuration)).not.toContain('bg-[var(--bg-hover)]');
  });

  it('puts the bar on the primary surface, which the hover + focus-ring offset assume', () => {
    const { container } = render(<AgentPanelContent agentId="a1" />);
    const bar = subTabs(container)[0].closest('div')!.parentElement!;
    expect(bar.className).toContain('bg-theme-primary');
    expect(bar.className).not.toContain('bg-theme-secondary');
  });
});
