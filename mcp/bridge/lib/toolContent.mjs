/**
 * Pure helpers that build the MCP `content` array for a backend tool result.
 *
 * Kept dependency-free (no @modelcontextprotocol/sdk import) so it can be unit-tested
 * directly with `node --test` - agent-cli-server.mjs imports these and wires them into
 * the CallTool handler.
 *
 * Two concerns:
 *  - vision media: a backend tool result may carry raw image bytes under
 *    `metadata.__media__` (kept in sync with ToolMediaMetadata.MEDIA_KEY on the Java
 *    side). The bridge is the ONLY consumer that turns these into native MCP image
 *    content blocks, so a vision-capable model receives the pixels instead of an
 *    opaque url. The heavy base64 is stripped from the `__BRIDGE_META__` text block.
 *  - frontend metadata: any remaining (light) metadata - iconSlug, visualization,
 *    marker, diff, gitStatus … - is re-emitted as a parseable `__BRIDGE_META__` text
 *    block the bridge extracts for the frontend (MCP has no metadata field).
 */

// Metadata key the backend uses to carry raw media bytes a vision-capable model must
// SEE (kept in sync with ToolMediaMetadata.MEDIA_KEY on the Java side).
export const MEDIA_META_KEY = '__media__';

/**
 * Sentinel that separates a tool result's visible text from its trusted frontend metadata.
 *
 * SECURITY: the bare `\n__BRIDGE_META__:` marker is a data/control-channel conflation - any
 * tool whose OUTPUT contains that literal (a shell command, a fetched page, a read file) could
 * forge trusted metadata and paint spoofed service-approval / tool-authorization / diff cards.
 * The bridge (server.mjs) mints a per-process random nonce into `BRIDGE_META_NONCE` and passes
 * it to this MCP subprocess, so only content this trusted producer emits carries the secret
 * marker `\n__BRIDGE_META__:<nonce>:`. Untrusted tool output cannot guess the nonce, so it can no
 * longer forge metadata. When the nonce is absent (standalone use with no bridge parsing the
 * output), we fall back to the legacy marker - there is no trust boundary to protect there.
 */
export function bridgeMetaMarker() {
  const nonce = process.env.BRIDGE_META_NONCE;
  return nonce ? `\n__BRIDGE_META__:${nonce}:` : '\n__BRIDGE_META__:';
}

/**
 * Split a tool result's joined text into its visible content and trusted metadata.
 *
 * Only the nonce-stamped marker from {@link bridgeMetaMarker} is honoured, so a bare or
 * wrong-nonce `__BRIDGE_META__:` embedded in untrusted tool output stays in `content` and
 * yields `metadata: null` (no forged card). `lastIndexOf` targets the trusted append, which
 * is always emitted as the final block. Kept here (pure, dependency-free) so both the
 * producer and the bridge parser share one definition and can be unit-tested together.
 */
export function parseBridgeMeta(text) {
  const marker = bridgeMetaMarker();
  const idx = text.lastIndexOf(marker);
  if (idx === -1) {
    return { content: text, metadata: null };
  }
  const metaJson = text.substring(idx + marker.length).trim();
  let metadata = null;
  try {
    metadata = JSON.parse(metaJson);
  } catch (e) {
    console.warn('[BRIDGE] Failed to parse tool metadata:', e.message);
  }
  return { content: text.substring(0, idx), metadata };
}

/** Keys an MCP CallToolResult may legitimately carry beside `content`. */
const ENVELOPE_SIBLING_KEYS = new Set(['isError', 'is_error', 'structured_content', 'structuredContent', '_meta']);

/**
 * True for an MCP CallToolResult envelope.
 *
 * The sibling test is what makes it safe: an object carrying `content` PLUS keys that are not
 * envelope machinery is a domain payload, not an envelope. Reading it as one makes
 * `{content: '', error: 'quota exceeded', code: 429}` come out empty, which the model takes
 * for "the tool returned nothing and that is fine". Deciding envelope-ness BEFORE looking at
 * the shape of `content` is what covers the empty-string and empty-array cases too; testing
 * `content` first only ever protected `content: null`.
 */
function isMcpEnvelope(value) {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false;
  if (!('content' in value)) return false;
  // An envelope's `content` is a block list, a CLI-flattened string, or absent. A number, a
  // boolean or a nested object under that key means this is a domain payload whose field
  // merely shares the name, and it must be serialised rather than read as an empty answer.
  const c = value.content;
  if (!(Array.isArray(c) || typeof c === 'string' || c === null || c === undefined)) return false;
  return Object.keys(value).every((k) => k === 'content' || ENVELOPE_SIBLING_KEYS.has(k));
}

/** Join the text blocks of an MCP content array, skipping image and malformed blocks. */
function blocksToText(blocks) {
  return blocks
    .filter((b) => b && b.type === 'text' && typeof b.text === 'string')
    .map((b) => b.text)
    .join('\n');
}

/**
 * Extract a tool result's visible text and its trusted metadata, whatever shape the CLI
 * handed us.
 *
 * agent-cli-server.mjs (the trusted MCP subprocess) appends metadata as
 * `\n__BRIDGE_META__:<nonce>:{json}` in a final text block. Only that nonce-stamped marker
 * is parsed, so untrusted tool output (shell/read/fetch) carrying a bare or wrong-nonce
 * sentinel stays plain text and can never forge trusted frontend cards.
 *
 * The input shapes, in the order they are tested:
 *  - null/undefined/empty string -> empty text;
 *  - string: already the joined text (Claude's flat path);
 *  - array: an MCP content block list (Claude passes the blocks through);
 *  - object with a `content` array: the WHOLE MCP CallToolResult envelope. Codex reports
 *    `item.result` this way. Serialising it (the previous behaviour at each call site)
 *    escaped the leading newline of the marker into a literal `\` + `n`, so `lastIndexOf`
 *    never matched: metadata was silently lost on every codex/gemini/mistral tool call and
 *    the raw envelope JSON became the visible result. Reading the blocks here fixes all
 *    call sites at once;
 *  - object with a `content` string: an envelope the CLI flattened into one block;
 *  - an envelope carrying no blocks at all -> empty text (see isEmptyEnvelope);
 *  - anything else: serialised as JSON.
 *
 * The final fallback is JSON, never `String(x)`, which would render `[object Object]`.
 */
export function extractToolResultAndMetadata(rawContent) {
  let text = '';
  // `== null` and not `!rawContent`: a falsy-but-real payload (0, false) must survive to the
  // serialiser below instead of being flattened to an empty result.
  if (rawContent == null || rawContent === '') {
    text = '';
  } else if (typeof rawContent === 'string') {
    text = rawContent;
  } else if (Array.isArray(rawContent)) {
    text = blocksToText(rawContent);
  } else if (isMcpEnvelope(rawContent)) {
    // Envelope-ness is decided by the object's SHAPE, before looking at `content`, so a
    // domain payload that merely owns a `content` key can never be read as an empty tool
    // answer. A block list joins its text blocks; a flattened string is the text; anything
    // else means the tool returned nothing, and serialising it would show `{"content": null}`
    // to the model as if that were the answer.
    if (Array.isArray(rawContent.content)) text = blocksToText(rawContent.content);
    else if (typeof rawContent.content === 'string') text = rawContent.content;
    else text = '';
  } else {
    // JSON, not String(x), which would render "[object Object]". JSON.stringify can throw
    // (cycle, BigInt) or return undefined (function, symbol), so both outcomes get a
    // readable placeholder: this helper must never be the thing that fails a tool result.
    try {
      text = JSON.stringify(rawContent, null, 2) ?? `[unserialisable tool result: ${typeof rawContent}]`;
    } catch (e) {
      // Readable, never `[object Object]` - which is what this branch exists to avoid.
      text = `[unserialisable tool result: ${e.message}]`;
    }
  }

  return parseBridgeMeta(text);
}

/**
 * Build the MCP `content` array for a SUCCESSFUL backend tool result.
 *  - the stringified result becomes a text block;
 *  - any `metadata.__media__` image descriptors become real MCP image content blocks;
 *  - the heavy media bytes are STRIPPED from the `__BRIDGE_META__` text block - only the
 *    light metadata (iconSlug, visualization, marker, …) is re-emitted for the frontend.
 */
export function buildSuccessContent(result) {
  const text = typeof result.result === 'string' ? result.result : JSON.stringify(result.result, null, 2);
  return withMediaAndMeta(text, result.metadata);
}

/**
 * Shared tail of both producers: leading text block, then the vision image blocks carried by
 * `metadata.__media__`, then the light metadata behind the trusted sentinel.
 *
 * Factored so the success and failure paths cannot diverge: they did, and the failure path
 * silently dropped both the metadata and the images. The sentinel block MUST stay last -
 * the parser resolves it with `lastIndexOf`.
 */
function withMediaAndMeta(text, rawMetadata) {
  const content = [{ type: 'text', text }];

  const metadata = rawMetadata && typeof rawMetadata === 'object' ? { ...rawMetadata } : null;
  if (metadata && Array.isArray(metadata[MEDIA_META_KEY])) {
    for (const m of metadata[MEDIA_META_KEY]) {
      if (m && m.type === 'image' && typeof m.dataBase64 === 'string' && m.dataBase64.length > 0) {
        content.push({ type: 'image', data: m.dataBase64, mimeType: m.mimeType || 'image/png' });
      }
    }
    delete metadata[MEDIA_META_KEY];
  }

  if (metadata && Object.keys(metadata).length > 0) {
    content.push({ type: 'text', text: `${bridgeMetaMarker()}${JSON.stringify(metadata)}` });
  }
  return content;
}

/**
 * Render a backend/CLI error value as readable text. THE single renderer: the producer
 * (`buildFailureContent`) and the consumer (`resolveItemErrorMessage` in adapterHelpers)
 * both call it, so the same error can no longer come out as its message on one side and a
 * useless `{}` on the other, which is what they did while a comment claimed they agreed.
 *
 * A `message` string wins over serialisation: `{message, code}` is the most common shape and
 * its message is the part worth showing, and an `Error` has no enumerable own keys, so plain
 * serialisation of one would yield `{}`. Returns '' when there is nothing to say, letting the
 * caller fall back to its own default.
 */
export function errorToText(err) {
  if (err === null || err === undefined) return '';
  if (typeof err === 'string') return err;
  // `false` / `0` / NaN are "no error" idioms, not diagnostics to render.
  if (err === false || err === 0 || (typeof err === 'number' && Number.isNaN(err))) return '';
  if (typeof err !== 'object') return String(err);
  // A `message` string wins for BOTH plain objects and Error instances; an Error without one
  // falls through to serialisation, which yields '' and lets the caller use its default.
  if (typeof err.message === 'string' && err.message) return err.message;
  try {
    const json = JSON.stringify(err);
    // '{}' / '[]' carry no information: let the caller use its default instead.
    return json === '{}' || json === '[]' ? '' : (json ?? '');
  } catch {
    return '';
  }
}

/**
 * Build the MCP `content` array for a FAILED backend tool result.
 *
 * The failure path used to emit the error text alone and drop `result.metadata` entirely, so
 * a failed tool reached the frontend with no iconSlug, no display name and no card. That is
 * the producer-side mirror of the metadata loss fixed on the parser side, and it applied to
 * EVERY provider, Claude included.
 *
 * Shares `withMediaAndMeta` with the success path, so a failing vision tool keeps its image
 * blocks instead of losing them (`__media__` is the vision channel: a failing files(view)
 * used to drop the picture on every provider).
 */
export function buildFailureContent(result, defaultMessage) {
  const rawError = result?.error;
  // Guard the type: a non-string `error` (some backends return an object) would produce an
  // invalid MCP text block instead of a readable message.
  const text = errorToText(rawError) || defaultMessage;

  return { content: withMediaAndMeta(text, result?.metadata), isError: true };
}

/**
 * A LOCAL tool (repo/shell) may attach a `metadata` object to its result. MCP has no
 * metadata field, so - exactly like the backend-proxied tool path - we re-emit it as a
 * parseable `__BRIDGE_META__` text block the bridge extracts for the frontend
 * (diff/gitStatus cards). The `metadata` key is stripped from the returned object so
 * only the MCP-standard {content,isError} shape goes out.
 */
export function withBridgeMeta(result) {
  if (result && result.metadata && Object.keys(result.metadata).length > 0) {
    const content = [...(result.content || []), { type: 'text', text: `${bridgeMetaMarker()}${JSON.stringify(result.metadata)}` }];
    const out = { ...result, content };
    delete out.metadata;
    return out;
  }
  return result;
}
