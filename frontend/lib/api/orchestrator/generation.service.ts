/**
 * Generation Service
 *
 * The model catalog behind the `core:generate` node. A model id decides the
 * format produced, which parameters are accepted and what a run costs, so the
 * inspector needs the whole row (limits included) before it can render a form
 * or quote a price.
 *
 * The `apiToolId` + `integrationName` on each row are what the platform price
 * quote is keyed on: they feed
 * `orchestratorApi.getPlatformCredentialPublicInfo`, which is the single quote
 * path shared with the MCP step inspector.
 */

import { apiClient } from '../api-client';

/** Value restriction on one generation parameter, as declared by the model. */
export interface GenerationLimit {
  /** Discrete accepted values. Absent when the parameter is not an enumeration. */
  allowed?: (string | number)[];
  /** Inclusive lower bound for a numeric parameter. */
  min?: number;
  /** Inclusive upper bound for a numeric parameter. */
  max?: number;
}

/**
 * The starting price shipped with the catalog seed, as components rather than a
 * total, so a surface can say "60 credits per second" instead of only stating a
 * number. The amount actually charged comes from the platform quote, which an
 * admin can have overridden.
 */
export interface GenerationPrice {
  /** What `unitCredits` is charged per: call, second, minute, image or character. */
  unit: string;
  /** Fixed component, as a decimal string. */
  baseCredits: string;
  /** Credits per unit, as a decimal string. */
  unitCredits: string;
  minCredits?: string;
  maxCredits?: string;
}

export interface GenerationModel {
  /** Public model id, what the node stores in `params.model`. */
  model: string;
  /** Format produced: image, video, audio, voice, music, ... */
  kind: string;
  label: string;
  provider: string;
  iconSlug: string | null;
  /** Catalog endpoint that runs it - the key the published price hangs off. */
  apiToolId: string | null;
  /** Platform credential the price hangs off, or null when the API has none. */
  integrationName: string | null;
  /** Unified parameter names this model accepts. Anything else is refused. */
  accepts: string[];
  /**
   * For each file this model takes: what the file IS to it, and how many of
   * them it accepts.
   *
   * <p>The slot name says an image goes here; it does not say whether the image
   * comes back changed, becomes the first frame of a clip, or only lends its
   * style. Runway and xAI animate FROM a still, OpenAI and Stability transform
   * the file itself, Flux never returns it and takes only its look. One label
   * for all three mis-describes two of them, and the reader finds out after
   * paying.
   *
   * <p>Absent for a model that takes no file.
   */
  inputs?: Record<string, { role: string; maxItems: number }>;
  /** Subset of `accepts` the provider will refuse the call without. */
  required: string[];
  limits: Record<string, GenerationLimit>;
  /** Which parameter the price multiplies, or null for a model sold per call. */
  billedOn: string | null;
  /**
   * What a call on this model is COUNTED in: second, image, character or call.
   *
   * <p>Not what it is SOLD by. A model counted in seconds can be published per
   * minute, which is the same dimension at another scale and converts fine; a
   * rate published per image cannot price it at all. A surface asking for a
   * quote sends this so the quote can refuse that second pair instead of
   * showing an amount every run is then refused for.
   */
  measuredUnit: string | null;
  /**
   * What `billedOn` becomes when nothing is typed, as a decimal string.
   *
   * <p>The run uses this size when the parameter is left empty, so the estimate
   * has to use it too. Without it the price falls silent on the most common
   * state of the form, which is the state an author is in while deciding
   * whether they can afford the node.
   */
  defaultQuantity: string | null;
  price: GenerationPrice;
  /** True when the job finishes asynchronously and the run waits for it. */
  async: boolean;
}

export interface GenerationModelsResponse {
  models: GenerationModel[];
  count: number;
  kinds: string[];
}

/** What a finished generation hands back. */
export interface GenerationResult {
  success: boolean;
  data?: {
    model: string;
    kind: string;
    provider: string;
    /**
     * The stored asset, as a FileRef. Absent on failure.
     *
     * <p>`id` is what a browser can follow: it resolves through
     * `fileRefToUrl` to the authenticated `/files/by-id/{id}/raw` route. `path`
     * is the S3 object KEY (`tenant/workflow/...`), which is not a URL, is
     * resolved relative to the current page if used as one, and leaks the
     * tenant prefix into the DOM. Declaring `id` here is what stops a paid
     * asset shipping behind a dead link.
     */
    file?: {
      id?: string;
      path?: string;
      name?: string;
      mimeType?: string;
      size?: number;
    } & Record<string, unknown>;
    /** The size the run was billed on, counted in `billed_unit`. */
    billed_quantity?: number;
    billed_unit?: string;
    provider_response?: Record<string, unknown>;
  };
  error?: string;
  errorCode?: string;
}

/** What the caller asks for: a model, the unified params, and which key pays. */
export interface GenerationRequest {
  model: string;
  params: Record<string, unknown>;
  credential_source?: 'platform' | 'user';
  /**
   * WHICH of the caller's own keys runs it, when they hold several for the
   * provider.
   *
   * <p>Only read beside `credential_source: 'user'`, and only as a preference:
   * an id whose credential has since been deleted falls back to the account's
   * default key for the integration rather than failing, so a saved choice
   * survives a reconnection. Omit it to run on that default.
   */
  credential_id?: number | null;
}

export class GenerationService {
  /**
   * Every generation model the platform offers, optionally narrowed to one kind.
   */
  async getModels(kind?: string): Promise<GenerationModelsResponse> {
    return apiClient.get<GenerationModelsResponse>(
      '/generation/models',
      kind ? { params: { kind } } : undefined,
    );
  }

  /**
   * Run one generation and wait for the asset.
   *
   * <p>This is a PURCHASE, not a read: it reserves, calls a paid provider and
   * commits. It resolves with `success: false` and a message rather than
   * throwing for a refusal the user can act on (no credits, no published
   * price, a parameter the model rejects), because those are answers rather
   * than faults.
   *
   * <p>No billing scope is sent. The server mints its own, so nothing the
   * browser says can decide where the charge lands.
   */
  async execute(request: GenerationRequest): Promise<GenerationResult> {
    // The default client budget is 30 seconds. A generation is not a read: the
    // server holds the request while it submits to the provider and polls for
    // the result, which for video is minutes. Giving up at 30 seconds does NOT
    // cancel any of that. The server finishes, stores the asset and COMMITS the
    // charge, so the default budget turns a paid, delivered generation into a
    // "Request timeout" on screen.
    //
    // No timeout at all is the right budget for the client itself, because the
    // client abandoning is never useful here. It is not the whole story: the
    // Next proxy caps its own hop and an edge proxy in front of it caps the
    // connection regardless, so a long generation can still lose the CONNECTION
    // while completing on the server. That is why the caller must present a
    // lost connection as "still running and still charged" rather than as a
    // failure. See GENERATION_STILL_RUNNING.
    // retries: 0 is NOT optional here, and removing the timeout without it is
    // worse than leaving both alone. The client retries a 5xx by default, and
    // an edge proxy that gives up on a long generation answers 502/504/524.
    // That is not a failed request to replay: the first submission is still
    // running upstream, so a retry starts a SECOND generation and the customer
    // pays for two. The 30-second abort used to hide this by turning the same
    // event into an AbortError, which the client does not retry.
    //
    // A generation is not idempotent and must be submitted exactly once. If it
    // does not come back, the caller's job is to say so, not to send it again.
    return apiClient.post<GenerationResult>(
      '/generation/execute', request, { timeout: 0, retries: 0 },
    );
  }
}

export const generationService = new GenerationService();
