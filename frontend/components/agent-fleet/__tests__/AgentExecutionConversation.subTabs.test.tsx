// @vitest-environment jsdom
/**
 * The bottom sub-tab bar of the execution-conversation side-panel tab must use
 * the SHARED panel tab style (`panelTabClass`), not its own copy. The bar itself
 * must sit on `bg-theme-primary`, which is what makes the inactive hover surface
 * and the Button focus-ring offset visible.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/components/agent-fleet/LoadOlderSentinel', () => ({ LoadOlderSentinel: () => null }));
vi.mock('@/hooks/agent/useExecutionPagedResource', () => ({
  useExecutionPagedResource: () => ({ items: [], loading: false, hasMore: false, loadingOlder: false, loadOlder: vi.fn() }),
}));
vi.mock('@/lib/api/orchestrator/agent.service', () => ({
  agentService: {
    getExecutionToolCallsPaged: function getExecutionToolCallsPaged() {},
    getExecutionConversationPaged: function getExecutionConversationPaged() {},
  },
}));

import { panelTabClass } from '@/components/ui/panel-tab';
import { AgentExecutionConversation } from '../AgentExecutionDetail';

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

describe('AgentExecutionConversation bottom sub-tabs', () => {
  it('styles both sub-tabs with the shared compact panel tab class', () => {
    const { container } = render(<AgentExecutionConversation executionId="e1" />);
    const [conversation, toolCalls] = subTabs(container);
    expect(conversation.className).toContain(panelTabClass(true, 'sm'));
    expect(toolCalls.className).toContain(panelTabClass(false, 'sm'));
  });

  it('moves the active style to the tab that was clicked', () => {
    const { container } = render(<AgentExecutionConversation executionId="e1" />);
    fireEvent.click(subTabs(container)[1]);
    const [conversation, toolCalls] = subTabs(container);
    expect(toolCalls.getAttribute('aria-pressed')).toBe('true');
    expect(tokens(toolCalls)).toContain('bg-[var(--bg-hover)]');
    expect(conversation.getAttribute('aria-pressed')).toBe('false');
    expect(tokens(conversation)).not.toContain('bg-[var(--bg-hover)]');
  });

  it('puts the bar on the primary surface, which the hover + focus-ring offset assume', () => {
    const { container } = render(<AgentExecutionConversation executionId="e1" />);
    const bar = subTabs(container)[0].closest('div')!.parentElement!;
    expect(bar.className).toContain('bg-theme-primary');
    expect(bar.className).not.toContain('bg-theme-secondary');
  });
});
