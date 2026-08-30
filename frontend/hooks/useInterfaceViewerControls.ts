'use client';

import { useEffect, useState } from 'react';
import {
  emitInterfaceViewerControlsToggle,
  onInterfaceViewerControls,
  type InterfaceViewerControlsState,
} from '@/lib/interfaces/interfaceViewerBus';

const NOTHING: InterfaceViewerControlsState = { available: false, open: false, soundOn: false };

export interface InterfaceViewerControlsHeaderState {
  /**
   * Press to show or hide the page's controls. Undefined when there is nothing to control:
   * an inert button beside Edit is worse than no button, because it promises a panel and then
   * opens onto nothing.
   */
  onToggle?: () => void;
  /** Whether the panel is showing, so the button can say so. */
  open: boolean;
  /** Whether the page is currently making a sound, so that is visible with the panel closed. */
  soundOn: boolean;
}

/**
 * What the app header needs to know about the page being viewed at `/app/interface/<id>`.
 *
 * <p>The two halves of one button live apart: the header draws it, and only the page knows
 * whether pressing it would show anything. Extracted from the header because the header itself
 * pulls in the router, the org store, streaming and billing, none of which takes part in this
 * decision - and because a decision left inline in a nine-hundred-line component is a decision
 * nothing tests.
 *
 * @param isInterfacePage whether an interface is what is on screen at all. Anything the viewer
 *   said is dropped the moment it is not, or the header would go on offering controls for a page
 *   the reader has left.
 */
export function useInterfaceViewerControls(isInterfacePage: boolean): InterfaceViewerControlsHeaderState {
  const [state, setState] = useState<InterfaceViewerControlsState>(NOTHING);

  useEffect(() => {
    if (!isInterfacePage) {
      setState(NOTHING);
      return;
    }
    return onInterfaceViewerControls(setState);
  }, [isInterfacePage]);

  const live = isInterfacePage && state.available;
  return {
    onToggle: live ? emitInterfaceViewerControlsToggle : undefined,
    open: live && state.open,
    soundOn: live && state.soundOn,
  };
}
