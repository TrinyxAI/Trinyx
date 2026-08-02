/**
 * @vitest-environment jsdom
 *
 * The side-panel header tabs are buttons, not browser-style tabs: each one
 * carries the shared `panelTabClass` style (Button shape + focus ring), the
 * active one is the solid accent and the inactive ones only get a hover surface.
 * The header keeps its original 56px (h-14) height while doing so.
 */
import fs from 'node:fs';
import path from 'node:path';
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
  usePathname: () => '/app/c/conv-1',
}));
vi.mock('@/hooks/useMobileDetection', () => ({ useMobileDetection: () => false }));
vi.mock('@/hooks/useMouseResize', () => ({
  useMouseResize: () => ({ isResizing: false, startResize: vi.fn(), hasManuallyResizedRef: { current: false } }),
}));
vi.mock('@/contexts/SharedConversationContext', () => ({ useSharedConversation: () => null }));
vi.mock('@/components/app/AddTabPicker', () => ({ AddTabPicker: () => null }));
vi.mock('@/components/ui/PanelResizeHandle', () => ({ PanelResizeHandle: () => null }));
vi.mock('@/components/ui/BulkDeleteModal', () => ({ BulkDeleteModal: () => null }));
vi.mock('@/lib/api', () => ({ orchestratorApi: {} }));

import { SidePanelProvider, useSidePanel, type SidePanelTab } from '@/contexts/SidePanelContext';
import { SidePanel } from '@/components/app/SidePanel';

afterEach(cleanup);

const tab = (id: string, pinned = false): SidePanelTab => ({
  id,
  label: id,
  icon: <span />,
  pinned,
  content: <div>{id}</div>,
});

function Opener({ tabs }: { tabs: SidePanelTab[] }) {
  const sp = useSidePanel();
  const done = React.useRef(false);
  React.useEffect(() => {
    if (done.current) return;
    done.current = true;
    tabs.forEach((t) => sp.openTab(t));
  }, [sp, tabs]);
  return null;
}

function renderPanel(ids: Array<string | SidePanelTab>) {
  const tabs = ids.map((t) => (typeof t === 'string' ? tab(t) : t));
  const utils = render(
    <SidePanelProvider>
      <Opener tabs={tabs} />
      <SidePanel />
    </SidePanelProvider>,
  );
  act(() => { /* flush the opening effect */ });
  return utils;
}

function tabButtons(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>('[data-testid="side-panel-tab"]'));
}

/**
 * Whole-token membership, NOT substring: `toContain('bg-[var(--bg-hover)]')`
 * is also satisfied by `hover:bg-[var(--bg-hover)]`, so a tab that lost its
 * resting fill would pass a substring check with no visible active state.
 */
function tokens(el: HTMLElement): string[] {
  return el.className.split(/\s+/).filter(Boolean);
}

describe('SidePanel header tabs', () => {
  it('renders one button per tab and marks only the active one', () => {
    const { container } = renderPanel(['alpha', 'beta']);
    const buttons = tabButtons(container);
    expect(buttons).toHaveLength(2);
    // openTab() activates the last opened tab.
    expect(buttons.filter((b) => b.getAttribute('data-active') === 'true')).toHaveLength(1);
    expect(buttons[1].getAttribute('data-active')).toBe('true');
    expect(buttons[0].hasAttribute('data-active')).toBe(false);
  });

  it('exposes the active state to assistive tech, which cannot see the color fill', () => {
    const { container } = renderPanel(['alpha', 'beta']);
    const buttons = tabButtons(container);
    expect(buttons[1].getAttribute('aria-pressed')).toBe('true');
    expect(buttons[0].getAttribute('aria-pressed')).toBe('false');
  });

  it('gives the active tab a resting chrome selected-item fill, not a solid accent block', () => {
    const { container } = renderPanel(['alpha', 'beta']);
    const active = tabButtons(container)[1];
    expect(tokens(active)).toContain('bg-[var(--bg-hover)]');
    expect(tokens(active)).not.toContain('bg-transparent');
    expect(tokens(active)).toContain('border-[var(--border-color)]');
    expect(tokens(active)).toContain('font-semibold');
    expect(tokens(active)).not.toContain('bg-[var(--accent-primary)]');
  });

  it('gives an inactive tab a hover surface instead of a permanent background', () => {
    const { container } = renderPanel(['alpha', 'beta']);
    const inactive = tabButtons(container)[0];
    expect(tokens(inactive)).toContain('bg-transparent');
    expect(inactive.className).toContain('hover:bg-[var(--bg-tertiary)]');
    expect(inactive.className).toContain('hover:text-[var(--text-primary)]');
    expect(inactive.className).not.toContain('bg-[var(--bg-hover)]');
  });

  it('every tab carries the shared button focus ring', () => {
    const { container } = renderPanel(['alpha', 'beta']);
    for (const button of tabButtons(container)) {
      expect(button.className).toContain('focus-visible:ring-2');
      expect(button.className).toContain('focus-visible:ring-[var(--accent-primary)]');
      expect(button.className).toContain('rounded-xl');
    }
  });

  it('drops the browser-tab chrome (no rounded-t / negative margin / browser-tab-active)', () => {
    const { container } = renderPanel(['alpha', 'beta']);
    for (const button of tabButtons(container)) {
      expect(button.className).not.toContain('browser-tab-active');
      expect(button.className).not.toContain('rounded-t-');
      expect(button.className).not.toContain('-mb-px');
    }
  });

  it('keeps the header row at its original h-14 height', () => {
    const { container } = renderPanel(['alpha']);
    const row = tabButtons(container)[0].closest('.h-14');
    expect(row).toBeTruthy();
  });

  it('reserves room for the close affordance on closeable tabs only', () => {
    const { container } = renderPanel(['alpha']);
    expect(tabButtons(container)[0].className).toContain('pr-8');
  });

  it('a pinned tab has no close affordance, so it keeps the symmetric padding', () => {
    const { container } = renderPanel([tab('pinned-one', true)]);
    const button = tabButtons(container)[0];
    expect(button.className).toContain('pr-3');
    expect(button.className).not.toContain('pr-8');
  });

  it('the pin icon inherits the tab text color so it stays readable on the inverted active fill', () => {
    // Two tabs, so the drag handle also renders: the pin must be found by its own
    // marker (lucide sets `lucide-pin`), never by "the first svg in the tab".
    const { container } = renderPanel([tab('alpha'), tab('pinned-one', true)]);
    const pin = container.querySelector('[data-testid="side-panel-tab"] svg.lucide-pin');
    expect(pin).toBeTruthy();
    expect(pin!.getAttribute('class')).toContain('text-current');
    // The old hardcoded slate was unreadable on the near-black active surface.
    expect(pin!.getAttribute('class')).not.toContain('text-slate-400');
  });

  it('wires the nested close control to the tab state (neutral tint on active, surface on inactive)', () => {
    const { container } = renderPanel(['alpha', 'beta']);
    const [inactive, active] = tabButtons(container);
    // Whole tokens: `group-hover:bg-black/20` would satisfy a substring check
    // while firing on TAB hover rather than on the control itself.
    const closeOf = (b: HTMLElement) =>
      b.querySelector('[role="button"]')!.getAttribute('class')!.split(/\s+/).filter(Boolean);
    expect(closeOf(active)).toContain('hover:bg-black/20');
    expect(closeOf(active)).toContain('dark:hover:bg-white/20');
    expect(closeOf(inactive)).toContain('hover:bg-[var(--bg-hover)]');
    expect(closeOf(inactive)).not.toContain('hover:bg-black/20');
  });

  it('the browser-tab CSS block is gone from globals.css, not just off the element', () => {
    const css = fs.readFileSync(
      path.resolve(__dirname, '../../../app/globals.css'),
      'utf8',
    );
    expect(css).not.toContain('browser-tab-active');
  });
});
