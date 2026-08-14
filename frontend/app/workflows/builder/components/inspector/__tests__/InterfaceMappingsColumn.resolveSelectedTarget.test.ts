import { describe, it, expect } from 'vitest';
import { resolveSelectedTarget } from '../InterfaceMappingsColumn';

/**
 * Which offered target a SAVED mapping shows as selected.
 *
 * The dropdown now writes a page switch as `interface:<label>:navigate`, but this same
 * component wrote `trigger:<label>:navigate` until recently, so working plans carry that
 * form. Radix matches a Select value against its items by STRING, so without this
 * resolution the inspector shows every navigation row of an existing multi-page app as
 * "Select target", as if the mapping had been lost - while it keeps working at runtime.
 * That is worse than the warning the producer change set out to fix: it invites the user
 * to repair data that is fine.
 *
 * Tested here rather than through the rendered column: this is the whole of the fix, and
 * a Radix Select driven in jsdom exercises the harness more than the rule.
 */
const targets = [
  { triggerRef: 'interface:details:navigate' },
  { triggerRef: 'interface:home:navigate' },
  { triggerRef: 'trigger:search:submit' },
];

describe('resolveSelectedTarget', () => {
  it('maps a legacy trigger-prefixed page switch onto the offered interface target', () => {
    expect(resolveSelectedTarget('trigger:details:navigate', targets))
      .toBe('interface:details:navigate');
  });

  it('leaves a canonical ref exactly as stored', () => {
    expect(resolveSelectedTarget('interface:details:navigate', targets))
      .toBe('interface:details:navigate');
  });

  it('matches on the NORMALIZED label, the form the refs are built from', () => {
    expect(resolveSelectedTarget('trigger:Details:navigate', targets))
      .toBe('interface:details:navigate');
  });

  it('keeps a page switch that matches no offered target, rather than re-pointing it', () => {
    // Showing some other page as selected would misrepresent what is saved, and a save
    // would then silently rewrite the mapping to it.
    expect(resolveSelectedTarget('trigger:ghost_page:navigate', targets))
      .toBe('trigger:ghost_page:navigate');
  });

  it('does not touch a real trigger fire', () => {
    expect(resolveSelectedTarget('trigger:search:submit', targets)).toBe('trigger:search:submit');
    // Not a navigate, so no interface lookup even when the label would match one.
    expect(resolveSelectedTarget('trigger:details:click', targets)).toBe('trigger:details:click');
  });

  it('is empty-safe: an unset row stays unset', () => {
    expect(resolveSelectedTarget(undefined, targets)).toBeUndefined();
    expect(resolveSelectedTarget('', targets)).toBeUndefined();
  });
});
