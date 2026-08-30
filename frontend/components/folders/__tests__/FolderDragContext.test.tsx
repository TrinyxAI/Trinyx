// @vitest-environment jsdom
/**
 * The drag surface of a list with folders.
 *
 * Three things are pinned here, all of them defects the folder lists shipped with:
 *  - every handler the five lists used to wire by hand is wired here now, so this is the one
 *    place a missing one would take drag-and-drop off ALL of them at once;
 *  - the drop target is decided by the POINTER, not by the floating preview. The preview is
 *    anchored where the card was picked up, so on a big card it floats well away from the
 *    cursor and the rectangle test lit up - and filed into - a tile nobody was pointing at;
 *  - the context wraps the WHOLE list, header included, so the folder path in the header is a
 *    live drop target. Dragging a card onto a crumb is the way OUT of a folder, and with the
 *    context around the cards alone every crumb was dead.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import React from 'react';

const dnd = vi.hoisted(() => ({
  props: {} as Record<string, unknown>,
  overlayProps: {} as Record<string, unknown>,
}));

vi.mock('@dnd-kit/core', () => ({
  DndContext: ({ children, ...props }: { children: React.ReactNode }) => {
    dnd.props = props;
    return <div data-testid="dnd-context">{children}</div>;
  },
  DragOverlay: ({ children, ...props }: { children: React.ReactNode }) => {
    dnd.overlayProps = props;
    return <div data-testid="overlay">{children}</div>;
  },
  pointerWithin: vi.fn(),
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${JSON.stringify(values)}` : key,
}));

import { pointerWithin } from '@dnd-kit/core';
import { FolderDragContext } from '../FolderDragContext';
import type { ListFolders } from '@/hooks/useListFolders';

const sensors = [{ sensor: 'mouse' }];

function renderContext(
  activeDrag: { label: string; count: number } | null = null,
  nameOf: (id: string) => string | undefined = () => 'Weekly digest',
) {
  const handleDragStart = vi.fn();
  const handleDragEnd = vi.fn();
  const cancelDrag = vi.fn();
  const folders = { sensors, handleDragStart, handleDragEnd, cancelDrag, activeDrag } as unknown as ListFolders;

  render(
    <FolderDragContext folders={folders} nameOf={nameOf}>
      <div data-testid="the-header" />
    </FolderDragContext>,
  );
  return { handleDragStart, handleDragEnd, cancelDrag };
}

beforeEach(() => { dnd.props = {}; dnd.overlayProps = {}; });
afterEach(() => cleanup());

describe('FolderDragContext - what it wires up', () => {
  it('hands the drag its sensors, so a press can start one at all', () => {
    renderContext();

    expect(dnd.props.sensors).toBe(sensors);
  });

  it('drives the drag by the pointer rather than by the floating preview', () => {
    renderContext();

    expect(dnd.props.collisionDetection).toBe(pointerWithin);
  });

  it('reports the end of a drag, which is the moment the card is filed', () => {
    const { handleDragEnd } = renderContext();

    (dnd.props.onDragEnd as (event: unknown) => void)({ some: 'event' });

    expect(handleDragEnd).toHaveBeenCalledWith({ some: 'event' });
  });

  it('reports a cancelled drag, so the preview is not left behind', () => {
    const { cancelDrag } = renderContext();

    (dnd.props.onDragCancel as () => void)();

    expect(cancelDrag).toHaveBeenCalled();
  });

  it('reports the start of a drag, handing over the list as the only thing that can name what moved', () => {
    const nameOf = () => 'Weekly digest';
    const { handleDragStart } = renderContext(null, nameOf);

    (dnd.props.onDragStart as (event: unknown) => void)({ active: 'a' });

    expect(handleDragStart).toHaveBeenCalledWith({ active: 'a' }, nameOf);
  });
});

describe('FolderDragContext - what it covers', () => {
  it('wraps what it is given, so a header inside it can hold drop targets', () => {
    renderContext();

    expect(screen.getByTestId('dnd-context')).toContainElement(screen.getByTestId('the-header'));
  });
});

describe('FolderDragContext - the floating preview', () => {
  it('shows nothing while nothing is being dragged', () => {
    renderContext();

    expect(screen.getByTestId('overlay')).toBeEmptyDOMElement();
  });

  it('names what is travelling under the pointer', () => {
    renderContext({ label: 'Weekly digest', count: 1 });

    expect(screen.getByText('Weekly digest')).toBeInTheDocument();
  });

  it('counts a multi-selection drag, so a drop never moves more than you meant', () => {
    renderContext({ label: 'Weekly digest', count: 3 });

    expect(screen.getByText('draggingCount:{"count":3}')).toBeInTheDocument();
  });

  it('does not fly the preview back to the card on a drop that was refused', () => {
    // The preview is a LABEL, not the card itself. dnd-kit's default animation returns it to
    // where the card sits, which on a refused drop points at the wrong thing entirely.
    renderContext({ label: 'Weekly digest', count: 1 });

    expect(dnd.overlayProps.dropAnimation).toBeNull();
  });

  it('keeps the preview transparent to the pointer, so nothing is hovered through it', () => {
    renderContext({ label: 'Weekly digest', count: 1 });

    // The preview ITSELF, not merely some element on screen. Which target a drop resolves to
    // is decided from coordinates, so this is about hover, not about the drop landing.
    expect(screen.getByText('Weekly digest').closest('div')).toHaveClass('pointer-events-none');
  });
});
