'use client';

import { useMemo } from 'react';
import { useQueries } from '@tanstack/react-query';
import {
  generationService,
  type GenerationModel,
  type GenerationOption,
} from '@/lib/api/orchestrator/generation.service';

/**
 * The values a provider offers for a parameter, fetched with the reader's key.
 *
 * <p><b>Why these are not in the model row.</b> A catalogue list works for a set
 * the PROVIDER defines: eleven voices, seventeen style presets. It cannot work
 * for a set the ACCOUNT defines. An ElevenLabs voice belongs to whoever owns the
 * key: the shared defaults, plus everything that account cloned or bought. No
 * list shipped in a seed is true for two different readers, and a wrong voice id
 * is twenty opaque characters that fail at the provider AFTER the call is paid
 * for. So the row only says asking is worth it, and this does the asking.
 *
 * <p><b>Why the payer is part of the question.</b> A list read on the platform's
 * key is not the reader's own. Both are in the cache key, so moving the payer
 * toggle re-asks rather than leaving a stale list on screen that offers ids the
 * chosen key never had.
 *
 * <p>One request per dynamic parameter, and only for the parameters the model
 * marked. In practice that is one (a voice), which is why this stays a plain
 * fan-out rather than a batching endpoint.
 */

/** What one parameter's fetch has produced so far. */
export interface GenerationOptionsState {
  options: GenerationOption[];
  isLoading: boolean;
  /**
   * Why there is nothing to offer, in the reader's language, or null.
   *
   * <p>Kept distinct from an empty list on purpose: "no key is connected" and
   * "we could not ask" are different facts from "this account has no voices",
   * and a dropdown showing nothing states the last one.
   */
  error: string | null;
  /** True when the list is a sample of `totalCount`, not the whole set. */
  truncated: boolean;
  totalCount: number | null;
}

const EMPTY_STATE: GenerationOptionsState = {
  options: [],
  isLoading: false,
  error: null,
  truncated: false,
  totalCount: null,
};

/** Matches the server's own cache for the same answer, so neither out-waits the other. */
export const GENERATION_OPTIONS_STALE_TIME = 5 * 60_000;

/**
 * Which of a model's parameters have values only the provider can name.
 *
 * <p>Exported because the dialog needs the same answer to decide what to render
 * before any fetch resolves: a field that WILL become a dropdown must not first
 * appear as a text box and then change shape under the reader's cursor.
 */
export function dynamicParamsOf(model: GenerationModel | null): string[] {
  if (!model) return [];
  return (model.accepts ?? []).filter((name) => model.limits?.[name]?.optionsAvailable === true);
}

export function useGenerationOptions(
  model: GenerationModel | null,
  credentialSource: 'platform' | 'user',
  credentialId: number | null,
  enabled: boolean = true,
): Record<string, GenerationOptionsState> {
  const params = useMemo(() => dynamicParamsOf(model), [model]);
  const modelId = model?.model ?? null;

  return useQueries({
    queries: params.map((parameter) => ({
      // credentialId only narrows a 'user' read: on the platform key it is not
      // sent, so leaving it in the key would split the cache on a value that
      // changed nothing about the answer.
      queryKey: [
        'generation-options',
        modelId,
        parameter,
        credentialSource,
        credentialSource === 'user' ? credentialId : null,
      ] as const,
      queryFn: () =>
        generationService.getModelOptions(
          modelId as string,
          parameter,
          credentialSource,
          credentialSource === 'user' ? credentialId : null,
        ),
      enabled: enabled && modelId != null,
      staleTime: GENERATION_OPTIONS_STALE_TIME,
      // Not retried. A refusal here is an ANSWER the reader can act on
      // ("connect a key"), and the one case that is not costs the provider
      // another call for the same question.
      retry: false,
    })),
    // Folded inside the query client rather than in a useMemo of my own: it
    // re-runs only when one of these queries actually changes, so the record
    // handed to the dialog keeps its identity across unrelated renders.
    combine: (results) => {
      const byParam: Record<string, GenerationOptionsState> = {};
      params.forEach((parameter, i) => {
        const r = results[i];
        if (!r) {
          byParam[parameter] = EMPTY_STATE;
          return;
        }
        const body = r.data;
        byParam[parameter] = {
          options: body?.success ? body.options ?? [] : [],
          isLoading: r.isLoading,
          // A transport failure and a refusal by the server arrive by different
          // routes and mean the same thing to the person: the list could not be
          // had, and the field stays typed rather than chosen.
          error: r.error
            ? (r.error as Error).message || String(r.error)
            : body && !body.success
              ? body.error ?? null
              : null,
          truncated: body?.truncated === true,
          totalCount: body?.total_count ?? null,
        };
      });
      return byParam;
    },
  });
}
