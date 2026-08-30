/**
 * Window-event channel between the app header and the page being VIEWED at
 * `/app/interface/<id>`, the same CustomEvent pattern the Files browser and the resource
 * folders already use ({@code filesHeaderBus}, {@code foldersHeaderBus}).
 *
 * <p>The header owns the row of buttons beside the page title; the viewer owns the page and is
 * the only thing that knows what can be controlled about it. So the viewer says whether it has
 * any controls to offer, and the header says when the reader asked to see them.
 */

/** Viewer → header: whether opening the controls would show anything. */
export const INTERFACE_VIEWER_CONTROLS_CHANGED = 'interfaceViewerControlsChanged';
/** Header → viewer: the reader pressed the controls button. */
export const INTERFACE_VIEWER_CONTROLS_TOGGLE = 'interfaceViewerControlsToggle';

export interface InterfaceViewerControlsState {
  /**
   * True when the page on screen has at least one thing to control. Today that means it plays
   * audio; the button is hidden otherwise rather than opening onto an empty panel.
   */
  available: boolean;
  /** Whether the panel is showing, so the button beside Edit can say what pressing it does. */
  open: boolean;
  /**
   * Whether the page is making a sound right now. The header needs it because the panel can be
   * dismissed while the sound plays on, and audio with nothing on screen accounting for it is
   * the thing every sibling surface takes care to avoid.
   */
  soundOn: boolean;
}

/** Viewer: announce whether there is anything to control. */
export function emitInterfaceViewerControls(state: InterfaceViewerControlsState): void {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(
    new CustomEvent<InterfaceViewerControlsState>(INTERFACE_VIEWER_CONTROLS_CHANGED, { detail: state }),
  );
}

/** Header: subscribe to what the viewer can offer. Returns an unsubscribe fn. */
export function onInterfaceViewerControls(
  handler: (state: InterfaceViewerControlsState) => void,
): () => void {
  if (typeof window === 'undefined') return () => {};
  const listener = (e: Event) => handler((e as CustomEvent<InterfaceViewerControlsState>).detail);
  window.addEventListener(INTERFACE_VIEWER_CONTROLS_CHANGED, listener);
  return () => window.removeEventListener(INTERFACE_VIEWER_CONTROLS_CHANGED, listener);
}

/** Header: the reader pressed the controls button. */
export function emitInterfaceViewerControlsToggle(): void {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent(INTERFACE_VIEWER_CONTROLS_TOGGLE));
}

/** Viewer: subscribe to the controls button. Returns an unsubscribe fn. */
export function onInterfaceViewerControlsToggle(handler: () => void): () => void {
  if (typeof window === 'undefined') return () => {};
  const listener = () => handler();
  window.addEventListener(INTERFACE_VIEWER_CONTROLS_TOGGLE, listener);
  return () => window.removeEventListener(INTERFACE_VIEWER_CONTROLS_TOGGLE, listener);
}
