package com.apimarketplace.publication.service;

import com.apimarketplace.common.storage.signing.ShowcaseUrlSigner;
import com.apimarketplace.common.storage.url.FileProxyUrls;
import com.apimarketplace.common.storage.url.StorageKeys;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks the {@code items[*].data} subtree of a showcase render payload and
 * replaces every FileRef object ({@code {_type:'file', path, name, mimeType,
 * size}}) with a short-lived HMAC-signed URL pointing to the storage-service
 * {@code /api/files/proxy-signed} endpoint. A file reference that arrived as a
 * URL STRING instead of a map (see {@link com.apimarketplace.common.storage.url.FileProxyUrls})
 * is re-signed the same way, so its expiry is always the current read's. Anonymous marketplace visitors
 * thus see {@code <img src="/api/files/proxy-signed?key=…&exp=…&sig=…">} and
 * the gateway lets the request through (path is in {@code PUBLIC_ENDPOINTS});
 * storage-service then verifies the HMAC and streams the file.
 *
 * <p>Why HMAC and not S3 presigning: the MinIO presigner emits URLs that
 * embed the configured S3 endpoint (an internal address such as {@code http://minio:9000}),
 * which is on the internal VPN and unreachable from a browser. A signed proxy
 * URL goes through Caddy → gateway → storage-service like any other request.
 *
 * <p>Scope is intentionally narrow: only walks {@code items[*].data}, not
 * {@code items[*].triggerData} (which can carry FileRefs uploaded by an
 * acquirer of a different tenant - never expose those publicly) and not the
 * snapshot's {@code runState} subtree (steps' {@code output} maps may carry
 * paths that are not part of the publicly rendered interface).
 *
 * <p>The rewriter refuses to sign keys that don't begin with
 * {@code pub.publisherId + "/"} (defense in depth: even a corrupted snapshot
 * with a foreign-tenant path won't leak). Failures to sign leave the FileRef
 * untouched (rendered as JSON, broken image) rather than aborting the whole
 * render.
 */
@Service
public class ShowcaseFileRefRewriter {

    private static final Logger log = LoggerFactory.getLogger(ShowcaseFileRefRewriter.class);
    private static final String SIGNED_DISPOSITION = "inline";

    private final ShowcaseUrlSigner signer;
    private final PublicationFileUrlResolver urlResolver;
    private final ObjectMapper objectMapper;
    private final int presignExpiryMinutes;
    private final Counter presignOkCounter;
    private final Counter presignFailCounter;
    private final Counter presignSkippedForeignKeyCounter;

    // Default expiry is 4 hours (240 minutes) - long enough to cover a typical
    // marketplace browsing session (the previous 15 min default broke images
    // whenever a viewer left the tab open for more than a quarter-hour) while
    // still expiring URLs so a leaked link does not grant indefinite access.
    // The HMAC is bound to the exact key + expiry; leakage of any single signed
    // URL is per-file and time-boxed. Operators can override via
    // `publication.showcase.presign-expiry-minutes` if a stricter or looser
    // window is required.
    public ShowcaseFileRefRewriter(
            ShowcaseUrlSigner signer,
            PublicationFileUrlResolver urlResolver,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${publication.showcase.presign-expiry-minutes:240}") int presignExpiryMinutes) {
        this.signer = signer;
        this.urlResolver = urlResolver;
        this.objectMapper = objectMapper;
        this.presignExpiryMinutes = presignExpiryMinutes;
        this.presignOkCounter = Counter.builder("publication_showcase_presign_total")
                .description("Showcase FileRef → signed URL conversions, by outcome")
                .tag("status", "ok")
                .register(meterRegistry);
        this.presignFailCounter = Counter.builder("publication_showcase_presign_total")
                .description("Showcase FileRef → signed URL conversions, by outcome")
                .tag("status", "fail")
                .register(meterRegistry);
        this.presignSkippedForeignKeyCounter = Counter.builder("publication_showcase_presign_total")
                .description("Showcase FileRef → signed URL conversions, by outcome")
                .tag("status", "skipped_foreign_key")
                .register(meterRegistry);
    }

    /**
     * Rewrite FileRefs in {@code items[*].data} of the given render payload.
     * Returns a new {@code items} list with rewritten data maps; the input is
     * not mutated.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> rewriteItems(List<Map<String, Object>> items,
                                                    WorkflowPublicationEntity pub) {
        if (items == null || items.isEmpty()) return items;
        if (!signer.isEnabled()) {
            // No HMAC secret in this environment → leave FileRefs raw. The
            // signer already logs a WARN at startup; the marketplace card just
            // won't render images until the operator wires the secret.
            return items;
        }
        String publisherId = pub.getPublisherId();
        if (publisherId == null || publisherId.isEmpty()) {
            log.warn("[FileRefRewriter] publication {} has no publisherId, skipping rewrite", pub.getId());
            return items;
        }
        String publicationNamespace = "_publications/" + pub.getId() + "/";
        List<Map<String, Object>> rewritten = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            if (item == null) {
                rewritten.add(null);
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>(item);
            Object data = copy.get("data");
            if (data instanceof Map<?, ?> dataMap) {
                copy.put("data", rewriteValue(dataMap, publisherId, publicationNamespace));
            }
            rewritten.add(copy);
        }
        return rewritten;
    }

    /**
     * Rewrite FileRefs in a single landing-snapshot payload (returned by
     * {@code GET /by-id/{pubId}/landing-snapshot} for INTERFACE/AGENT/WORKFLOW
     * publications). The landing map carries htmlTemplate / cssTemplate /
     * jsTemplate as strings (no FileRefs) and a {@code data} sub-map that CAN
     * carry FileRefs the interface template references via variable_mapping.
     * Without this rewrite, anonymous marketplace visitors see raw FileRef Maps
     * serialized as JSON inside {@code <img src=...>}, producing broken images.
     *
     * <p>Returns a new map with FileRefs in {@code data} replaced by
     * HMAC-signed URLs; non-FileRef fields are preserved.
     */
    public Map<String, Object> rewriteLanding(Map<String, Object> landing,
                                               WorkflowPublicationEntity pub) {
        if (landing == null || landing.isEmpty()) return landing;
        // Before every early return below: an internal key must not survive a path that
        // merely declined to rewrite the payload.
        if (!signer.isEnabled()) return LandingInterfaceSnapshotter.withoutInternalKeys(landing);
        String publisherId = pub.getPublisherId();
        if (publisherId == null || publisherId.isEmpty()) {
            log.warn("[FileRefRewriter] publication {} has no publisherId, skipping landing rewrite", pub.getId());
            return LandingInterfaceSnapshotter.withoutInternalKeys(landing);
        }
        String publicationNamespace = "_publications/" + pub.getId() + "/";
        Map<String, Object> rewritten = LandingInterfaceSnapshotter.withoutInternalKeys(landing);
        // Same scope decision as rewriteItems: only `data` is walked. Templates
        // are strings, actionMappings carry no FileRefs.
        Object data = rewritten.get("data");
        if (data != null) {
            rewritten.put("data", rewriteValue(data, publisherId, publicationNamespace));
        }
        return rewritten;
    }

    /**
     * Sign replacement image S3 keys into HMAC-signed proxy URLs.
     * Used by ShowcaseSnapshotReader to apply AI image replacements at render time.
     */
    public Map<String, String> signReplacementUrls(Map<String, String> replacements,
                                                     WorkflowPublicationEntity pub) {
        if (replacements == null || replacements.isEmpty()) return Map.of();
        if (!signer.isEnabled()) return Map.of();
        String publisherId = pub.getPublisherId();
        if (publisherId == null || publisherId.isEmpty()) return Map.of();
        String publicationNamespace = "_publications/" + pub.getId() + "/";
        Map<String, String> signed = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            String url = mintSignedUrl(publisherId, publicationNamespace, entry.getValue());
            if (url != null) {
                signed.put(entry.getKey(), url);
            }
        }
        return signed;
    }

    /**
     * Rewrite the {@code stepFiles} section - {@code {"<epoch>": {"<stepAlias>": FileRef}}} -
     * into what an anonymous visitor's canvas can actually render:
     * {@code {"<epoch>": {"<stepAlias>": {name, mimeType, size, url}}}}.
     *
     * <p>Unlike {@link #rewriteItems}, a FileRef here does NOT collapse to a bare URL string.
     * The node file strip is a labelled pill, not an {@code <img>}: it shows the file's name and
     * human size before anything is expanded, and those fields only exist on the ref. So the
     * display fields survive and {@code path} is replaced by a signed {@code url}.
     *
     * <p>{@code path} and {@code id} are dropped, but not as a secrecy measure: the signed URL
     * carries the storage key verbatim in {@code ?key=}, exactly as {@link #rewriteItems} has
     * always done, so the path is visible either way. What actually protects the file is that
     * the HMAC binds key and expiry together - a visitor cannot swap in another key, and the
     * one they hold stops working. They are dropped because they are dead weight on this
     * surface: the pill's only way to the bytes is the URL, and a storage id here would invite
     * a caller to try an authenticated by-id route it has no token for.
     *
     * <p>An entry whose URL cannot be minted is DROPPED rather than served without one. Signing
     * fails when the signer has no secret in this environment, or when the key belongs to
     * neither the publisher nor this publication (see {@link #mintSignedUrl}) - in both cases
     * the visitor could not open the file, and a pill naming a file that will never open is
     * worse than no pill.
     *
     * @return a new map; the snapshot's backing maps are never mutated
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> rewriteStepFiles(Map<String, Object> stepFiles,
                                                 WorkflowPublicationEntity pub) {
        if (stepFiles == null || stepFiles.isEmpty()) return Map.of();
        if (!signer.isEnabled()) {
            // Same contract as rewriteItems: no secret, no signed URLs. The startup WARN
            // already says why; here it degrades to a canvas with no file pills.
            return Map.of();
        }
        String publisherId = pub.getPublisherId();
        if (publisherId == null || publisherId.isEmpty()) {
            log.warn("[FileRefRewriter] publication {} has no publisherId, skipping stepFiles rewrite", pub.getId());
            return Map.of();
        }
        String publicationNamespace = "_publications/" + pub.getId() + "/";

        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> epochEntry : stepFiles.entrySet()) {
            if (!(epochEntry.getValue() instanceof Map<?, ?> perAliasRaw)) continue;
            Map<String, Object> perAlias = (Map<String, Object>) perAliasRaw;
            Map<String, Object> rewrittenAliases = new LinkedHashMap<>();
            for (Map.Entry<String, Object> aliasEntry : perAlias.entrySet()) {
                if (!(aliasEntry.getValue() instanceof Map<?, ?> refRaw)) continue;
                Map<String, Object> ref = (Map<String, Object>) refRaw;
                if (!isFileRef(ref)) continue;
                String url = mintSignedUrl(publisherId, publicationNamespace, String.valueOf(ref.get("path")));
                if (url == null) continue;
                Map<String, Object> pill = new LinkedHashMap<>();
                pill.put("name", ref.get("name"));
                pill.put("mimeType", ref.get("mimeType"));
                pill.put("size", ref.get("size"));
                pill.put("url", url);
                rewrittenAliases.put(aliasEntry.getKey(), pill);
            }
            if (!rewrittenAliases.isEmpty()) {
                out.put(epochEntry.getKey(), rewrittenAliases);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Object rewriteValue(Object value, String publisherId, String publicationNamespace) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> m = (Map<String, Object>) map;
            if (isFileRef(m)) {
                String key = String.valueOf(m.get("path"));
                String url = mintSignedUrl(publisherId, publicationNamespace, key);
                if (url != null) return url;
                return m; // leave raw FileRef on failure (better than null)
            }
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : m.entrySet()) {
                result.put(e.getKey(), rewriteValue(e.getValue(), publisherId, publicationNamespace));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) out.add(rewriteValue(item, publisherId, publicationNamespace));
            return out;
        }
        // A file reference that reached the snapshot as a URL STRING rather than a
        // FileRef map: `/api/files/proxy?key=…` (authenticated, unusable by an
        // anonymous visitor) or `/api/files/proxy-signed?key=…&exp=…&sig=…` minted
        // by the `core:public_link` node (absolute, and carrying the expiry it was
        // born with). Re-mint it here so the URL a visitor gets is always signed
        // for THIS read - the whole point of signing at render time.
        //
        // Without this, a snapshot published before the publish-time normalisation
        // learned these shapes keeps serving its frozen `exp` and every media on
        // the marketplace page 403s once that expiry passes, permanently: nothing
        // downstream ever looks at a String, so nothing can refresh it. Re-minting
        // goes through the same key-ownership guard as a FileRef, and is a no-op
        // for any string that is not entirely such a URL.
        if (value instanceof String str) {
            String proxiedKey = urlResolver.trustedStorageKeyOf(str, publisherId, publicationNamespace);
            if (proxiedKey != null) {
                String fresh = mintSignedUrl(publisherId, publicationNamespace, proxiedKey);
                return fresh != null ? fresh : value;
            }
        }
        // JSON-encoded string that may carry FileRef maps or proxy URLs (e.g. postsJson
        // from js_template). Parse, rewrite what is inside, re-serialize to preserve the
        // String type for template compat.
        if (value instanceof String s && (s.startsWith("[") || s.startsWith("{"))
                && (s.contains("\"_type\":\"file\"") || s.contains(FileProxyUrls.PATH_MARKER))) {
            try {
                Object parsed = objectMapper.readValue(s, Object.class);
                Object rewritten = rewriteValue(parsed, publisherId, publicationNamespace);
                return objectMapper.writeValueAsString(rewritten);
            } catch (Exception e) {
                // Not valid JSON - leave as-is
                return value;
            }
        }
        return value;
    }

    private static boolean isFileRef(Map<String, Object> m) {
        return "file".equals(m.get("_type"))
                && m.get("path") instanceof String s
                && !s.isEmpty();
    }

    private String mintSignedUrl(String publisherId, String publicationNamespace, String key) {
        // Defense in depth: refuse to sign keys that don't belong either to the
        // publisher's tenant (legacy items that were never namespace-copied) OR
        // to this publication's `_publications/{pubId}/` namespace (the target of
        // `WorkflowPublicationService.copyFileRefsInRunState`). Protects against
        // a corrupted snapshot leaking another tenant's or another publication's
        // file via the marketplace HMAC channel.
        // Shape first: a prefix test is only a boundary while the suffix cannot walk out of
        // it, and a path the copy pass REFUSED is deliberately left in the snapshot pointing
        // at its origin - so `8/../3/private.pdf` reaches here, and the signature is the whole
        // authorization at the other end (proxySignedDownload does not re-check the tenant).
        if (!StorageKeys.isWellFormed(key)) {
            presignSkippedForeignKeyCounter.increment();
            log.warn("[FileRefRewriter] refusing to sign a malformed storage key: {}", key);
            return null;
        }
        boolean publisherOwned = key.startsWith(publisherId + "/");
        boolean publicationOwned = key.startsWith(publicationNamespace);
        if (!publisherOwned && !publicationOwned) {
            presignSkippedForeignKeyCounter.increment();
            log.warn("[FileRefRewriter] refusing to sign foreign key: publisher={} pubNs={} key={}",
                    publisherId, publicationNamespace, key);
            return null;
        }
        long exp = Instant.now().getEpochSecond() + (long) presignExpiryMinutes * 60L;
        String sig = signer.sign(key, exp, SIGNED_DISPOSITION);
        if (sig == null) {
            presignFailCounter.increment();
            return null;
        }
        presignOkCounter.increment();
        return "/api/files/proxy-signed"
                + "?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&exp=" + exp
                + "&disposition=" + SIGNED_DISPOSITION
                + "&sig=" + sig;
    }
}
