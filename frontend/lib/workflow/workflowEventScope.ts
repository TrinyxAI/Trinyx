/**
 * Scoping rule for the workflow CustomEvents that travel on `window`
 * (`workflowViewSave`, `workflowViewStart`, `workflowStartStepByStep`,
 * `workflowDirtyChange`, `workflowViewSaveComplete`,
 * `workflowStreamingStateChange`).
 *
 * These are broadcast globally, which was unambiguous while exactly one canvas
 * existed per page. It no longer is: the right side panel mounts its own canvas
 * (a sub-workflow tab, an application tab), so a page canvas and a panel canvas
 * listen at the same time. Unscoped, one Save saved both workflows and one Run
 * started a run of each - the same class of bug `shouldAdoptEpochEvent` already
 * closes for the per-run epoch selection, here keyed by workflow instead.
 *
 * The rule is deliberately permissive: an event is refused ONLY when it names a
 * DIFFERENT workflow. A dispatcher that names no workflow still reaches every
 * listener, which is what keeps the broadcast call sites working (e2e fixtures
 * dispatch without an id, and so did every in-canvas control before this).
 *
 * KNOWN LIMIT, stated rather than implied: this separates two canvases showing
 * DIFFERENT workflows, which is the case the side panel creates every day. It
 * does NOT separate two surfaces showing the SAME workflow (a self-referencing
 * sub-workflow node, or opening the current workflow from the tab picker) -
 * there, both canvases still answer, exactly as they did before. Closing that
 * needs a surface identity on the event, the way `subscribeBindRun` already
 * carries one for the run bus.
 */
export function isEventForWorkflow(detail: unknown, workflowId?: string | null): boolean {
  const target = (detail as { workflowId?: unknown } | null | undefined)?.workflowId;
  if (typeof target !== 'string' || target.length === 0) return true;
  if (typeof workflowId !== 'string' || workflowId.length === 0) return true;
  return target === workflowId;
}
