/**
 * @vitest-environment jsdom
 *
 * A media card that finishes loading must not pop open a panel the user shaded.
 *
 * Both cards auto-open their detail view once per session, deliberately ONLY when
 * the panel is already showing something: popping one the user closed would be
 * intrusive. A window collapsed to a strip reads as open, so the guard has to ask
 * `isForward`, or the card the chat happens to render next yanks a window the user
 * just collapsed back to full size, unprompted, and the collapse looks broken.
 *
 * The real cards are rendered against the real provider, because the value under
 * test is read inside their effects.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/lib/api/storage-api', () => ({
  storageApi: {
    getEntryPreview: vi.fn().mockResolvedValue({
      id: 'file-1', name: 'shot.png', mimeType: 'image/png', s3Key: null, sizeBytes: 10,
    }),
  },
}));
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getInterface: vi.fn().mockResolvedValue({
      id: 'iface-1', name: 'Gen', data: { images: [{ path: 'p/one.png', prompt: 'a cat' }] },
    }),
  },
}));
vi.mock('@/lib/api/orchestrator/file.service', () => ({
  fileService: { formatFileSize: (n: number) => `${n} B` },
  getFileUrlById: () => 'blob:file',
  fileRefToUrl: () => 'blob:img',
}));
vi.mock('@/hooks/useAuthedObjectUrl', () => ({ useAuthedObjectUrl: () => ({ url: null }) }));
vi.mock('@/app/workflows/builder/components/inspector/StorageExplorerTab', () => ({
  StorageExplorerTab: () => null,
}));
vi.mock('@/components/app/FileDetailView', () => ({ FileDetailView: () => null }));

import { SidePanelProvider, useSidePanel, type SidePanelTab } from '@/contexts/SidePanelContext';
import { FileVisualizeCard } from '@/components/chat/FileVisualizeCard';
import { ImageGenerationVisualizeCard } from '@/components/chat/ImageGenerationVisualizeCard';
import { FILES_TAB_ID } from '@/lib/sidePanel/openFilesPanel';

/** Opens a tab first, so the panel is genuinely open before anything collapses it. */
function Seed({ collapse }: { collapse: boolean }) {
  const sp = useSidePanel();
  const done = React.useRef(false);
  React.useEffect(() => {
    if (done.current) return;
    done.current = true;
    sp.openTab({ id: 'seed', label: 'Seed', icon: <span />, content: <div /> } as SidePanelTab);
    if (collapse) sp.setCollapsed(true);
  }, [sp, collapse]);
  return <span data-testid="active">{sp.activeTabId ?? ''}</span>;
}

function Closed() {
  const sp = useSidePanel();
  return <span data-testid="active">{sp.activeTabId ?? ''}</span>;
}

/** Un-shades the way the header button does, so the deferred effect gets its chance. */
function Expander() {
  const sp = useSidePanel();
  return <button type="button" data-testid="expand" onClick={() => sp.bringForward()} />;
}

/** Opens the panel later, from somewhere unrelated to this card. */
function LateOpener() {
  const sp = useSidePanel();
  return (
    <button
      type="button"
      data-testid="open-later"
      onClick={() => sp.openTab({ id: 'later', label: 'L', icon: <span />, content: <div /> } as SidePanelTab)}
    />
  );
}

const active = () => screen.getByTestId('active').textContent;

beforeEach(() => {
  window.localStorage.clear();
  window.sessionStorage.clear();
});
afterEach(cleanup);

describe.each([
  ['FileVisualizeCard', () => <FileVisualizeCard fileId="file-1" />],
  ['ImageGenerationVisualizeCard', () => <ImageGenerationVisualizeCard interfaceId="iface-1" />],
])('%s auto-open guard', (_name, card) => {
  it('auto-opens its detail view when the panel is forward', async () => {
    // The positive case, so the guard cannot pass by never auto-opening at all.
    render(<SidePanelProvider><Seed collapse={false} />{card()}</SidePanelProvider>);

    await waitFor(() => expect(active()).toBe(FILES_TAB_ID));
  });

  it('does NOT yank open a window the user collapsed', async () => {
    render(<SidePanelProvider><Seed collapse />{card()}</SidePanelProvider>);

    // Give the card's fetch and effect every chance to fire.
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    await new Promise((r) => setTimeout(r, 30));

    expect(active(), 'the shaded window kept the tab the user left it on').toBe('seed');
  });

  it('does not steal the click that brings the shaded window back', async () => {
    // Skipping is not enough: the effect depends on the context value, which changes
    // the instant the shade lifts, so a pass that merely returned fires again on the
    // very render the user's press causes. They press to get their window back and
    // land on this card's detail view instead - the exact swap the un-shade exists to
    // prevent, and in the one configuration collapsing is for.
    render(<SidePanelProvider><Seed collapse /><Expander />{card()}</SidePanelProvider>);
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });

    act(() => { screen.getByTestId('expand').click(); });
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    await new Promise((r) => setTimeout(r, 30));

    expect(active(), 'the press was spent opening this card instead').toBe('seed');
  });

  it('does not come back later, once it has declined', async () => {
    // The second half of claiming the latch, and the one that is easy to miss: the
    // effect depends on the context value, so a card that loaded against a CLOSED
    // panel used to fire the next time the panel opened for any reason at all - a
    // detail view arriving on top of whatever the user had just asked for, minutes
    // later. The one-shot is spent by the decision, not by the opening.
    render(<SidePanelProvider><Closed /><LateOpener />{card()}</SidePanelProvider>);
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    expect(active(), 'nothing popped while closed').toBe('');

    act(() => { screen.getByTestId('open-later').click(); });
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    await new Promise((r) => setTimeout(r, 30));

    expect(active(), 'the card hijacked an unrelated opening').toBe('later');
  });

  it('does not pop a panel that is closed', async () => {
    render(<SidePanelProvider><Closed />{card()}</SidePanelProvider>);

    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    await new Promise((r) => setTimeout(r, 30));

    expect(active()).toBe('');
  });
});
