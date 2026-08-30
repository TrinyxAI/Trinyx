import type { SidePanelContextValue } from '@/contexts/SidePanelContext';

/**
 * What the header's panel button does, in one place.
 *
 * "Open" is the wrong question for it: a detached window collapsed to a strip is
 * open and SHOWS NOTHING, so a toggle that branched on `isOpen` dismissed the very
 * panel the user pressed the button to bring up, and it took a second press, plus
 * the panel vanishing in between, to get it back.
 *
 * Two steps, in this order. `bringForward()` un-shades and reports whether that was
 * the WHOLE job: when it was, the button stops there, because the user collapsed a
 * window that was showing something they chose and the open branch would swap them
 * onto a different tab. Only a panel that is genuinely not forward reaches
 * `openWhenClosed`, which is the one part that differs per page.
 *
 * Exported so the decision is tested as it runs. The header itself pulls in the
 * router, the org store, streaming and billing, none of which takes part in it, and
 * a test that restated these four lines would certify a copy of them.
 */
export function togglePanelFromHeader(
  sidePanel: SidePanelContextValue | null,
  openWhenClosed: (panel: SidePanelContextValue) => void,
): void {
  if (!sidePanel) return;
  if (sidePanel.bringForward()) return;
  if (sidePanel.isForward) {
    sidePanel.close();
    return;
  }
  openWhenClosed(sidePanel);
}
