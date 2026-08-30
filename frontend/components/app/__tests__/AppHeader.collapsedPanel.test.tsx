/**
 * @vitest-environment jsdom
 *
 * The app header's side-panel button against a COLLAPSED detached window.
 *
 * "Open" and "forward" are not the same thing: a detached window shaded to a strip
 * is open and shows nothing. A toggle that branches on `isOpen` therefore dismisses
 * the very panel the user pressed the button to see, and it takes a second press,
 * plus the panel disappearing in between, to get it back.
 *
 * The other half is subtler: the open branch opens a SPECIFIC tab, which is right
 * for a closed panel and wrong for a shaded one - the user collapsed a window
 * showing something, and the button must give them that window back rather than
 * swap them onto the Agent or Workflow tab.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key, useLocale: () => 'en' }));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  usePathname: () => '/en/app/c/conv-1',
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({ locale: 'en' }),
}));

import { SidePanelProvider, useSidePanel, type SidePanelTab } from '@/contexts/SidePanelContext';
import { togglePanelFromHeader } from '@/lib/sidePanel/togglePanelFromHeader';

/**
 * The real `togglePanelFromHeader`, driven against the real provider.
 *
 * The header itself pulls in the router, the org store, streaming and billing, none
 * of which takes part in this decision, which is why the decision is exported. What
 * matters is that the four lines under test are the ones that run in the app: an
 * earlier version of this file restated them, and deleting the un-shade guard from
 * the header left the whole suite green.
 */
function HeaderToggle() {
  const sidePanel = useSidePanel();
  const onClick = () => {
    togglePanelFromHeader(sidePanel, (panel) => {
      panel.openTab({
        id: 'agent-config', label: 'Agent', icon: <span />, content: <div>agent</div>,
      } as SidePanelTab);
    });
  };
  return (
    <>
      <button type="button" data-testid="header-toggle" onClick={onClick} />
      <span data-testid="state">{`${sidePanel.isOpen}|${sidePanel.collapsed}|${sidePanel.activeTabId ?? ''}|${sidePanel.isForward}`}</span>
    </>
  );
}

/** Opens a tab that is NOT the one the header's open branch would pick. */
function OpenFiles() {
  const sp = useSidePanel();
  const done = React.useRef(false);
  React.useEffect(() => {
    if (done.current) return;
    done.current = true;
    sp.openTab({ id: 'files', label: 'Files', icon: <span />, content: <div>files</div> } as SidePanelTab);
  }, [sp]);
  return <button type="button" data-testid="collapse" onClick={() => sp.setCollapsed(true)} />;
}

function renderHeader() {
  render(
    <SidePanelProvider>
      <OpenFiles />
      <HeaderToggle />
    </SidePanelProvider>,
  );
}

const state = () => screen.getByTestId('state').textContent;
const press = () => act(() => { screen.getByTestId('header-toggle').click(); });
const collapse = () => act(() => { screen.getByTestId('collapse').click(); });

beforeEach(() => { window.localStorage.clear(); });
afterEach(cleanup);

describe('AppHeader panel toggle vs a collapsed window', () => {
  it('restores a shaded window, keeping the tab the user left it on', () => {
    // Two things at once, and the second is the subtle one. The press must not
    // DISMISS a panel the user merely collapsed, and it must give back the tab that
    // was shaded rather than the header's own: a closed panel gets `agent-config`,
    // but a shaded one already holds content the user chose, and swapping it is a
    // behaviour change only they can see.
    renderHeader();
    collapse();
    expect(state(), 'open and shaded').toBe('true|true|files|false');

    press();

    expect(state(), 'forward again, still on files, nothing closed').toBe('true|false|files|true');
  });

  it('still opens its own tab when the panel is genuinely closed', () => {
    // The un-shade branch must not swallow the ordinary case.
    renderHeader();
    press();               // open -> forward -> closes
    expect(state()).toBe('false|false|files|false');

    press();

    expect(state(), 'a closed panel gets the header tab').toBe('true|false|agent-config|true');
  });

  it('does nothing at all outside a SidePanelProvider', () => {
    // The header renders on routes that have no panel; the null guard is what keeps
    // the button inert there rather than throwing on the first press.
    expect(() => togglePanelFromHeader(null, () => {
      throw new Error('the open branch must not run without a panel');
    })).not.toThrow();
  });

  it('reports a shaded window as NOT forward, which is the whole distinction', () => {
    // Every toggle in the app asks this question. Answering it with `isOpen` is
    // what made them dismiss a panel the user had merely collapsed.
    renderHeader();
    expect(state(), 'open and showing').toBe('true|false|files|true');
    collapse();
    expect(state(), 'open, but showing nothing').toBe('true|true|files|false');
  });

  it('still closes a panel that is open and showing', () => {
    renderHeader();
    expect(state()).toBe('true|false|files|true');

    press();

    expect(state()).toBe('false|false|files|false');
  });
});
