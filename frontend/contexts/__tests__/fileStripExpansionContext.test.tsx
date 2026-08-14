// @vitest-environment jsdom
/**
 * FileStripExpansionContext - the canvas-wide expand/collapse registry behind
 * the toolbar's toggle-all control.
 *
 * The behaviour that matters most here is what happens when a strip UNMOUNTS.
 * The builder canvas runs ReactFlow with `onlyRenderVisibleElements`, so simply
 * panning the graph unmounts and remounts nodes constantly. The first
 * implementation pruned the expanded set on unmount, which folded previews back
 * on their own as the user scrolled - that regression is pinned below, together
 * with the bulk actions reaching strips that are not on screen at all.
 */
import React from 'react';
import { describe, it, expect, beforeEach } from 'vitest';
import { render, act } from '@testing-library/react';
import { renderToString } from 'react-dom/server';

import {
  FileStripExpansionProvider,
  useFileStripExpansionSafe,
  type FileStripExpansionContextValue,
} from '../FileStripExpansionContext';

/** Captures the live context value so a test can drive it imperatively. */
function Probe({ sink }: { sink: { current: FileStripExpansionContextValue | null } }) {
  sink.current = useFileStripExpansionSafe();
  return null;
}

/** A strip stand-in: registers on mount, unregisters on unmount. */
function Strip({ id }: { id: string }) {
  const ctx = useFileStripExpansionSafe();
  const register = ctx?.registerStrip;
  const unregister = ctx?.unregisterStrip;
  React.useEffect(() => {
    if (!register || !unregister) return;
    register(id);
    return () => unregister(id);
  }, [register, unregister, id]);
  return <div data-testid={`strip-${id}`} data-expanded={ctx ? String(ctx.isExpanded(id)) : 'none'} />;
}

/** Renders a canvas whose visible strips and reset identity can both change. */
const renderCanvas = (ids: string[], resetKey?: string) => {
  const sink: { current: FileStripExpansionContextValue | null } = { current: null };
  const tree = (visible: string[], key?: string) => (
    <FileStripExpansionProvider resetKey={key}>
      <Probe sink={sink} />
      {visible.map((id) => <Strip key={id} id={id} />)}
    </FileStripExpansionProvider>
  );
  const view = render(tree(ids, resetKey));
  return { ...view, sink, show: (visible: string[], key?: string) => view.rerender(tree(visible, key)) };
};

// The default is now a REMEMBERED preference, so a case that expands leaves that
// choice behind for the next one exactly as it would for the next visit. Clearing it
// here is what keeps each case describing its own scenario instead of the previous
// one's leftovers - and the storage-backed behaviour gets its own describe below.
beforeEach(() => {
  window.localStorage.clear();
});

describe('FileStripExpansionContext - registry', () => {
  it('counts every strip seen and starts with all of them collapsed', () => {
    const { sink } = renderCanvas(['a', 'b', 'c']);
    expect(sink.current?.stripCount).toBe(3);
    expect(sink.current?.expandedCount).toBe(0);
  });

  it('registering the same id twice does not double-count it (StrictMode / remount safe)', () => {
    const { sink } = renderCanvas(['a']);
    act(() => { sink.current?.registerStrip('a'); });
    expect(sink.current?.stripCount).toBe(1);
  });

  it('a strip scrolled out of the viewport keeps counting, so the toolbar control does not blink off mid-pan', () => {
    const { sink, show } = renderCanvas(['a', 'b']);
    // ReactFlow virtualization unmounts the node; nothing about the run changed.
    show(['a']);
    expect(sink.current?.stripCount).toBe(2);
  });
});

describe('FileStripExpansionContext - virtualized scrolling (regression)', () => {
  it('an EXPANDED strip that scrolls off-screen and back comes back EXPANDED', () => {
    const { sink, show, getByTestId } = renderCanvas(['a', 'b']);
    act(() => { sink.current?.expandAll(); });
    expect(getByTestId('strip-a').dataset.expanded).toBe('true');

    show(['b']);   // 'a' leaves the viewport
    show(['a', 'b']); // and comes back

    // Pre-fix, unregisterStrip pruned 'a' from the expanded set on unmount, so
    // panning across the graph silently folded previews back one by one.
    expect(getByTestId('strip-a').dataset.expanded).toBe('true');
    expect(sink.current?.expandedCount).toBe(2);
  });

  it('a strip expanded BY HAND also survives the round trip', () => {
    const { sink, show, getByTestId } = renderCanvas(['a', 'b']);
    act(() => { sink.current?.setExpanded('a', true); });

    show(['b']);
    show(['a', 'b']);

    expect(getByTestId('strip-a').dataset.expanded).toBe('true');
    expect(getByTestId('strip-b').dataset.expanded).toBe('false');
  });

  it('a strip COLLAPSED by hand under an expand-all stays collapsed across the round trip', () => {
    const { sink, show, getByTestId } = renderCanvas(['a', 'b']);
    act(() => { sink.current?.expandAll(); });
    act(() => { sink.current?.setExpanded('a', false); });

    show(['b']);
    show(['a', 'b']);

    expect(getByTestId('strip-a').dataset.expanded).toBe('false');
    expect(getByTestId('strip-b').dataset.expanded).toBe('true');
    expect(sink.current?.expandedCount).toBe(1);
  });

  it('unmounting a strip never changes the expanded count on its own', () => {
    const { sink, show } = renderCanvas(['a', 'b']);
    act(() => { sink.current?.expandAll(); });
    show([]);
    expect(sink.current?.expandedCount).toBe(2);
    expect(sink.current?.stripCount).toBe(2);
  });
});

describe('FileStripExpansionContext - per-strip state', () => {
  it('setExpanded flips exactly one strip and leaves its siblings alone', () => {
    const { sink, getByTestId } = renderCanvas(['a', 'b']);
    act(() => { sink.current?.setExpanded('a', true); });
    expect(getByTestId('strip-a').dataset.expanded).toBe('true');
    expect(getByTestId('strip-b').dataset.expanded).toBe('false');
    expect(sink.current?.expandedCount).toBe(1);
  });

  it('a strip stays registered when it expands - the registry must not churn on a toggle', () => {
    const { sink } = renderCanvas(['a', 'b']);
    act(() => { sink.current?.setExpanded('a', true); });
    // The context value gets a new identity on every expand; a strip that
    // re-registered on that would unregister itself and collapse instantly.
    expect(sink.current?.stripCount).toBe(2);
    expect(sink.current?.isExpanded('a')).toBe(true);
  });

  it('setting the value a strip already has is a no-op on the expanded count', () => {
    const { sink } = renderCanvas(['a']);
    act(() => { sink.current?.setExpanded('a', false); });
    expect(sink.current?.expandedCount).toBe(0);
  });
});

describe('FileStripExpansionContext - bulk actions', () => {
  it('expandAll opens every strip', () => {
    const { sink, getByTestId } = renderCanvas(['a', 'b', 'c']);
    act(() => { sink.current?.expandAll(); });
    expect(sink.current?.expandedCount).toBe(3);
    expect(getByTestId('strip-a').dataset.expanded).toBe('true');
    expect(getByTestId('strip-c').dataset.expanded).toBe('true');
  });

  it('expandAll reaches a node the user has NEVER scrolled to: it arrives expanded', () => {
    const { sink, show, getByTestId } = renderCanvas(['a']);
    act(() => { sink.current?.expandAll(); });

    show(['a', 'b']); // 'b' enters the viewport for the first time

    // Bulk actions move the DEFAULT, so a strip that was never registered at
    // click time still honours the user's "show me everything".
    expect(getByTestId('strip-b').dataset.expanded).toBe('true');
    expect(sink.current?.expandedCount).toBe(2);
  });

  it('collapseAll closes every strip and drops the per-strip deviations', () => {
    const { sink, getByTestId } = renderCanvas(['a', 'b']);
    act(() => { sink.current?.setExpanded('a', true); });

    act(() => { sink.current?.collapseAll(); });

    expect(sink.current?.expandedCount).toBe(0);
    expect(getByTestId('strip-a').dataset.expanded).toBe('false');
  });
});

describe('FileStripExpansionContext - resetKey', () => {
  it('a new run/epoch drops the per-strip deviations: the same node ids now stand for different files', () => {
    const { sink, show, getByTestId } = renderCanvas(['a', 'b'], 'run:r1:1');
    // A preview this user opened by hand, on files that are about to be replaced.
    act(() => { sink.current?.setExpanded('a', true); });

    show(['a', 'b'], 'run:r1:2');

    expect(getByTestId('strip-a').dataset.expanded).toBe('false');
    expect(sink.current?.expandedCount).toBe(0);
  });

  it('a new run/epoch KEEPS the remembered preference - only the per-strip deviations belong to the old files', () => {
    const { sink, show, getByTestId } = renderCanvas(['a', 'b'], 'run:r1:1');
    act(() => { sink.current?.expandAll(); });        // states the preference
    act(() => { sink.current?.setExpanded('a', false); }); // and deviates from it once

    show(['a', 'b'], 'run:r1:2');

    // The deviation went with the files it was about; the standing choice did not.
    // Resetting this to folded is what made the user restate it on every run.
    expect(getByTestId('strip-a').dataset.expanded).toBe('true');
    expect(sink.current?.expandedCount).toBe(2);
  });

  it('a reset rebuilds the count from the strips still on screen, it does not blank it', () => {
    const { sink, show } = renderCanvas(['a', 'b'], 'run:r1:1');
    show(['a', 'b'], 'run:r2:1');
    // Emptying it would dip the count through 0, which is the value that makes the
    // toolbar show the stored preference instead of what these strips are doing.
    expect(sink.current?.stripCount).toBe(2);
  });

  it('a reset forgets the strips that are no longer on screen', () => {
    const { sink, show } = renderCanvas(['a', 'b'], 'run:r1:1');
    show(['a'], 'run:r1:1');   // 'b' scrolled away - still counted
    expect(sink.current?.stripCount).toBe(2);

    show(['a'], 'edit::');      // leaving run mode - the registry is rebuilt

    expect(sink.current?.stripCount).toBe(1);
  });

  it('an unchanged resetKey never resets, however often the canvas re-renders', () => {
    const { sink, show } = renderCanvas(['a'], 'run:r1:1');
    act(() => { sink.current?.expandAll(); });
    show(['a'], 'run:r1:1');
    expect(sink.current?.expandedCount).toBe(1);
  });
});

describe('FileStripExpansionContext - the remembered preference', () => {
  it('a canvas opened with "expanded" stored brings its strips up on its own', () => {
    window.localStorage.setItem('workflow:fileStripExpanded', 'true');

    const { sink, getByTestId } = renderCanvas(['a', 'b']);

    expect(getByTestId('strip-a').dataset.expanded).toBe('true');
    expect(sink.current?.expandedCount).toBe(2);
    expect(sink.current?.defaultExpanded).toBe(true);
  });

  it('expandAll and collapseAll write the choice down', () => {
    const { sink } = renderCanvas(['a']);

    act(() => { sink.current?.expandAll(); });
    expect(window.localStorage.getItem('workflow:fileStripExpanded')).toBe('true');

    act(() => { sink.current?.collapseAll(); });
    expect(window.localStorage.getItem('workflow:fileStripExpanded')).toBe('false');
  });

  it('opening ONE preview by hand is not a standing choice and is never written down', () => {
    const { sink } = renderCanvas(['a', 'b']);

    act(() => { sink.current?.setExpanded('a', true); });

    // Only the bulk control states a preference; a single pill is about that file.
    expect(window.localStorage.getItem('workflow:fileStripExpanded')).toBeNull();
  });

  it('nothing stored means folded, as before', () => {
    const { sink } = renderCanvas(['a']);
    expect(sink.current?.defaultExpanded).toBe(false);
    expect(sink.current?.expandedCount).toBe(0);
  });

  it('a reset keeps the LIVE choice, it does not re-read storage', () => {
    const { sink, show, getByTestId } = renderCanvas(['a'], 'run:r1:1');
    act(() => { sink.current?.expandAll(); });

    // Someone else writes to the same key between mount and reset: a second canvas
    // in this tab (the sub-workflow side panel), another tab, or a write that
    // silently failed and left nothing behind at all.
    window.localStorage.setItem('workflow:fileStripExpanded', 'false');
    show(['a'], 'run:r1:2');

    // Re-reading here would fold this canvas at an arbitrary epoch change, with
    // nothing on screen to explain it - and for a user whose write failed, it would
    // resurrect the very bug this feature removes.
    expect(getByTestId('strip-a').dataset.expanded).toBe('true');

    window.localStorage.removeItem('workflow:fileStripExpanded');
    show(['a'], 'run:r1:3');
    expect(getByTestId('strip-a').dataset.expanded).toBe('true');
  });

  it('an unrecognised stored value leaves the canvas folded instead of guessing', () => {
    window.localStorage.setItem('workflow:fileStripExpanded', 'yes please');
    const junk = renderCanvas(['a']);
    expect(junk.sink.current?.defaultExpanded).toBe(false);
  });

  it('reads the preference AFTER mount, never during render - the hydration contract', () => {
    // This is the one decision the implementation goes out of its way to make (see
    // the comment on the mount effect), and effects flush inside act() during a
    // client render, so every other test here passes just as happily with the read
    // moved into the useState initializer. A server render is where the two differ:
    // it runs no effects, so the markup MUST show the folded default even with
    // "expanded" stored.
    //
    // This is a PROXY for SSR, not a reproduction of it: jsdom still provides a
    // `window` here, so the initializer variant would really read storage and emit
    // "true". A production server has no window and would emit "false" either way,
    // surfacing the mismatch on the client instead. What this pins is the property
    // that actually matters in both settings: the preference is not read during render.
    window.localStorage.setItem('workflow:fileStripExpanded', 'true');

    const serverHtml = renderToString(
      <FileStripExpansionProvider>
        <Strip id="a" />
      </FileStripExpansionProvider>,
    );

    expect(serverHtml).toContain('data-expanded="false"');

    // ...and the client then brings it up on its own once mounted.
    const { getByTestId } = renderCanvas(['a']);
    expect(getByTestId('strip-a').dataset.expanded).toBe('true');
  });
});

describe('useFileStripExpansionSafe outside the provider', () => {
  it('returns null so a consumer can tell "nobody coordinates me" from "I am registered"', () => {
    const sink: { current: FileStripExpansionContextValue | null } = { current: undefined as never };
    render(<Probe sink={sink} />);
    expect(sink.current).toBeNull();
  });
});
