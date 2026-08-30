import React from 'react';
import { Workflow } from 'lucide-react';
import type { SidePanelTab } from '@/contexts/SidePanelContext';
import { workflowPanelTabId } from '@/lib/sidePanel/tabResource';

/** Just the slice of the side panel this needs - keeps callers testable without the provider. */
export interface WorkflowTabOpener {
  openTab: (tab: SidePanelTab) => void;
}

export interface OpenWorkflowBuilderTabOptions {
  workflowId: string;
  /** Tab label. Falls back to a generic one so a relation with no resolvable name still reads. */
  workflowName?: string | null;
  /** Mount the canvas read-only (marketplace/publisher views). Omitted = editable, as before. */
  readOnly?: boolean;
}

/**
 * Open a workflow's builder in the right side panel.
 *
 * <p>This is the one place that decides what such a tab looks like: its id (so re-opening the same
 * workflow re-activates the existing tab rather than stacking duplicates), its half-width default,
 * and `keepMounted` - a workflow canvas is expensive to rebuild and holds a live run subscription,
 * so it must survive the panel being closed or another tab taking over.
 *
 * <p>The panel content is imported lazily inside the call: it pulls in the whole builder, and the
 * surfaces that offer this (a card footer, a canvas toolbar, a node button) must not pay for it
 * until someone actually clicks.
 */
export function openWorkflowBuilderTab(
  sidePanel: WorkflowTabOpener | null | undefined,
  { workflowId, workflowName, readOnly }: OpenWorkflowBuilderTabOptions,
): void {
  if (!sidePanel || !workflowId) return;
  import('@/components/app/WorkflowBuilderPanelContent').then(({ WorkflowBuilderPanelContent }) => {
    sidePanel.openTab({
      id: workflowPanelTabId(workflowId),
      label: workflowName || 'Workflow',
      icon: React.createElement(Workflow, { className: 'w-4 h-4' }),
      content: React.createElement(WorkflowBuilderPanelContent, { workflowId, readOnly }),
      preferredWidth: 0.5,
      keepMounted: true,
    });
  });
}

/**
 * Ask the surrounding workflow view to open a related workflow, letting it resolve the PINNED RUN
 * first and show that instead of the editable builder.
 *
 * <p>Only meaningful inside a workflow view: `WorkflowDetailView` and `WorkflowBuilderPanelContent`
 * are what listen for this. From a card grid, where no such view is mounted, call
 * {@link openWorkflowBuilderTab} directly - the event would be dropped on the floor.
 */
export function requestOpenRelatedWorkflow(
  workflowId: string,
  workflowName?: string | null,
  /**
   * The node that asked, when one did. No listener reads it today, but it is what identifies the
   * call SITE inside the plan, so it is carried rather than dropped: an opener that is not a node
   * (the toolbar's relations menu) simply sends none.
   */
  nodeId = '',
): void {
  if (typeof window === 'undefined' || !workflowId) return;
  window.dispatchEvent(new CustomEvent('workflowOpenSubWorkflow', {
    detail: { workflowId, workflowName: workflowName || 'Workflow', nodeId },
  }));
}
