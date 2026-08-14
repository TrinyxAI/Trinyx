/**
 * Shared reading of an interface action-mapping REF - the value side of
 * `actionMapping` (`{'#css-selector': '<ref>'}`).
 *
 * A ref is `<prefix>:<label>:<event>`, and `navigate` is the event that means
 * "switch the displayed page, frontend only, no backend call".
 *
 * <p><b>The prefix is deliberately NOT checked.</b> Two producers disagree about
 * it and both are in the wild: `InterfaceNodeCreator` (backend) emits
 * `interface:<label>:navigate`, while the builder's own Action Mappings dropdown
 * (`InterfaceMappingsColumn`) emits `trigger:<label>:navigate` for an interface
 * target - so every navigate mapping ever authored through the UI, including
 * those already persisted in published and cloned plans, carries the `trigger:`
 * prefix. The tool schema now teaches the `interface:` form, so the plans already
 * saved with the other one are the whole of the reason this stays prefix-agnostic.
 * Requiring `interface:` would turn all of those into live trigger fires.
 *
 * <p>A fifth consumer, the publication side panel, had NO navigate check at all: a
 * real page-switch link there fell through and fired `executeStep` on the stripped
 * key, turning a frontend-only navigation into a backend call. It now uses this
 * predicate like the rest.
 *
 * <p>What IS checked is the segment count, and that is the bug this module
 * exists for. Four call sites used to test `ref.endsWith(':navigate')`,
 * which also matches a trigger whose LABEL is "Navigate": `trigger:navigate`.
 * Every navigate handler then took the page-switch branch, found no target in a
 * two-segment ref, and returned - so the button did nothing at all, silently,
 * and the guards those sites apply to real actions (preview blocking, the
 * running-run guard, the in-flight dedupe) were skipped on the way.
 */

const NAVIGATE_SUFFIX = ':navigate';

/** `<prefix>:<label>:navigate` - at least three segments, any prefix. */
export function isNavigateRef(ref: string | null | undefined): boolean {
  if (!ref || !ref.endsWith(NAVIGATE_SUFFIX)) return false;
  // A two-segment ref is `<prefix>:navigate`: a trigger named "Navigate", not a
  // page switch. Three segments means there is room for a target label.
  return ref.split(':').length >= 3;
}

/**
 * The target interface label carried by a navigate ref, still RAW - callers
 * normalize it before matching against a config label. Null when the ref is not
 * a navigate ref, or carries an empty label (`interface::navigate`): callers
 * treat that as "nothing to switch to" and swallow it rather than firing it.
 * A label containing colons is preserved intact.
 */
export function navigateTargetLabel(ref: string | null | undefined): string | null {
  if (!isNavigateRef(ref)) return null;
  const parts = ref!.split(':');
  const label = parts.slice(1, -1).join(':');
  return label.length > 0 ? label : null;
}
