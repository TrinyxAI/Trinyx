'use client';

import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
// ApiError carries the STATUS, which is the only thing that separates "this
// install does not serve generation" from "the request did not get through".
import { ApiError } from '@/lib/api/api-client';
import { generationService, type GenerationModel } from '@/lib/api/orchestrator/generation.service';

/**
 * The generation model catalogue, and what its answer says about whether the
 * surface exists at all.
 *
 * <p><b>Why the second half matters.</b> The generation endpoints are
 * config-gated: an install started without generation enabled serves no
 * `/generation/models` and no `/generation/execute` at all, and answers 404 on
 * both. Every surface that offers a way IN to a generation therefore has two
 * questions to answer, not one: may this person write here, and does this
 * install have the thing at all. Reading only the first is how a default
 * self-hosted install ended up offering a Generate button that opened a dialog
 * onto "No generation model is available yet."
 *
 * <p>The query key, the fetcher and the cache lifetime are stated ONCE, here,
 * so the surfaces that ask this question share a single answer: an entry point
 * that checks availability and the dialog behind it must never cost two
 * requests, and must never disagree about what came back.
 */

/**
 * Shared with every reader of the catalogue. Changing it here splits the cache,
 * so a caller that opens a dialog would re-ask what the page already knows.
 */
export const GENERATION_MODELS_QUERY_KEY = ['generation-models'] as const;

/** Models change only on a catalog import, so one answer serves a session. */
export const GENERATION_MODELS_STALE_TIME = 5 * 60_000;

/**
 * What the catalogue's answer says about the surface behind it.
 *
 * <ul>
 *   <li>{@code ready} - it is served and has at least one model: a generation
 *       can actually be started.</li>
 *   <li>{@code empty} - it is served and answered with no model. The feature IS
 *       installed; its catalogue has not been seeded or synced yet, which an
 *       administrator can fix. Not the same thing as absent, and not something
 *       to hide.</li>
 *   <li>{@code absent} - answered 404: this install does not serve generation.
 *       Nothing anyone does in the app changes that.</li>
 *   <li>{@code unknown} - no answer yet, or one that does not say which of the
 *       above is true (in flight, a 5xx, a dropped connection). Uncertainty is
 *       its own state on purpose: a caller must not be able to mistake it for
 *       absence, because hiding a feature on a hiccup is a lie that looks
 *       exactly like the truth.</li>
 * </ul>
 */
export type GenerationAvailability = 'unknown' | 'absent' | 'empty' | 'ready';

export interface GenerationCatalogState {
  /** Every model the platform offers, empty until an answer arrives. */
  models: GenerationModel[];
  /** True while the FIRST answer is still outstanding. */
  isLoading: boolean;
  availability: GenerationAvailability;
}

/**
 * Read the generation catalogue through the shared cache.
 *
 * @param enabled pass false to hold the request back (a dialog that is closed,
 *        a reader who cannot write here anyway). A disabled reader still gets
 *        whatever another reader has already put in the cache.
 */
export function useGenerationModels(enabled: boolean = true): GenerationCatalogState {
  const { data, error, isLoading } = useQuery({
    queryKey: GENERATION_MODELS_QUERY_KEY,
    queryFn: () => generationService.getModels(),
    staleTime: GENERATION_MODELS_STALE_TIME,
    enabled,
  });

  // Stable identity: callers filter and quote off this list inside memos, and a
  // fresh array every render would re-run all of them.
  //
  // `Array.isArray` and not `?? []`, because the callers do not merely READ this
  // list, they `map`, `filter` and `find` over it. A 200 whose `models` is
  // anything but an array would pass a nullish default untouched and reach
  // those calls, which is a crash during render rather than a page with nothing
  // to offer. What the type promises is enforced here, once, at the boundary.
  const models = useMemo(() => (Array.isArray(data?.models) ? data.models : []), [data]);

  const availability = useMemo<GenerationAvailability>(() => {
    // A 404 is checked FIRST, and it beats a cached list. If generation is
    // turned off on an install that used to serve it, the stale models in hand
    // describe a surface that no longer answers.
    if (error instanceof ApiError && error.status === 404) return 'absent';
    // Counted off the NORMALISED list above, never off `data.models` again.
    // Two reasons, and the second is the one that bit:
    //
    //  - A 200 carrying no `models` array is not a hypothetical. Anything that
    //    answers this path without serving it (a proxy, a gateway, a test
    //    double) sends one, and reading `.length` straight off the payload
    //    threw inside a memo, DURING RENDER: one odd body took the entire page
    //    into the error boundary, so a chat composer that has nothing to do
    //    with generation stopped rendering at all. Degrading to "there is
    //    nothing to offer" is the honest outcome; taking the page down is not.
    //
    //  - The dialog lists `models`. Counting anything else lets the control's
    //    claim and the dialog's contents disagree, which is the very split this
    //    hook exists to close: one list, one verdict, one request.
    //
    // Still 'empty' rather than 'unknown' on such a body, deliberately. 'empty'
    // is what the reader actually faces (an answer arrived and it names no
    // model), and it keeps the entry point disabled WITH its reason instead of
    // enabled onto a dialog that would open on an empty list, which is the
    // exact regression this hook was written to end.
    if (data) return models.length > 0 ? 'ready' : 'empty';
    // Anything else - still in flight, a 5xx, a transport that never
    // delivered - says nothing about whether the feature exists.
    return 'unknown';
  }, [data, error, models]);

  return { models, isLoading, availability };
}
