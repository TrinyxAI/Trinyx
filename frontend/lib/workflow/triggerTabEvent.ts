/**
 * Contract of the `workflowOpenTriggerTab` window event: "open the side-panel
 * tab of the trigger the user just picked", dispatched by the play button under
 * a trigger node, by the run-info step popover, and by the run-mode canvas
 * toolbar's trigger menu.
 *
 * <p>Both halves live here so a dispatcher and a listener can never drift: use
 * {@link dispatchOpenTriggerTab} to emit (the payload is compile-checked) and
 * {@link findTriggerTabConfig} to resolve it against the panel's tab configs.
 */

/** Side-panel tab families, i.e. the triggers that carry a user-supplied payload. */
export type TriggerPanelTab = 'chat' | 'form' | 'webhook';

export const OPEN_TRIGGER_TAB_EVENT = 'workflowOpenTriggerTab';

export interface OpenTriggerTabDetail {
  /** React Flow node id of the picked trigger. */
  nodeId: string;
  /** Tab family to open. */
  triggerType: TriggerPanelTab;
  /**
   * Backend trigger id (`trigger:<normalized_label>`) of the picked trigger, when
   * the dispatcher knows it. Optional: a caller that cannot resolve it still gets
   * the type fallback below.
   */
  triggerId?: string;
}

/** Emits {@link OPEN_TRIGGER_TAB_EVENT} with a checked payload. */
export function dispatchOpenTriggerTab(detail: OpenTriggerTabDetail): void {
  window.dispatchEvent(new CustomEvent<OpenTriggerTabDetail>(OPEN_TRIGGER_TAB_EVENT, { detail }));
}

/**
 * Resolves which trigger tab config an {@link OpenTriggerTabDetail} refers to.
 *
 * <p>The exact `triggerId` wins whenever it is present and known: a workflow can
 * hold several triggers of the same type, and matching on type alone would
 * always open the first one - silently ignoring the user's choice. Type stays as
 * the fallback for dispatchers that do not carry the id.
 */
export function findTriggerTabConfig<T extends { triggerId: string; type: string }>(
  configs: T[] | undefined | null,
  detail: OpenTriggerTabDetail,
): T | undefined {
  if (!configs || configs.length === 0) return undefined;
  if (detail.triggerId) {
    const exact = configs.find((config) => config.triggerId === detail.triggerId);
    if (exact) return exact;
  }
  return configs.find((config) => config.type === detail.triggerType);
}
