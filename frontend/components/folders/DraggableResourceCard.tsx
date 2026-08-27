'use client';

import React from 'react';
import { useDraggable } from '@dnd-kit/core';

interface DraggableResourceCardProps {
  /** The resource's id - what gets filed when the card is dropped on a folder. */
  id: string;
  /** Off for a read-only surface, or while the list is showing search results. */
  disabled?: boolean;
  children: React.ReactNode;
}

/**
 * Makes a resource card draggable onto a folder tile without the card knowing anything
 * about folders. The drag needs a few pixels of movement before it starts (dnd-kit's
 * activation constraint, set by the list), so a plain click still opens the card.
 */
export function DraggableResourceCard({ id, disabled = false, children }: DraggableResourceCardProps) {
  const { setNodeRef, attributes, listeners, isDragging } = useDraggable({
    id: `resource:${id}`,
    data: { type: 'resource', resourceId: id },
    disabled,
  });

  return (
    <div ref={setNodeRef} {...attributes} {...listeners} className={isDragging ? 'opacity-50' : undefined}>
      {children}
    </div>
  );
}
