'use client';

import * as React from 'react';

/**
 * The one way IN to the generation dialog, for every surface that offers one.
 *
 * <p><b>Why this is a module and not three copies.</b> Each entry point needs
 * the same two things: the dialog fetched lazily, and a way to fetch it BEFORE
 * the click. Written per surface, the pair drifted: the second copy is where a
 * warmer gets forgotten, or a `React.lazy` gets called inside a render and
 * remounts the dialog on every keystroke. Stating it once means a new entry
 * point is two imports and cannot get either half wrong.
 *
 * <p>Deliberately NOT the place to ask whether a generation is possible: that
 * question goes through {@code useGenerationModels}. Importing this module is
 * cheap either way (the dialog's chunk is fetched by the arrow below, not by
 * the import), but keeping the question out of it keeps the two concerns
 * separable: a surface can ask whether to offer a generation without owning
 * the dialog at all.
 */

/**
 * Idempotent by construction: the module registry caches the fetch, so warming
 * repeatedly and then opening costs exactly one request.
 */
export const importGenerationModal = () => import('@/components/chat/CreateGenerationModal');

/**
 * The dialog, kept out of every caller's first load.
 *
 * <p>It carries the quote client, the credit hook and the shared price helpers,
 * and most visits to a page that offers it never open it.
 */
export const CreateGenerationModal = React.lazy(() =>
  importGenerationModal().then((m) => ({ default: m.CreateGenerationModal })));

/**
 * Fetch the dialog's chunk before it is asked for.
 *
 * <p>Lazy alone puts the whole download on the click, and the chunk is not
 * small, so pressing Generate meant waiting with nothing on screen. A pointer
 * arriving at the button, or a keyboard landing on it, is the earliest honest
 * signal that it is about to be pressed, and by then the fetch is free.
 *
 * <p>Bind it to BOTH `onMouseEnter` and `onFocus`: they are two different
 * readers, and neither should be the one that waits.
 */
export function warmGenerationModal() {
  void importGenerationModal();
}
