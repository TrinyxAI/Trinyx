import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

/** Where an application on the applications page came from. */
export type ShowcaseAppSource = 'published' | 'acquired';

/** What an application card knows about one app before it decides how to preview it. */
export interface ShowcaseAppInput {
  publication: WorkflowPublication;
  source: ShowcaseAppSource;
  /** Run id of the application-dedicated run, when the caller has resolved one. */
  applicationRunId?: string;
}

/** The props {@code ShowcasePreview} needs to render THIS application's live showcase. */
export interface ShowcaseBinding {
  /** False when there is nothing to render - the caller draws its cover instead. */
  canPreview: boolean;
  runId?: string;
  interfaceId?: string;
  /** Set on an acquired app: the publisher's frozen, publication-scoped showcase. */
  publicationId?: string;
  authenticated: boolean;
  remote: boolean;
  isAcquired: boolean;
}

/**
 * How an application is previewed, decided once for every surface that shows one.
 *
 * <p>The rules are not obvious and getting one wrong shows up as a card that silently
 * falls back to its cover tile:
 * <ul>
 *   <li>An acquired app's run and interface belong to the PUBLISHER, so an authenticated
 *       per-run render is cross-tenant and 404s. It reads the frozen showcase of the
 *       publication instead.</li>
 *   <li>A LOCAL acquired app reads that showcase through the authenticated endpoint, so the
 *       acquirer's receipt still admits it once the publisher unpublishes the source.</li>
 *   <li>A cloud-acquired app (cloud-linked CE) carries a CLOUD publication id that is absent
 *       from the local DB, so its read is routed through the remote by-id proxy.</li>
 * </ul>
 *
 * <p>Shared by the application card and the face of an application FOLDER, which shows the
 * same live showcases in miniature.
 */
export function showcaseBindingFor({ publication, source, applicationRunId }: ShowcaseAppInput): ShowcaseBinding {
  const isAcquired = source === 'acquired';
  const remote = !!publication.remote;
  const runId = applicationRunId || publication.showcaseRunId || undefined;
  const interfaceId = publication.showcaseInterfaceId || undefined;

  return {
    canPreview: !!runId && !!interfaceId,
    runId,
    interfaceId,
    publicationId: isAcquired ? publication.id : undefined,
    authenticated: isAcquired && !remote,
    remote,
    isAcquired,
  };
}
