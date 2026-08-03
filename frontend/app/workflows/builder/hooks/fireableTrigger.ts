import type { NodeContextFlags } from './useNodeContextualButtons';

/**
 * Trigger types a user can actually fire from the UI. Narrower than
 * {@link NodeContextFlags.isTriggerNode}, which also covers generic entry nodes
 * that carry no fireable trigger type, and which is kind-dependent (a workflows
 * or error trigger only satisfies it at kind 'entry'). Shared by every surface
 * offering a play affordance (the node bottom bar, its focus-epoch counterpart,
 * the run-info step row, the run-mode canvas toolbar) so they never disagree
 * about what is runnable.
 *
 * Lives in its own module, apart from the hook that owns the flags, because it is
 * a pure predicate and its consumers must be able to read it without pulling that
 * hook's dependency chain in - the side-panel payloads it imports reach next-intl's
 * navigation entry, which is why every test rendering those consumers replaces the
 * hook module wholesale. The type import above is erased at compile time, so this
 * module has no runtime edge back.
 */
export function isFireableTrigger(flags: NodeContextFlags): boolean {
  return flags.isManualTrigger || flags.isChatTrigger || flags.isFormTrigger ||
    flags.isWebhookTrigger || flags.isScheduleTrigger || flags.isWorkflowsTriggerNode ||
    flags.isTablesTrigger || flags.isErrorTrigger;
}
