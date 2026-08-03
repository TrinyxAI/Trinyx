// @vitest-environment jsdom
/**
 * FileStripExpansionContext - the canvas-wide expand/collapse registry behind
 * the toolbar's toggle-all control.
 *
 * What is pinned here is the bookkeeping the toolbar reads (how many strips are
 * mounted, how many are open) and the two failure modes that make a toggle-all
 * feel broken: a strip that unmounts while expanded leaving a phantom in the
 * count, and a bulk expand that misses strips registered after the provider
 * mounted.
 */
import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, act } from '@testing-library/react';

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

const renderWith = (ids: string[]) => {
  const sink: { current: FileStripExpansionContextValue | null } = { current: null };
  const view = render(
    <FileStripExpansionProvider>
      <Probe sink={sink} />
      {ids.map((id) => <Strip key={id} id={id} />)}
    </FileStripExpansionProvider>,
  );
  const rerenderWith = (next: string[]) =>
    view.rerender(
      <FileStripExpansionProvider>
        <Probe sink={sink} />
        {next.map((id) => <Strip key={id} id={id} />)}
      </FileStripExpansionProvider>,
    );
  return { ...view, sink, rerenderWith };
};

describe('FileStripExpansionContext - registry', () => {
  it('counts every mounted strip and starts with all of them collapsed', () => {
    const { sink } = renderWith(['a', 'b', 'c']);
    expect(sink.current?.stripCount).toBe(3);
    expect(sink.current?.expandedCount).toBe(0);
  });

  it('registering the same id twice does not double-count it (StrictMode / re-render safe)', () => {
    const { sink } = renderWith(['a']);
    act(() => { sink.current?.registerStrip('a'); });
    expect(sink.current?.stripCount).toBe(1);
  });

  it('drops an unmounted strip from the count', () => {
    const { sink, rerenderWith } = renderWith(['a', 'b']);
    rerenderWith(['a']);
    expect(sink.current?.stripCount).toBe(1);
  });

  it('an EXPANDED strip that unmounts stops counting as expanded, so the toolbar cannot claim there is something to collapse', () => {
    const { sink, rerenderWith } = renderWith(['a', 'b']);
    act(() => { sink.current?.expandAll(); });
    expect(sink.current?.expandedCount).toBe(2);

    rerenderWith(['a']);

    expect(sink.current?.stripCount).toBe(1);
    // Pre-fix this stayed at 2: expandedCount >= stripCount, so the toolbar read
    // "all expanded" and its next click collapsed instead of expanding.
    expect(sink.current?.expandedCount).toBe(1);
  });
});

describe('FileStripExpansionContext - per-strip state', () => {
  it('setExpanded flips exactly one strip and leaves its siblings alone', () => {
    const { sink, getByTestId } = renderWith(['a', 'b']);
    act(() => { sink.current?.setExpanded('a', true); });
    expect(getByTestId('strip-a').dataset.expanded).toBe('true');
    expect(getByTestId('strip-b').dataset.expanded).toBe('false');
    expect(sink.current?.expandedCount).toBe(1);
  });

  it('a strip stays registered when it expands - the registry must not churn on a toggle', () => {
    const { sink } = renderWith(['a', 'b']);
    act(() => { sink.current?.setExpanded('a', true); });
    // The context value gets a new identity on every expand; a strip that
    // re-registered on that would unregister itself and collapse instantly.
    expect(sink.current?.stripCount).toBe(2);
    expect(sink.current?.isExpanded('a')).toBe(true);
  });

  it('setting the value a strip already has is a no-op on the expanded count', () => {
    const { sink } = renderWith(['a']);
    act(() => { sink.current?.setExpanded('a', false); });
    expect(sink.current?.expandedCount).toBe(0);
  });
});

describe('FileStripExpansionContext - bulk actions', () => {
  it('expandAll opens every registered strip', () => {
    const { sink, getByTestId } = renderWith(['a', 'b', 'c']);
    act(() => { sink.current?.expandAll(); });
    expect(sink.current?.expandedCount).toBe(3);
    expect(getByTestId('strip-a').dataset.expanded).toBe('true');
    expect(getByTestId('strip-c').dataset.expanded).toBe('true');
  });

  it('expandAll reaches strips that registered AFTER the provider mounted (a run resolving its files late)', () => {
    const { sink, rerenderWith, getByTestId } = renderWith(['a']);
    rerenderWith(['a', 'b']);

    act(() => { sink.current?.expandAll(); });

    // expandAll reads the CURRENT registry, not the set captured when the
    // callback was created.
    expect(sink.current?.expandedCount).toBe(2);
    expect(getByTestId('strip-b').dataset.expanded).toBe('true');
  });

  it('collapseAll closes every strip', () => {
    const { sink, getByTestId } = renderWith(['a', 'b']);
    act(() => { sink.current?.expandAll(); });
    act(() => { sink.current?.collapseAll(); });
    expect(sink.current?.expandedCount).toBe(0);
    expect(getByTestId('strip-a').dataset.expanded).toBe('false');
  });
});

describe('useFileStripExpansionSafe outside the provider', () => {
  it('returns null so a consumer can tell "nobody coordinates me" from "I am registered"', () => {
    const sink: { current: FileStripExpansionContextValue | null } = { current: undefined as never };
    render(<Probe sink={sink} />);
    expect(sink.current).toBeNull();
  });
});
