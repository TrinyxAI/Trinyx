'use client';

// The workflow service DIRECTLY, not the aggregated `orchestratorApi` barrel: this hook is
// imported by card grids whose tests mock that one service, and pulling the barrel in would make
// it bind every OTHER service's methods off a partial mock at import time.
import { workflowService } from '@/lib/api/orchestrator/workflow.service';
import type { WorkflowRelations } from '@/lib/api/orchestrator/types';
import { QUERY_CONFIG } from '@/lib/api/constants';
import { useResourceQuery } from '@/lib/hooks/useResourceQuery';

const NO_RELATIONS: WorkflowRelations = { parents: [], children: [] };

/**
 * The sub-workflow neighbourhood of ONE workflow, for a surface that shows a single workflow (a
 * canvas toolbar, an application view).
 *
 * <p>A card GRID must not use this: it would be one request per card. Those pages call
 * {@code orchestratorApi.getWorkflowRelationsBatch} once for the whole page and hand each card its
 * slice through the menu's `relations` prop.
 *
 * <p>Relations change only when someone edits a plan, so the answer is cached rather than refetched
 * on focus. Known staleness, stated rather than hidden: adding a sub-workflow node to the workflow
 * you are looking at does not move this menu until the cached answer goes stale (SHORT, 2 min) or
 * the canvas is remounted. It is advisory navigation, not state anything acts on, so it is not
 * worth wiring an invalidation into every plan-save path for.
 */
export function useWorkflowRelations(workflowId: string | undefined, enabled = true) {
  const query = useResourceQuery<WorkflowRelations>({
    queryKey: ['workflow-relations', workflowId],
    queryFn: () => workflowService.getWorkflowRelations(workflowId as string),
    enabled: enabled && !!workflowId,
    resourceId: workflowId,
    staleTime: QUERY_CONFIG.STALE_TIME.SHORT,
  });

  return {
    // The service already swallows failures into empty lists; this covers "not loaded yet" so the
    // caller never has to null-check before reading .parents/.children.
    relations: query.data ?? NO_RELATIONS,
    isLoading: query.isLoading,
  };
}
