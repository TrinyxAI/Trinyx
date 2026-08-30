'use client';

import { useCallback } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { isEmbeddedWorkflowCanvas } from '@/lib/workflow/canvasEmbedding';

/**
 * Show a run of `workflowId`, without assuming the workflow owns the page.
 *
 * On its own page this routes to the run URL, as it always has. From an
 * EMBEDDED canvas it binds the run in place instead - the contract
 * `isEmbeddedWorkflowCanvas` states and the mode toggle already honours. Routing
 * from there takes the whole app off the page the user was on, which is the
 * opposite of what a side-panel control is for: watching this workflow without
 * leaving.
 *
 * It lives here because three controls now reach run mode and each one is
 * mountable in the panel: starting a run, pinning a version as production, and
 * restoring one. They were three copies of the same `router.push`, and only the
 * first had been taught the difference.
 */
export function useEnterRunMode(workflowId: string | undefined): (runId: string) => void {
  const router = useRouter();
  const pathname = usePathname();
  // `useWorkflowMode` falls back to a provider-less object whose `setRunId` is a
  // no-op and whose `workflowId` is undefined. Binding into that would silently
  // do nothing: the run would start and the user would be left on the same
  // screen with no sign of it. A provider that does not even know its workflow
  // is no better, so one check covers both - route, because showing the run
  // somewhere beats showing it nowhere.
  const { setRunId, workflowId: boundWorkflowId } = useWorkflowMode();

  return useCallback((runId: string) => {
    if (!workflowId || !runId) return;
    if (boundWorkflowId && isEmbeddedWorkflowCanvas(pathname, workflowId)) {
      setRunId(runId);
      return;
    }
    router.push(`/app/workflow/${workflowId}/run/${runId}`);
  }, [workflowId, pathname, setRunId, router, boundWorkflowId]);
}
