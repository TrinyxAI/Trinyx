package com.apimarketplace.catalog.service.generation;

import com.apimarketplace.catalog.service.execution.BinaryResponseHandler;
import com.apimarketplace.common.web.UrlSafetyValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns whatever a generation provider answered into a stored file.
 *
 * <p>Providers split into two camps and the descriptor says which: some return
 * the bytes ({@code assetPath: "$binary"}), which the catalog already stores on
 * the way through; the rest return a URL somewhere in a JSON body, and that URL
 * expires. This resolver fetches the second kind and puts it in storage under
 * the caller's tenant, so a generation the customer paid for survives the
 * provider's link TTL and can be handed to another node as an ordinary file.
 *
 * <p><b>Why fetch rather than pass the URL through.</b> A provider URL is
 * short-lived and unauthenticated. Handing it to an agent produces a reference
 * that works during the conversation and 404s the next day, for an asset that
 * was already billed. Storing it is what makes the purchase durable.
 */
@Slf4j
@Service
public class GenerationAssetResolver {

    /** Storage category, distinct from catalog-binary so generated assets are findable. */
    static final String GENERATED_ASSET_CATEGORY = "generated-asset";

    /**
     * {@code name}, {@code name[3]} or {@code name[*]} - one segment of a dotted
     * response path. The wildcard means "the first element that carries the rest
     * of the path", which is how a provider that returns its parts in a moving
     * order is read without guessing an index.
     */
    private static final Pattern SEGMENT = Pattern.compile("^([^\\[\\]]+)(?:\\[(\\d+|\\*)\\])?$");

    /** Index token meaning "first element that satisfies the remaining path". */
    private static final String WILDCARD = "*";

    /**
     * Redirect hops followed by hand.
     *
     * <p>The client is deliberately NOT allowed to follow them itself: only the
     * FIRST URL would then be checked, and a public host answering {@code 302
     * Location: http://169.254.169.254/...} would walk the fetch straight into
     * the cluster. Following them here means every hop goes through the same
     * safety check as the original URL.
     */
    static final int MAX_REDIRECTS = 5;

    private final BinaryResponseHandler binaryResponseHandler;
    private final HttpClient httpClient;
    private final long maxAssetBytes;

    public GenerationAssetResolver(BinaryResponseHandler binaryResponseHandler,
                                    @Value("${generation.asset.max-bytes:104857600}") long maxAssetBytes,
                                    @Value("${generation.asset.fetch-timeout-seconds:120}") long fetchTimeoutSeconds) {
        this.binaryResponseHandler = binaryResponseHandler;
        this.maxAssetBytes = maxAssetBytes;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.fetchTimeout = Duration.ofSeconds(fetchTimeoutSeconds);
    }

    private final Duration fetchTimeout;

    /**
     * Outcome of resolving an asset.
     *
     * @param fileRef  canonical FileRef map, empty when nothing was stored
     * @param error    why nothing was stored, null on success
     * @param assetUrl the URL the provider named for the asset, when the
     *                 descriptor found one. Carried on FAILURE on purpose: by
     *                 the time this resolver runs, the generation has been
     *                 produced and CHARGED, and the fetch can fail for reasons
     *                 that have nothing to do with the asset (a timeout, a
     *                 redirect budget, a 5xx from the provider's CDN, a body
     *                 over the storage cap). Dropping the URL there turns a
     *                 recoverable hiccup into a total loss: the customer paid,
     *                 the asset exists at a link that expires in minutes, and
     *                 nothing in the failure says where. Null when no URL was
     *                 ever read - a bytes-camp endpoint, or a response the path
     *                 did not match.
     */
    public record Resolved(Map<String, Object> fileRef, String error, String assetUrl) {

        public Resolved(Map<String, Object> fileRef, String error) {
            this(fileRef, error, null);
        }

        public boolean ok() {
            return error == null && fileRef != null && !fileRef.isEmpty();
        }

        static Resolved failed(String error) {
            return new Resolved(Map.of(), error, null);
        }

        /** Failed with the provider's own link still in hand. */
        static Resolved failed(String error, String assetUrl) {
            return new Resolved(Map.of(), error, assetUrl);
        }
    }

    /**
     * Find the asset the descriptor points at and make sure it is stored.
     *
     * @param spec     descriptor of the endpoint that ran
     * @param model    model that ran, used to name the stored file
     * @param response the catalog's projected response
     * @param tenantId owner the file is stored under
     */
    public Resolved resolve(GenerationSpec spec, GenerationSpec.Model model,
                             Map<String, Object> response, String tenantId) {
        if (response == null || response.isEmpty()) {
            return Resolved.failed("the provider returned an empty response");
        }

        // Bytes camp: the catalog stored the file on the way through, so the
        // response already carries a FileRef. Finding it is the whole job.
        if (spec.isBinaryAsset()) {
            return findExistingFileRef(response)
                    .map(ref -> new Resolved(ref, null))
                    .orElseGet(() -> Resolved.failed(
                            "the provider was expected to return the asset bytes but the response "
                                    + "carries no stored file"));
        }

        // Base64 camp: the asset is text at a declared path. Handled before the
        // URL camp because the value there is not a URL and fetching it would
        // be nonsense.
        if (spec.isBase64Asset()) {
            return resolveInlineBase64(spec.base64Path(), model, response, tenantId);
        }

        Optional<String> url = readPath(response, spec.assetPath());
        if (url.isEmpty()) {
            return Resolved.failed("no asset URL at '" + spec.assetPath() + "' in the provider response. "
                    + "If the endpoint only submits a job rather than finishing it, set its "
                    + "execution.mode to 'async_poll' so the catalog waits for the terminal response "
                    + "before the asset is read.");
        }
        return fetchAndStore(url.get(), model, tenantId);
    }

    // ── base64 in the body ──────────────────────────────────────────────────

    /**
     * Resolve an asset the provider inlined as base64 at a declared path.
     *
     * <p>Two shapes arrive here and both are normal. A large asset, which in
     * practice means any real image, has already been turned into a stored file
     * by the catalog's own dehydrator on the way out, so the path holds a
     * FileRef and the job is to recognise it: decoding again would store the
     * very same bytes a second time, under a second key, and bill the
     * customer's storage twice for one image. A small asset stayed under the
     * dehydrator's 64 KB threshold and is still text, so it is stored here,
     * under this class's own category rather than the catalog's binary one.
     * Handling only the first shape would leave the cheapest calls returning
     * their asset as a wall of base64.
     */
    private Resolved resolveInlineBase64(String path, GenerationSpec.Model model,
                                          Map<String, Object> response, String tenantId) {
        Optional<Object> found = readValue(response, path);
        if (found.isEmpty()) {
            return Resolved.failed("no asset at '" + path + "' in the provider response. "
                    + "If the endpoint only submits a job rather than finishing it, set its "
                    + "execution.mode to 'async_poll' so the catalog waits for the terminal "
                    + "response before the asset is read.");
        }

        Object raw = found.get();
        if (raw instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> candidate = (Map<String, Object>) map;
            if (isFileRef(candidate)) {
                return new Resolved(new LinkedHashMap<>(candidate), null);
            }
            return Resolved.failed("the value at '" + path + "' is an object rather than the "
                    + "base64 asset the descriptor expects");
        }

        byte[] bytes = decodeBase64(String.valueOf(raw));
        if (bytes == null) {
            return Resolved.failed("the value at '" + path + "' is not decodable base64");
        }
        if (bytes.length == 0) {
            return Resolved.failed("the base64 asset at '" + path + "' decodes to nothing");
        }
        if (bytes.length > maxAssetBytes) {
            return Resolved.failed("the generated asset exceeds the " + (maxAssetBytes / 1_048_576)
                    + " MB limit for a stored asset");
        }

        // Sniffed from the bytes rather than read from a sibling field: a
        // provider that names its own mime puts it at a path that differs per
        // provider, and one that disagrees with its payload would have the file
        // stored under a type it is not.
        String mimeType = BinaryResponseHandler.sniffMime(bytes);
        String fileName = model.id() + "_" + UUID.randomUUID().toString().substring(0, 8)
                + binaryResponseHandler.extensionForMime(mimeType);
        Map<String, Object> fileRef = binaryResponseHandler.storeBytes(
                tenantId, GENERATED_ASSET_CATEGORY, fileName, mimeType, bytes);
        return fileRef.isEmpty()
                ? Resolved.failed("the generated asset could not be stored")
                : new Resolved(fileRef, null);
    }

    /**
     * Text that is entirely base64, in either alphabet, wrapped or not.
     *
     * <p>Checked BEFORE decoding because the decoder cannot be asked. The MIME
     * decoder silently discards anything outside the alphabet, so it turns
     * {@code "I'm sorry, I can't help with that."} into eighteen bytes and
     * reports success: a provider that answers with a refusal where the
     * descriptor expects an image would have that sentence stored as a file and
     * handed back as a finished generation. Same reason the dehydrator gates on
     * the alphabet before it decodes.
     */
    private static final Pattern BASE64_TEXT = Pattern.compile("^[A-Za-z0-9+/\\-_]{4,}={0,2}$");

    /** Line breaks a provider wraps a long payload with. Spaces are NOT one. */
    private static final Pattern BASE64_WRAPPING = Pattern.compile("[\\r\\n\\t]");

    /**
     * Decode base64, tolerating a {@code data:} URL prefix, both alphabets and
     * embedded newlines. Returns null when the text is not base64 at all.
     */
    private static byte[] decodeBase64(String value) {
        String text = value.trim();
        if (text.isEmpty()) return null;
        int comma = text.startsWith("data:") ? text.indexOf(',') : -1;
        if (comma >= 0) {
            text = text.substring(comma + 1);
        }
        // Wrapping comes off first so a legitimately wrapped payload still
        // matches; a SPACE deliberately does not, because base64 never contains
        // one and a sentence always does.
        text = BASE64_WRAPPING.matcher(text).replaceAll("");
        if (!BASE64_TEXT.matcher(text).matches()) return null;
        // Base64 encodes three bytes as four characters, so a real payload is
        // always a multiple of four long. Without this, the alphabet check alone
        // still passes any single word: 'moderation_blocked', 'unavailable' and
        // 'Error1234' all decode to a handful of bytes and would be stored and
        // handed back as the asset the customer paid for. Providers that answer
        // with one word instead of an image are exactly the case this camp has
        // to survive.
        if (text.length() % 4 != 0) return null;
        try {
            // MIME decoder rather than the basic one: providers wrap long
            // payloads at 76 columns, which the basic decoder refuses outright.
            return Base64.getMimeDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            try {
                return Base64.getUrlDecoder().decode(text);
            } catch (IllegalArgumentException urlSafeFailure) {
                return null;
            }
        }
    }

    // ── fetching ────────────────────────────────────────────────────────────

    /**
     * Fetch and store, keeping the provider's link on every failure.
     *
     * <p>Everything below this line runs AFTER the provider produced the asset
     * and the platform charged for it, and every failure it can report is
     * transient in kind: a timeout, a redirect budget, a 5xx from the
     * provider's CDN, a body over the storage cap. The link the descriptor
     * found is then the customer's only remaining route to what they bought,
     * and it expires in minutes. It is attached ONCE here rather than at each
     * of the ten failure branches, so a branch added later cannot forget it.
     */
    private Resolved fetchAndStore(String url, GenerationSpec.Model model, String tenantId) {
        Resolved outcome = fetchAndStoreInner(url, model, tenantId);
        return outcome.ok() ? outcome : new Resolved(Map.of(), outcome.error(), url);
    }

    private Resolved fetchAndStoreInner(String url, GenerationSpec.Model model, String tenantId) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return Resolved.failed("the provider returned a malformed asset URL");
        }

        try {
            HttpResponse<InputStream> response = null;
            URI target = uri;
            for (int hop = 0; response == null; hop++) {
                Resolved refusal = refuseUnfetchable(target);
                if (refusal != null) {
                    return refusal;
                }
                HttpResponse<InputStream> hopResponse = httpClient.send(
                        HttpRequest.newBuilder(target).GET().timeout(fetchTimeout).build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                if (!isRedirect(hopResponse.statusCode())) {
                    response = hopResponse;
                    break;
                }
                // A redirect body is never the asset; drop it and re-check the
                // hop it points at, which is the whole reason redirects are not
                // delegated to the client.
                String location = hopResponse.headers().firstValue("location").orElse("").trim();
                hopResponse.body().close();
                if (hop >= MAX_REDIRECTS) {
                    return Resolved.failed("fetching the generated asset followed more than "
                            + MAX_REDIRECTS + " redirects");
                }
                if (location.isEmpty()) {
                    return Resolved.failed("fetching the generated asset returned HTTP "
                            + hopResponse.statusCode() + " without a redirect target");
                }
                try {
                    target = target.resolve(location);
                } catch (IllegalArgumentException e) {
                    return Resolved.failed("the generated asset URL redirected to a malformed URL");
                }
            }

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Resolved.failed("fetching the generated asset returned HTTP " + response.statusCode());
            }

            byte[] bytes = readCapped(response.body());
            if (bytes == null) {
                return Resolved.failed("the generated asset exceeds the " + (maxAssetBytes / 1_048_576)
                        + " MB limit for a stored asset");
            }
            if (bytes.length == 0) {
                return Resolved.failed("the generated asset URL returned an empty body");
            }

            String mimeType = response.headers().firstValue("content-type")
                    .map(v -> v.split(";")[0].trim())
                    .filter(v -> !v.isEmpty())
                    .orElse("application/octet-stream");
            String fileName = model.id() + "_" + UUID.randomUUID().toString().substring(0, 8)
                    + binaryResponseHandler.extensionForMime(mimeType);

            Map<String, Object> fileRef = binaryResponseHandler.storeBytes(
                    tenantId, GENERATED_ASSET_CATEGORY, fileName, mimeType, bytes);
            if (fileRef.isEmpty()) {
                return Resolved.failed("the generated asset could not be stored");
            }
            return new Resolved(fileRef, null);
        } catch (java.io.IOException e) {
            return Resolved.failed("fetching the generated asset failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Resolved.failed("fetching the generated asset was interrupted");
        }
    }

    /** Status codes that name another URL to fetch instead of carrying a body. */
    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /**
     * Refuse a URL this service must not fetch, or return null to allow it.
     *
     * <p>Applied to EVERY hop, not only to the URL the provider answered with.
     * The bytes fetched here are stored and handed back to the caller, so an
     * unchecked URL is a read primitive from inside the cluster: a provider
     * response (or a hand-edited {@code assetPath} pointing at a field the
     * provider echoes) naming {@code http://10.42.0.5:8080/...} or the cloud
     * metadata address would be delivered as a downloadable file.
     */
    private Resolved refuseUnfetchable(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            // Refusing anything else keeps a descriptor or a compromised provider
            // from turning this fetch into a file:// or gopher:// read.
            return Resolved.failed("asset URL scheme '" + scheme + "' is not allowed; expected http or https");
        }
        try {
            // Same guard the catalog's own outbound calls use: private, loopback,
            // link-local and localhost targets are refused after DNS resolution.
            UrlSafetyValidator.validateUrl(uri.toString());
        } catch (IllegalArgumentException e) {
            return Resolved.failed("the generated asset URL is not fetchable: " + e.getMessage());
        }
        return null;
    }

    /**
     * Read at most {@link #maxAssetBytes}, returning null when the stream is
     * bigger. Streamed rather than {@code readAllBytes} so an oversized asset is
     * refused after one buffer instead of after it has already filled the heap.
     */
    private byte[] readCapped(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxAssetBytes) {
                return null;
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    // ── response navigation ─────────────────────────────────────────────────

    /**
     * Read a string at a dotted, optionally indexed path. Tolerates the
     * catalog's {@code result} envelope so a descriptor written against the
     * provider's own documentation keeps working.
     */
    static Optional<String> readPath(Map<String, Object> response, String path) {
        return readValue(response, path)
                .map(value -> String.valueOf(value).trim())
                .filter(value -> !value.isEmpty());
    }

    /**
     * Read the raw value at a dotted path, tolerating the catalog's
     * {@code result} envelope. Kept separate from {@link #readPath} because the
     * base64 camp needs the value as it stands: by the time it runs the leaf is
     * usually a FileRef Map, and stringifying it would turn a stored file into
     * the text {@code {_type=file, path=...}}.
     */
    static Optional<Object> readValue(Map<String, Object> response, String path) {
        String[] segments = path.split("\\.");
        Optional<Object> direct = walk(response, segments, 0);
        if (direct.isPresent()) return direct;
        Object wrapped = response.get("result");
        if (wrapped instanceof Map<?, ?> m) {
            return walk(m, segments, 0);
        }
        return Optional.empty();
    }

    /**
     * Walk one segment and recurse. Recursive rather than iterative because the
     * {@code [*]} wildcard has to be able to try an element, fail on the REST of
     * the path, and move on to the next one; a single cursor cannot back out of
     * a wrong branch.
     */
    private static Optional<Object> walk(Object cursor, String[] segments, int depth) {
        if (depth == segments.length) {
            return cursor == null ? Optional.empty() : Optional.of(cursor);
        }
        Matcher m = SEGMENT.matcher(segments[depth]);
        if (!m.matches()) return Optional.empty();

        if (!(cursor instanceof Map)) return Optional.empty();
        Object next = ((Map<?, ?>) cursor).get(m.group(1));
        if (next == null) return Optional.empty();

        String index = m.group(2);
        if (index == null) {
            return walk(next, segments, depth + 1);
        }
        if (!(next instanceof List<?> list)) return Optional.empty();
        if (WILDCARD.equals(index)) {
            for (Object element : list) {
                Optional<Object> found = walk(element, segments, depth + 1);
                // An element whose leaf is there but EMPTY does not end the
                // search. A provider that pads its parts with blank fields would
                // otherwise stop the walk on the first one and report no asset,
                // for a generation sitting in the very next element.
                if (found.isPresent() && !isBlankScalar(found.get())) return found;
            }
            return Optional.empty();
        }
        int position = Integer.parseInt(index);
        return position >= list.size() ? Optional.empty() : walk(list.get(position), segments, depth + 1);
    }

    /** Text that is present but says nothing, which is not an answer. */
    private static boolean isBlankScalar(Object value) {
        return value instanceof CharSequence text && text.toString().trim().isEmpty();
    }

    /**
     * A copy of the response with the value at {@code path} removed, or empty
     * when the path is not there.
     *
     * <p><b>Copy-on-write along the path, never mutation.</b> The map handed in
     * is the same object the catalog put in its response cache, so removing a
     * key in place would empty the asset out of every later cache hit as well.
     * Only the containers ON the path are rebuilt; everything else is shared.
     */
    public static Optional<Map<String, Object>> withoutPath(Map<String, Object> response, String path) {
        String[] segments = path.split("\\.");
        Optional<Object> direct = prune(response, segments, 0);
        if (direct.isPresent()) {
            return castToMap(direct.get());
        }
        // Same envelope tolerance as the read side, so a descriptor written
        // against the provider's own documentation prunes what it found.
        Object wrapped = response.get("result");
        if (wrapped instanceof Map<?, ?> inner) {
            Optional<Object> nested = prune(inner, segments, 0);
            if (nested.isPresent()) {
                Map<String, Object> copy = new LinkedHashMap<>(response);
                copy.put("result", nested.get());
                return Optional.of(copy);
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static Optional<Map<String, Object>> castToMap(Object value) {
        return value instanceof Map<?, ?> m
                ? Optional.of((Map<String, Object>) m)
                : Optional.empty();
    }

    /**
     * Rebuild {@code cursor} without the value the remaining segments name.
     * Empty when the path does not resolve, so the caller can leave the
     * original untouched rather than hand back a copy that changed nothing.
     */
    private static Optional<Object> prune(Object cursor, String[] segments, int depth) {
        if (!(cursor instanceof Map<?, ?> map)) return Optional.empty();
        Matcher m = SEGMENT.matcher(segments[depth]);
        if (!m.matches()) return Optional.empty();

        String key = m.group(1);
        Object next = map.get(key);
        if (next == null) return Optional.empty();
        String index = m.group(2);
        boolean last = depth == segments.length - 1;

        if (index == null) {
            if (last) {
                Map<String, Object> copy = new LinkedHashMap<>(castToMap(map).orElseThrow());
                copy.remove(key);
                return Optional.of(copy);
            }
            return prune(next, segments, depth + 1).map(child -> {
                Map<String, Object> copy = new LinkedHashMap<>(castToMap(map).orElseThrow());
                copy.put(key, child);
                return copy;
            });
        }

        // An indexed LAST segment would name a list element rather than a field,
        // which no asset path does: both shipped camps end on a named leaf.
        // Refusing it keeps this walker from inventing a meaning for a shape it
        // has never been asked to prune.
        if (last || !(next instanceof List<?> list)) return Optional.empty();

        List<Object> rebuilt = new java.util.ArrayList<>(list);
        boolean wildcard = WILDCARD.equals(index);
        int from = wildcard ? 0 : Integer.parseInt(index);
        if (!wildcard && from >= list.size()) return Optional.empty();
        int to = wildcard ? list.size() : from + 1;

        for (int i = from; i < to; i++) {
            Object element = list.get(i);
            // The element the READER would have taken, and only that one. The
            // two walkers have to agree: `walk` steps over an element whose leaf
            // is present but blank, so pruning the first one that merely HAS the
            // leaf would empty a decoy and leave the real payload in the reply.
            if (wildcard && walk(element, segments, depth + 1)
                    .filter(found -> !isBlankScalar(found)).isEmpty()) {
                continue;
            }
            Optional<Object> child = prune(element, segments, depth + 1);
            if (child.isPresent()) {
                rebuilt.set(i, child.get());
                Map<String, Object> copy = new LinkedHashMap<>(castToMap(map).orElseThrow());
                copy.put(key, rebuilt);
                return Optional.of(copy);
            }
        }
        return Optional.empty();
    }

    /**
     * Find a FileRef the catalog already stored. Looks at the top level and one
     * level down, which covers both the projected shape and the {@code result}
     * envelope; a FileRef is recognised by carrying a {@code path}, the field
     * every downstream consumer actually needs.
     */
    @SuppressWarnings("unchecked")
    static Optional<Map<String, Object>> findExistingFileRef(Map<String, Object> response) {
        Optional<Map<String, Object>> here = scanForFileRef(response);
        if (here.isPresent()) return here;
        for (Object value : response.values()) {
            if (value instanceof Map<?, ?> m) {
                Optional<Map<String, Object>> nested = scanForFileRef((Map<String, Object>) m);
                if (nested.isPresent()) return nested;
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static Optional<Map<String, Object>> scanForFileRef(Map<String, Object> node) {
        if (isFileRef(node)) return Optional.of(new LinkedHashMap<>(node));
        for (Object value : node.values()) {
            if (value instanceof Map<?, ?> m && isFileRef((Map<String, Object>) m)) {
                return Optional.of(new LinkedHashMap<>((Map<String, Object>) m));
            }
        }
        return Optional.empty();
    }

    private static boolean isFileRef(Map<String, Object> node) {
        Object path = node.get("path");
        return path instanceof String s && !s.isBlank();
    }
}
