import { cn } from "@/lib/utils"
import { buttonVariants } from "@/components/ui/button"

/**
 * Shared style for panel tab buttons: the side-panel header tabs and the
 * sub-tab bars (workflow panel, agent panel, agent execution detail).
 *
 * Composed FROM `buttonVariants` rather than re-declaring it, so a tab inherits
 * the Button shape, control height, motion and focus ring for free and cannot
 * drift from it. Both states start from `ghost` and override its legacy inverted
 * hover, so a tab is always a flat chip on the panel surface.
 *
 * The active tab uses `--bg-hover`, the token the collapsed sidebar rail already
 * uses for a selected nav item (`bg-surface-hover`, mapped in globals.css). The
 * solid accent it replaced was too heavy for a whole bar of tabs.
 *
 * Surface ladder: transparent -> `--bg-tertiary` on hover -> `--bg-hover` when
 * active. That ladder is FAR wider in dark (#2a2925 -> #57534e) than in light
 * (#eceff3 -> #e5e7eb, barely one perceptible step), so in light mode the fill
 * alone does not carry the state. Two extra cues do, and both are load-bearing,
 * not decoration: a hairline `--border-color` outline, and the text stepping
 * from `--text-secondary` to `--text-primary` at `font-semibold`.
 *
 * Both bar backgrounds must stay `bg-theme-primary`: that is what the Button
 * focus ring offset (`--bg-primary`) assumes, and what keeps every step of the
 * ladder visible.
 */
export function panelTabClass(
  active: boolean,
  size: "default" | "sm" = "default"
): string {
  return cn(
    buttonVariants({ variant: "ghost", size }),
    // `group` + `relative` host the absolutely positioned close/menu controls.
    "group relative whitespace-nowrap",
    active
      ? "bg-[var(--bg-hover)] border-[var(--border-color)] font-semibold text-[var(--text-primary)] hover:bg-[var(--bg-hover)] hover:text-[var(--text-primary)]"
      : "text-[var(--text-secondary)] hover:bg-[var(--bg-tertiary)] hover:text-[var(--text-primary)]"
  )
}

/**
 * Hover surface for a control nested INSIDE a tab (close button, tab menu).
 * The tab already carries a surface in both states, so the control has to step
 * off it: a neutral tint on the active chip (`--bg-hover` has no token above it
 * in either theme), and `--bg-hover` itself on an inactive tab, whose own hover
 * only reaches `--bg-tertiary`. The tint is 20%, not less: below that it stops
 * reading as an affordance against `--bg-hover` in either theme.
 */
export function panelTabInnerHoverClass(active: boolean): string {
  return active
    ? "hover:bg-black/20 dark:hover:bg-white/20"
    : "hover:bg-[var(--bg-hover)]"
}
