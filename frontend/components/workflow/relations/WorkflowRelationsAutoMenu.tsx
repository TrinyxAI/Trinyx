'use client';

import React from 'react';

import { WorkflowRelationsMenu, type WorkflowRelationsMenuProps } from './WorkflowRelationsMenu';
import { useWorkflowRelations } from './useWorkflowRelations';

export interface WorkflowRelationsAutoMenuProps extends Omit<WorkflowRelationsMenuProps, 'relations'> {
  /** The workflow whose neighbourhood to resolve and show. */
  workflowId: string;
}

/**
 * {@link WorkflowRelationsMenu} for a surface that shows ONE workflow (a canvas toolbar, an
 * application view): it resolves that workflow's relations itself.
 *
 * <p>Kept apart from the menu on purpose. Resolving goes through the app's query layer, and a card
 * GRID must neither pay for that provider nor fire a read per card - it batches the whole page and
 * passes each card its slice into the plain menu. Folding the two together is what made the menu
 * drag a data layer into every grid that rendered it.
 */
export function WorkflowRelationsAutoMenu({ workflowId, ...menuProps }: WorkflowRelationsAutoMenuProps) {
  const { relations } = useWorkflowRelations(workflowId);
  return <WorkflowRelationsMenu {...menuProps} relations={relations} />;
}
