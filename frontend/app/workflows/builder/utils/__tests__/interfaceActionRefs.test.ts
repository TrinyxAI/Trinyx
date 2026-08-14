import { describe, it, expect } from 'vitest';
import { isNavigateRef, navigateTargetLabel } from '../interfaceActionRefs';

/**
 * The predicate that separates a page switch from a real action.
 *
 * It replaced four copies of `ref.endsWith(':navigate')`, whose failure mode was
 * silent: a trigger LABELLED "Navigate" produces `trigger:navigate`, which ends
 * with `:navigate` too. Every navigate handler then took the page-switch branch,
 * could not extract a target from two segments, and returned - so the button did
 * nothing, and the guards those call sites apply to real actions (preview
 * blocking, the running-run guard, the in-flight dedupe) never ran.
 *
 * The prefix is deliberately not part of the predicate: BOTH `interface:` (from
 * InterfaceNodeCreator) and `trigger:` (from the builder's own Action Mappings
 * dropdown) are produced in the wild and already persisted in published plans.
 */
describe('isNavigateRef', () => {
  it('accepts the shape the backend emits for a page switch', () => {
    expect(isNavigateRef('interface:details:navigate')).toBe(true);
  });

  it('accepts the shape the builder UI emits - a trigger prefix with a navigate event', () => {
    // InterfaceMappingsColumn builds the target list with triggerKey(label) and
    // appends ':navigate' for an interface node. Rejecting this would turn every
    // UI-authored page switch, including ones already saved in shipped plans,
    // into a live trigger fire.
    expect(isNavigateRef('trigger:details:navigate')).toBe(true);
  });

  it('rejects a trigger merely LABELLED "Navigate" - the bug this predicate exists for', () => {
    // Two segments: no target to switch to, and it is a real trigger that must
    // fire instead of being swallowed.
    expect(isNavigateRef('trigger:navigate')).toBe(false);
    expect(isNavigateRef('interface:navigate')).toBe(false);
  });

  it('rejects the real trigger event suffixes', () => {
    expect(isNavigateRef('trigger:my_form:submit')).toBe(false);
    expect(isNavigateRef('trigger:my_chat:message')).toBe(false);
    expect(isNavigateRef('trigger:my_button:click')).toBe(false);
  });

  it('rejects the bridge control refs, which are not actions at all', () => {
    expect(isNavigateRef('__continue')).toBe(false);
    expect(isNavigateRef('__pagination:next')).toBe(false);
    expect(isNavigateRef('__varpage:items:2')).toBe(false);
  });

  it('still counts an empty-label ref as a navigate, so callers swallow it instead of firing it', () => {
    // Pre-change this was caught by endsWith and swallowed by the `if (targetLabel)`
    // guard. Treating it as a real action would send `interface:` to the backend.
    expect(isNavigateRef('interface::navigate')).toBe(true);
    expect(navigateTargetLabel('interface::navigate')).toBeNull();
  });

  it('accepts an interface literally labelled "Navigate" - the mirror of the fixed bug', () => {
    expect(isNavigateRef('interface:navigate:navigate')).toBe(true);
    expect(navigateTargetLabel('interface:navigate:navigate')).toBe('navigate');
  });

  it('rejects a ref that merely mentions navigate elsewhere', () => {
    expect(isNavigateRef('interface:navigate_bar:submit')).toBe(false);
    expect(isNavigateRef('navigate')).toBe(false);
  });

  it('is null-safe: an absent mapping value is not a page switch', () => {
    expect(isNavigateRef(null)).toBe(false);
    expect(isNavigateRef(undefined)).toBe(false);
    expect(isNavigateRef('')).toBe(false);
  });
});

describe('navigateTargetLabel', () => {
  it('returns the raw label between the prefix and the suffix, for either prefix', () => {
    expect(navigateTargetLabel('interface:details:navigate')).toBe('details');
    expect(navigateTargetLabel('trigger:settings_page:navigate')).toBe('settings_page');
  });

  it('preserves a label that itself contains colons', () => {
    // The old `parts.slice(1, -1).join(':')` had this property; keep it.
    expect(navigateTargetLabel('interface:a:b:navigate')).toBe('a:b');
  });

  it('returns null for anything that is not a page switch, so callers cannot navigate on a real action', () => {
    expect(navigateTargetLabel('trigger:navigate')).toBeNull();
    expect(navigateTargetLabel('trigger:my_form:submit')).toBeNull();
    expect(navigateTargetLabel(null)).toBeNull();
  });
});
