// @vitest-environment jsdom
/**
 * The wrapper that makes a resource card draggable onto a folder.
 *
 * What matters here is that it stays out of the way: the card inside it is a link the user
 * clicks far more often than they drag, and on a phone the card has to be scrollable past.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';

const dnd = vi.hoisted(() => ({ args: {} as Record<string, unknown>, isDragging: false }));

vi.mock('@dnd-kit/core', () => ({
  useDraggable: (args: Record<string, unknown>) => {
    dnd.args = args;
    return {
      setNodeRef: () => {},
      attributes: { role: 'button' },
      listeners: { onMouseDown: () => {} },
      isDragging: dnd.isDragging,
    };
  },
}));

import { DraggableResourceCard } from '../DraggableResourceCard';

afterEach(() => { cleanup(); dnd.isDragging = false; });

describe('DraggableResourceCard', () => {
  it('files the resource by id, which is what a drop needs to know', () => {
    render(<DraggableResourceCard id="w1"><span>card</span></DraggableResourceCard>);

    expect(dnd.args.data).toEqual({ type: 'resource', resourceId: 'w1' });
    expect(dnd.args.id).toBe('resource:w1');
  });

  it('can be switched off, for a read-only surface or a search view', () => {
    render(<DraggableResourceCard id="w1" disabled><span>card</span></DraggableResourceCard>);

    expect(dnd.args.disabled).toBe(true);
  });

  it('lets a finger scroll past the card it wraps', () => {
    // The touch sensor waits for a HOLD, and `touch-manipulation` tells the browser there is
    // no double-tap gesture to reserve time for - so the wait is not competing with one.
    const { container } = render(<DraggableResourceCard id="w1"><span>card</span></DraggableResourceCard>);

    expect(container.firstElementChild).toHaveClass('touch-manipulation');
  });

  it('fades the card it is carrying, so the original does not look like a second copy', () => {
    dnd.isDragging = true;
    const { container } = render(<DraggableResourceCard id="w1"><span>card</span></DraggableResourceCard>);

    expect(container.firstElementChild).toHaveClass('opacity-50');
  });

  it('leaves the card at full strength when nothing is being dragged', () => {
    const { container } = render(<DraggableResourceCard id="w1"><span>card</span></DraggableResourceCard>);

    expect(container.firstElementChild).not.toHaveClass('opacity-50');
  });

  it('renders the card it was given, whatever that card is', () => {
    render(<DraggableResourceCard id="w1"><span>Weekly digest</span></DraggableResourceCard>);

    expect(screen.getByText('Weekly digest')).toBeInTheDocument();
  });
});
