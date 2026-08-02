import { cn } from "@/lib/utils"
import { buttonVariants } from "@/components/ui/button"

/**
 * Shared style for the floating chrome that sits ON the workflow canvas, in BOTH
 * edit and run mode: the edit/run mode toggle, the run identity bar and the
 * bottom toolbar.
 *
 * Those three surfaces were the last pill-shaped islands in the app (`rounded-full`
 * containers, `rounded-full` icon buttons, hand-rolled hover colors). Everything
 * around them - the Button component, the side-panel tabs (`panelTabClass`) - moved
 * to the flat square system: `rounded-xl`, hairline `--border-color`, color-only
 * transitions, one control height. This module is what lets the canvas chrome join
 * it instead of restating it.
 *
 * Composed FROM `buttonVariants`, exactly like `panelTabClass`, so a chrome control
 * inherits the Button shape, control height, motion and focus ring and cannot drift
 * from them. The active/inactive surface ladder is the SAME one the panel tabs use
 * (transparent -> `--bg-tertiary` on hover -> `--bg-hover` when active, plus the
 * hairline outline that carries the state in light mode, where the fills are barely
 * one perceptible step apart).
 */

/**
 * The chrome container itself: a square card floating over the nodes. One radius
 * step above the controls it holds (`rounded-2xl` outside, Button `rounded-xl`
 * inside), a hairline border so it reads as a surface against a busy canvas, and a
 * translucent + blurred background so nodes stay legible underneath.
 *
 * Deliberately FLAT: no drop shadow. The hairline border already separates the
 * card from the canvas, and a shadow on a small floating chip reads as a raised
 * bubble rather than as chrome.
 */
export const canvasChromeSurfaceClass =
  "rounded-2xl border border-[var(--border-color)] bg-[var(--bg-primary)]/95 backdrop-blur"

/**
 * A control that IS a chrome surface on its own - it floats directly on the
 * canvas with no card under it (the add-node button, the standalone run-history
 * chip). Square and Button-shaped at the Button `icon` size, h-9/36px, which is
 * the height every canvas chrome surface shares.
 *
 * A control that sits INSIDE a card (mode toggle, bottom toolbar) uses
 * `canvasChromeCompactButtonClass` instead, so the card itself lands on 36px.
 */
export function canvasChromeButtonClass(active = false, className?: string): string {
  return cn(
    buttonVariants({ variant: "ghost", size: "icon" }),
    // shrink-0: the toolbar is a horizontally scrolling flex row on a narrow
    // canvas, and without this the icons squash instead of scrolling.
    "shrink-0",
    active
      ? "bg-[var(--bg-hover)] border-[var(--border-color)] text-[var(--text-primary)] hover:bg-[var(--bg-hover)] hover:text-[var(--text-primary)]"
      : "text-[var(--text-secondary)] hover:bg-[var(--bg-tertiary)] hover:text-[var(--text-primary)]",
    className,
  )
}

/**
 * A control nested INSIDE a chrome card: the two mode-toggle buttons and every
 * bottom-toolbar control.
 *
 * The 28px footprint is arithmetic, not taste. Every canvas chrome surface is
 * `CANVAS_CHROME_TOP_ROW_HEIGHT_PX` tall, and a card is `p-1` around its controls,
 * so a nested control must be 36 - 4 - 4 = 28px for its card to land on 36. At the
 * standard 36px control size the toggle became a 44px block and the toolbar a 48px
 * slab next to a 36px run bar, which is what made them look oversized.
 */
export function canvasChromeCompactButtonClass(active = false, className?: string): string {
  return canvasChromeButtonClass(active, cn("h-7 w-7", className))
}

/** Padding a chrome card puts around its compact controls. See above for why. */
export const CANVAS_CHROME_CARD_PADDING_PX = 4

/**
 * Radius for the small non-interactive info labels carried by the run bar (status,
 * version, epoch, step-by-step). They are chips, not controls: too small for the
 * Button radius, so they take the step below it rather than staying circular.
 */
export const canvasChromeChipRadiusClass = "rounded-md"

/**
 * The one height every canvas chrome surface shares: the mode toggle and the run
 * info bar on the top edge, the add-node button in the corner, the toolbar at the
 * bottom. Exported so a test can hold them together rather than trusting four
 * independently written padding stacks.
 */
export const CANVAS_CHROME_TOP_ROW_HEIGHT_PX = 36
