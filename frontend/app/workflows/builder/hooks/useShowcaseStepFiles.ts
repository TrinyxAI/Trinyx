'use client';

import { useQuery } from '@tanstack/react-query';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import type { ShowcaseStepFile, ShowcaseStepFiles } from '@/lib/api/orchestrator/publication.service';
import { getActivePublicPreview } from '@/contexts/PublicationSnapshotContext';

/** Stable empty result so a consumer's memo deps do not change identity on every render. */
const EMPTY: ShowcaseStepFiles = {};

/**
 * The frozen per-node file results of the publication currently being previewed.
 *
 * <p>Outside a publication preview this returns `{}` and issues no request: it is the fallback
 * source for a canvas that CANNOT use the live one. The authenticated canvas reads its file
 * strips from the run's step outputs ({@link useRunOutputData}), and every one of those reads is
 * disabled in preview - an anonymous visitor has no token, and the endpoints are tenant-scoped.
 * Without this hook a published workflow's file nodes show nothing at all.
 *
 * <p>One query per publication, shared across every node on the canvas through the react-query
 * cache: the payload is one entry per file-producing node, so fetching it whole once beats a
 * per-node round trip. The URLs are signed with an expiry (`publication.showcase.presign-expiry-
 * minutes`, 4 hours by default), so the staleTime is set well inside it: a canvas left open
 * longer re-fetches on its next mount rather than serving links that have quietly gone 403.
 */
export function useShowcaseStepFiles(): ShowcaseStepFiles {
  const publicCtx = getActivePublicPreview();
  const publicationId = publicCtx?.publicationId ?? null;
  const remote = !!publicCtx?.remote;

  const { data } = useQuery({
    queryKey: ['showcase-step-files', publicationId, remote],
    queryFn: () => publicationService.getShowcaseStepFiles(publicationId as string, remote),
    enabled: !!publicationId,
    staleTime: 5 * 60 * 1000,
    refetchOnMount: false,
    refetchOnWindowFocus: false,
  });

  return data ?? EMPTY;
}

/**
 * The file one node shows for one epoch, or null.
 *
 * <p>Epoch resolution mirrors the owner's canvas rather than being strict about it.
 *
 * <p>No epoch selected is the state every visitor LANDS ON - `viewingEpoch` starts null ("all
 * epochs") and only a click on an epoch pill sets it - so refusing to answer there would leave
 * every multi-epoch publication with no pill at all, in the one view everybody sees. The owner's
 * canvas is not strict in that state either: it loads every epoch's items FOR THIS NODE and
 * opens on the newest of them. So this walks the epochs newest-first and answers with the
 * newest one that actually carries this node - not merely the newest epoch, which would leave
 * a node that was skipped on the last fire blank while its owner still sees the file it
 * produced on the fire before.
 *
 * <p>An epoch that IS selected but absent from the map still falls back when the map holds
 * exactly one epoch, because a publication captured with a pin is RENUMBERED to a single key
 * and the two legitimately disagree. With several epochs a selected-but-absent epoch answers
 * null: the visitor asked for a specific run, and showing another one's file would be worse
 * than showing none.
 *
 * <p>The alias match falls back to case-insensitive: aliases are stored as the node wrote them
 * and looked up case-insensitively everywhere else on the read path, so an exact-only match here
 * would drop the pill for a node whose label case drifted.
 */
export function selectShowcaseStepFile(
  stepFiles: ShowcaseStepFiles,
  epoch: number | null | undefined,
  stepAlias: string | undefined,
): ShowcaseStepFile | null {
  if (!stepAlias) return null;
  const epochKeys = Object.keys(stepFiles);
  if (epochKeys.length === 0) return null;

  if (epoch == null) {
    for (const key of newestFirst(epochKeys)) {
      const hit = fileForAlias(stepFiles[key], stepAlias);
      if (hit) return hit;
    }
    return null;
  }

  let perAlias = stepFiles[String(epoch)];
  if (!perAlias && epochKeys.length === 1) {
    perAlias = stepFiles[epochKeys[0]];
  }
  return perAlias ? fileForAlias(perAlias, stepAlias) : null;
}

/** One epoch's entry for a node: exact alias first, then case-insensitively. */
function fileForAlias(
  perAlias: Record<string, ShowcaseStepFile> | undefined,
  stepAlias: string,
): ShowcaseStepFile | null {
  if (!perAlias) return null;
  const exact = perAlias[stepAlias];
  if (exact) return exact;

  const wanted = stepAlias.toLowerCase();
  for (const [alias, file] of Object.entries(perAlias)) {
    if (alias.toLowerCase() === wanted) return file;
  }
  return null;
}

/**
 * The epoch keys, newest first, compared NUMERICALLY.
 *
 * Lexicographic order would call "9" newer than "10", which is exactly the range a reusable
 * trigger crosses on its tenth fire. A key that is not a number sorts after every one that is,
 * so a malformed section is searched last instead of first.
 */
function newestFirst(epochKeys: string[]): string[] {
  return [...epochKeys].sort((a, b) => {
    const na = Number(a);
    const nb = Number(b);
    if (Number.isNaN(na) && Number.isNaN(nb)) return 0;
    if (Number.isNaN(na)) return 1;
    if (Number.isNaN(nb)) return -1;
    return nb - na;
  });
}
