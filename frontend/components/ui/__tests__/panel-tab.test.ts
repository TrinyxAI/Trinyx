/**
 * Panel tabs (side-panel header + workflow/agent sub-tabs) are rendered as real
 * buttons. `panelTabClass` must stay COMPOSED from `buttonVariants` - it inherits
 * the Button shape, control height, motion and focus ring instead of restating
 * them - and must express hover/active through the theme palette variables so
 * light and dark both work.
 */
import { describe, expect, it } from 'vitest';
import { buttonVariants } from '@/components/ui/button';
import { panelTabClass, panelTabInnerHoverClass } from '@/components/ui/panel-tab';

/** Classes twMerge is expected to drop when the ghost hover is overridden. */
const GHOST_INVERTED_HOVER = ['hover:bg-[var(--text-primary)]', 'hover:text-[var(--bg-primary)]'];

/**
 * Whole-token membership, NOT substring. `toContain('bg-[var(--bg-hover)]')` is
 * also satisfied by `hover:bg-[var(--bg-hover)]`, so a tab that lost its resting
 * fill and only paints on hover - i.e. no visible active state - would pass a
 * substring assertion. Every fill check below goes through this.
 */
function tokens(className: string): string[] {
  return className.split(/\s+/).filter(Boolean);
}

describe('panelTabClass', () => {
  it('inherits the Button base tokens, and fails if Button drops or renames one', () => {
    // Anything in the Button base that twMerge cannot conflict away must appear
    // in the tab class. This pins the REMOVAL direction: if Button drops a token
    // while the tab keeps it, the tab has stopped composing and is a stale copy.
    const inherited = [
      'rounded-xl',
      'transition-colors',
      'duration-150',
      'focus-visible:outline-none',
      'focus-visible:ring-2',
      'focus-visible:ring-[var(--accent-primary)]',
      'focus-visible:ring-offset-1',
      'focus-visible:ring-offset-[var(--bg-primary)]',
      'disabled:pointer-events-none',
    ];
    const base = buttonVariants();
    for (const token of inherited) {
      expect(base, `Button lost ${token} - panel tabs inherit it`).toContain(token);
      expect(panelTabClass(true)).toContain(token);
      expect(panelTabClass(false)).toContain(token);
    }
  });

  it('active tab has a RESTING fill in the chrome selected-item surface, not the solid accent', () => {
    const active = panelTabClass(true);
    // Whole token: a tab that only fills on hover has no visible active state.
    expect(tokens(active)).toContain('bg-[var(--bg-hover)]');
    expect(tokens(active)).not.toContain('bg-transparent');
    expect(tokens(active)).toContain('text-[var(--text-primary)]');
    // A full accent fill reads as a black block across a bar of tabs.
    expect(tokens(active)).not.toContain('bg-[var(--accent-primary)]');
    expect(tokens(active)).not.toContain('text-[var(--accent-foreground)]');
  });

  it('active tab carries the two cues that survive light mode, where the fill barely differs from hover', () => {
    // --bg-tertiary (#eceff3) vs --bg-hover (#e5e7eb) is about one perceptible
    // step, so the outline and the heavier text are what actually read.
    const active = tokens(panelTabClass(true));
    const inactive = tokens(panelTabClass(false));
    expect(active).toContain('border-[var(--border-color)]');
    expect(active).toContain('font-semibold');
    expect(inactive).not.toContain('border-[var(--border-color)]');
    expect(inactive).not.toContain('font-semibold');
    expect(inactive).toContain('font-medium');
  });

  it('active tab keeps its surface on hover instead of drifting to another color', () => {
    const active = panelTabClass(true);
    expect(active).toContain('hover:bg-[var(--bg-hover)]');
    expect(active).toContain('hover:text-[var(--text-primary)]');
  });

  it('inactive tab starts from ghost but replaces its inverted hover with a raised surface', () => {
    const inactive = panelTabClass(false);
    expect(inactive).toContain('bg-transparent');
    expect(inactive).toContain('text-[var(--text-secondary)]');
    expect(inactive).toContain('hover:text-[var(--text-primary)]');
    // The ghost variant's legacy dark-surface hover must NOT survive the merge:
    // it would flip the tab to a near-black chip on hover, i.e. look active.
    for (const token of GHOST_INVERTED_HOVER) {
      expect(buttonVariants({ variant: 'ghost' }), `ghost lost ${token}`).toContain(token);
      expect(inactive).not.toContain(token);
      expect(panelTabClass(true), `active also overrides ${token}`).not.toContain(token);
    }
  });

  it('hovers to the tertiary surface, not secondary - secondary is invisible against the dark panel', () => {
    const inactive = panelTabClass(false);
    expect(inactive).toContain('hover:bg-[var(--bg-tertiary)]');
    expect(inactive).not.toContain('hover:bg-[var(--bg-secondary)]');
  });

  it('keeps the surface ladder ordered: an inactive tab never wears the active surface', () => {
    // Neither at rest nor on hover: --bg-hover is reserved for "selected".
    expect(panelTabClass(false)).not.toContain('bg-[var(--bg-hover)]');
  });

  it('keeps the single Button control height and text size for both sizes', () => {
    for (const size of ['default', 'sm'] as const) {
      for (const active of [true, false]) {
        const t = tokens(panelTabClass(active, size));
        expect(t).toContain('h-9');
        // text-sm now arrives from the size variant BEFORE the state's
        // text-[var(--…)] override, so a twMerge reclassification of the
        // arbitrary text color into the font-size group would silently eat it.
        expect(t).toContain('text-sm');
      }
    }
  });

  it('defaults to the standard size so a bare call matches the header tabs', () => {
    expect(panelTabClass(true)).toBe(panelTabClass(true, 'default'));
  });

  it('is a group with a positioning context (tabs host absolutely placed close/menu controls)', () => {
    expect(panelTabClass(true)).toContain('group');
    expect(panelTabClass(true)).toContain('relative');
  });
});

describe('panelTabInnerHoverClass', () => {
  it('tints the active chip neutrally, since --bg-hover has no token above it in either theme', () => {
    // Whole tokens, not substrings: `group-hover:bg-black/20` contains
    // `hover:bg-black/20` but fires when the TAB is hovered, not the control.
    const active = tokens(panelTabInnerHoverClass(true));
    expect(active).toContain('hover:bg-black/20');
    expect(active).toContain('dark:hover:bg-white/20');
    // Reusing the tab's own surface would make the control invisible on it.
    expect(panelTabInnerHoverClass(true)).not.toContain('--bg-hover');
  });

  it('steps above the tab hover surface on an inactive tab so the control stays distinguishable', () => {
    expect(panelTabInnerHoverClass(false)).toBe('hover:bg-[var(--bg-hover)]');
    expect(panelTabClass(false)).not.toContain('--bg-hover');
  });
});
