package com.apimarketplace.publication.service;

import com.apimarketplace.common.storage.signing.ShowcaseUrlSigner;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ShowcaseFileRefRewriter")
class ShowcaseFileRefRewriterTest {

    private static final String SECRET = "test-secret-32-bytes-long-enough-for-hmac";

    private ShowcaseUrlSigner signer;
    private SimpleMeterRegistry meterRegistry;
    private ShowcaseFileRefRewriter rewriter;
    private WorkflowPublicationEntity pub;

    @BeforeEach
    void setUp() {
        signer = new ShowcaseUrlSigner(SECRET);
        meterRegistry = new SimpleMeterRegistry();
        rewriter = new ShowcaseFileRefRewriter(signer, new PublicationFileUrlResolver(signer), new com.fasterxml.jackson.databind.ObjectMapper(), meterRegistry, 15);
        pub = new WorkflowPublicationEntity();
        pub.setId(UUID.randomUUID());
        pub.setPublisherId("1");
    }

    @Test
    @DisplayName("Replaces a top-level FileRef in items[*].data with a /api/files/proxy-signed URL - regression for marketplace card showing <img src='{\"_type\":\"file\"...}'>")
    void replacesTopLevelFileRefWithSignedUrl() {
        Map<String, Object> fileRef = Map.of(
                "_type", "file",
                "path", "1/general/catalog-binary/abc.png",
                "name", "abc.png",
                "mimeType", "image/png",
                "size", 1234);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("openai_image", fileRef);
        data.put("prompt", "A sunset");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("data", data);

        List<Map<String, Object>> out = rewriter.rewriteItems(List.of(item), pub);

        Map<String, Object> outData = (Map<String, Object>) out.get(0).get("data");
        String url = (String) outData.get("openai_image");
        assertThat(url).startsWith("/api/files/proxy-signed?");
        assertThat(url).contains("key=1%2Fgeneral%2Fcatalog-binary%2Fabc.png");
        assertThat(url).contains("disposition=inline");
        assertThat(url).contains("sig=");
        assertThat(url).contains("exp=");
        // Non-FileRef fields untouched.
        assertThat(outData.get("prompt")).isEqualTo("A sunset");
    }

    @Test
    @DisplayName("URL signature round-trips through ShowcaseUrlSigner.verify - sign-side and verify-side use the same canonicalisation")
    void signatureRoundTripsThroughVerify() {
        Map<String, Object> fileRef = Map.of(
                "_type", "file", "path", "1/x.png",
                "name", "x.png", "mimeType", "image/png", "size", 1);
        List<Map<String, Object>> out = rewriter.rewriteItems(
                List.of(Map.of("data", Map.of("img", fileRef))), pub);
        String url = (String) ((Map<String, Object>) out.get(0).get("data")).get("img");

        // Parse URL and verify the signature using the SAME signer instance -
        // proves both sides agree on canonicalisation.
        java.util.Map<String, String> q = parseQuery(url);
        boolean ok = signer.verify(
                java.net.URLDecoder.decode(q.get("key"), java.nio.charset.StandardCharsets.UTF_8),
                Long.parseLong(q.get("exp")),
                q.get("disposition"),
                q.get("sig"),
                java.time.Instant.now().getEpochSecond());
        assertThat(ok).isTrue();
    }

    @Test
    @DisplayName("Recurses into nested maps and lists so deeply-nested FileRefs are also rewritten")
    void recursesIntoNestedStructures() {
        Map<String, Object> nestedFileRef = Map.of(
                "_type", "file", "path", "1/path/inner.png",
                "name", "i.png", "mimeType", "image/png", "size", 1);
        Map<String, Object> data = Map.of(
                "gallery", List.of(nestedFileRef),
                "wrapper", Map.of("inner", nestedFileRef));
        Map<String, Object> item = Map.of("data", data);

        List<Map<String, Object>> out = rewriter.rewriteItems(List.of(item), pub);

        Map<String, Object> outData = (Map<String, Object>) out.get(0).get("data");
        List<?> gallery = (List<?>) outData.get("gallery");
        assertThat(gallery.get(0)).asString().startsWith("/api/files/proxy-signed?");
        Map<String, Object> wrapper = (Map<String, Object>) outData.get("wrapper");
        assertThat(wrapper.get("inner")).asString().startsWith("/api/files/proxy-signed?");
    }

    @Test
    @DisplayName("Skips triggerData even when it embeds FileRefs - anti-leak guard for fields uploaded by acquirers in another tenant")
    void doesNotRewriteTriggerData() {
        Map<String, Object> fileRef = Map.of(
                "_type", "file", "path", "99/uploaded/by-acquirer.bin",
                "name", "x.bin", "mimeType", "application/octet-stream", "size", 1);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("data", Map.of("safe", "value"));
        item.put("triggerData", Map.of("trigger:form",
                Map.of("uploaded_file", fileRef)));

        List<Map<String, Object>> out = rewriter.rewriteItems(List.of(item), pub);

        Map<String, Object> outTrigger = (Map<String, Object>) out.get(0).get("triggerData");
        Map<String, Object> outForm = (Map<String, Object>) outTrigger.get("trigger:form");
        // Original FileRef object preserved (still a Map, not a String URL)
        assertThat(outForm.get("uploaded_file")).isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("Refuses to sign keys that don't begin with publisherId + '/' - defense in depth, prevents a corrupted snapshot from leaking another tenant's files")
    void refusesCrossTenantKey() {
        Map<String, Object> foreignFileRef = Map.of(
                "_type", "file", "path", "99/foreign-tenant.png",
                "name", "foreign.png", "mimeType", "image/png", "size", 1);
        Map<String, Object> item = Map.of("data", Map.of("img", foreignFileRef));

        List<Map<String, Object>> out = rewriter.rewriteItems(List.of(item), pub);

        // Original FileRef preserved (downstream renders broken image).
        Map<String, Object> outData = (Map<String, Object>) out.get(0).get("data");
        assertThat(outData.get("img")).isInstanceOf(Map.class);
        // Counter increments under skipped_foreign_key tag.
        assertThat(meterRegistry.counter("publication_showcase_presign_total",
                "status", "skipped_foreign_key").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Increments presign_total{status=ok} on success - gives ops a signal when the rewrite is healthy")
    void emitsOkMetricsOnSuccess() {
        Map<String, Object> fileRef = Map.of("_type", "file", "path", "1/ok.png",
                "name", "ok.png", "mimeType", "image/png", "size", 1);
        rewriter.rewriteItems(List.of(Map.of("data", Map.of("a", fileRef))), pub);

        assertThat(meterRegistry.counter("publication_showcase_presign_total", "status", "ok").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("Disabled signer (no HMAC secret) returns items as-is so the anonymous render at least doesn't 500")
    void skipsWhenSignerDisabled() {
        ShowcaseUrlSigner disabled = new ShowcaseUrlSigner(null);
        ShowcaseFileRefRewriter r = new ShowcaseFileRefRewriter(disabled, new PublicationFileUrlResolver(disabled), new com.fasterxml.jackson.databind.ObjectMapper(), new SimpleMeterRegistry(), 15);
        Map<String, Object> fileRef = Map.of("_type", "file", "path", "1/x.png",
                "name", "x.png", "mimeType", "image/png", "size", 1);
        List<Map<String, Object>> items = List.of(Map.of("data", Map.of("img", fileRef)));

        List<Map<String, Object>> out = r.rewriteItems(items, pub);

        // Same reference returned (no mutation, no rewrite, no exception).
        assertThat(out).isSameAs(items);
    }

    @Test
    @DisplayName("No publisherId → skip rewrite entirely so we never accidentally sign a URL with empty/null tenant")
    void skipsWhenPublisherIdMissing() {
        pub.setPublisherId(null);
        Map<String, Object> fileRef = Map.of("_type", "file", "path", "1/x.png",
                "name", "x.png", "mimeType", "image/png", "size", 1);
        List<Map<String, Object>> items = List.of(Map.of("data", Map.of("img", fileRef)));

        List<Map<String, Object>> out = rewriter.rewriteItems(items, pub);

        assertThat(out).isSameAs(items);
    }

    @Test
    @DisplayName("Empty input list returns empty without invoking the signer")
    void emptyInputNoOps() {
        List<Map<String, Object>> out = rewriter.rewriteItems(List.of(), pub);
        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("legacy flat-shape Map (file_url/file_name/...) keeps its shape - the strict _type=='file' probe rejects the MAP, while the flattened reference inside file_url is re-signed like any other")
    void doesNotSignLegacyFlatFileShape() {
        // Pre-PR2 producer nodes emitted {file_url, file_name, file_size, content_type}.
        // PR2 removed that shape from the 4 producers but historical workflow_runs.state_snapshot
        // JSONB rows still carry it. The strict `_type=='file'` probe keeps the MAP itself intact:
        // it must never be replaced by a URL string, or a template reading `img.file_name` breaks.
        //
        // Its `file_url` VALUE is a different matter: it is a file reference flattened into a
        // string, and the authenticated `/api/files/proxy` shape is unusable by the anonymous
        // visitor this rewriter serves. It is re-signed like any other such string - which is
        // also what the publish path has always produced for a fresh snapshot, where
        // normalizeProxyUrlsInMap turns that value into a FileRef before this rewriter signs it.
        Map<String, Object> legacyMap = new LinkedHashMap<>();
        legacyMap.put("file_url", "/api/files/proxy?key=1/legacy.png");
        legacyMap.put("file_name", "legacy.png");
        legacyMap.put("file_size", 12345);
        legacyMap.put("content_type", "image/png");

        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("data", new LinkedHashMap<>(Map.of("img", legacyMap)));
        List<Map<String, Object>> items = List.of(wrapped);

        List<Map<String, Object>> out = rewriter.rewriteItems(items, pub);

        // The legacy Map keeps its shape - only the flattened reference inside it changes.
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) out.get(0).get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> img = (Map<String, Object>) data.get("img");
        assertThat(img).doesNotContainKey("_type");
        assertThat(img).doesNotContainKey("path");
        assertThat(img).containsEntry("file_name", "legacy.png");
        assertThat(img).containsEntry("file_size", 12345);
        // The flattened reference inside is re-signed so an anonymous visitor can fetch it.
        assertThat((String) img.get("file_url")).startsWith("/api/files/proxy-signed?key=1%2Flegacy.png");
        // Exactly one sign: the `file_url` value. The Map itself is not a FileRef and
        // contributes nothing. (The meter name here was wrong before - it matched no
        // counter, so the old assertion held whatever the code did.)
        assertThat(meterRegistry.find("publication_showcase_presign_total")
                .tag("status", "ok").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("PR2 round-6: signs FileRefs whose path is in `_publications/{pubId}/` namespace (post copyFileRefsInRunState target)")
    void signsPublicationNamespaceKey() {
        // After `WorkflowPublicationService.copyFileRefsInRunState`, items[].data
        // FileRefs are namespace-copied to `_publications/{pubId}/...`. The
        // rewriter must accept this prefix in addition to the publisher's tenant
        // prefix - otherwise the namespace copy is signed-rejected and the
        // marketplace shows broken images even though the file was migrated.
        String pubNamespacePath = "_publications/" + pub.getId() + "/snapshot/runout/copied.png";
        Map<String, Object> fileRef = Map.of(
                "_type", "file",
                "path", pubNamespacePath,
                "name", "copied.png",
                "mimeType", "image/png",
                "size", 4567);
        List<Map<String, Object>> items = List.of(Map.of("data", Map.of("img", fileRef)));

        List<Map<String, Object>> out = rewriter.rewriteItems(items, pub);

        @SuppressWarnings("unchecked")
        Map<String, Object> outData = (Map<String, Object>) out.get(0).get("data");
        String url = (String) outData.get("img");
        assertThat(url).startsWith("/api/files/proxy-signed?");
        assertThat(url).contains("sig=");
        assertThat(meterRegistry.find("publication_showcase_presign_total").tag("status", "ok").counter().count())
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("PR2 round-6: still refuses to sign a key from ANOTHER publication's `_publications/<otherPubId>/` namespace")
    void refusesForeignPublicationNamespaceKey() {
        // Defense-in-depth: even though the publication namespace is now accepted,
        // it must be THIS publication's namespace - a key from another
        // publication's namespace (e.g. injected via a corrupted snapshot) must
        // still be refused. Otherwise a publisher could leak another
        // publication's files through their own marketplace card.
        java.util.UUID otherPubId = java.util.UUID.randomUUID();
        String foreignPubPath = "_publications/" + otherPubId + "/snapshot/runout/foreign.png";
        Map<String, Object> fileRef = Map.of(
                "_type", "file", "path", foreignPubPath,
                "name", "foreign.png", "mimeType", "image/png", "size", 1);
        List<Map<String, Object>> items = List.of(Map.of("data", Map.of("img", fileRef)));

        List<Map<String, Object>> out = rewriter.rewriteItems(items, pub);

        @SuppressWarnings("unchecked")
        Map<String, Object> outData = (Map<String, Object>) out.get(0).get("data");
        // Foreign publication key → not signed, FileRef map preserved as-is.
        assertThat(outData.get("img")).isInstanceOf(Map.class);
        assertThat(meterRegistry.find("publication_showcase_presign_total")
                .tag("status", "skipped_foreign_key").counter().count())
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("PR2 round-7 (D4): rewriteLanding signs FileRefs in landing.data (standalone INTERFACE publication)")
    void rewriteLandingSignsFileRefInData() {
        Map<String, Object> fileRef = Map.of(
                "_type", "file",
                "path", "1/landing/page-banner.png",
                "name", "banner.png",
                "mimeType", "image/png",
                "size", 4096);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("banner", fileRef);
        data.put("title", "Welcome");
        Map<String, Object> landing = new LinkedHashMap<>();
        landing.put("htmlTemplate", "<img src=\"{{banner}}\">{{title}}");
        landing.put("data", data);

        Map<String, Object> out = rewriter.rewriteLanding(landing, pub);

        @SuppressWarnings("unchecked")
        Map<String, Object> outData = (Map<String, Object>) out.get("data");
        String signedUrl = (String) outData.get("banner");
        assertThat(signedUrl).startsWith("/api/files/proxy-signed?");
        assertThat(signedUrl).contains("sig=");
        // Non-FileRef fields untouched.
        assertThat(outData.get("title")).isEqualTo("Welcome");
        // htmlTemplate untouched (no FileRef in it, just placeholder).
        assertThat(out.get("htmlTemplate")).isEqualTo("<img src=\"{{banner}}\">{{title}}");
    }

    @Test
    @DisplayName("PR2 round-7 (D4): rewriteLanding skips when signer is disabled (no HMAC secret)")
    void rewriteLandingSkipsWhenSignerDisabled() {
        // Build a rewriter with a disabled signer (null secret).
        ShowcaseUrlSigner disabledSigner = new ShowcaseUrlSigner(null);
        SimpleMeterRegistry localRegistry = new SimpleMeterRegistry();
        ShowcaseFileRefRewriter disabledRewriter = new ShowcaseFileRefRewriter(disabledSigner, new PublicationFileUrlResolver(disabledSigner), new com.fasterxml.jackson.databind.ObjectMapper(), localRegistry, 15);
        Map<String, Object> landing = Map.of("htmlTemplate", "<img>",
                "data", Map.of("img", Map.of("_type", "file", "path", "1/x.png",
                        "name", "x.png", "mimeType", "image/png", "size", 1)));

        Map<String, Object> out = disabledRewriter.rewriteLanding(landing, pub);

        // Signer disabled: the payload is returned unrewritten (degraded mode, FileRef Maps
        // land in HTML as JSON - broken images but no security leak). Same content, not the
        // same instance: an internal key must not survive a path that merely declined to
        // rewrite the payload.
        assertThat(out).containsAllEntriesOf(landing);
    }

    @Test
    @DisplayName("PR2 round-7 (D4): rewriteLanding skips when publisherId is missing")
    void rewriteLandingSkipsWhenNoPublisherId() {
        pub.setPublisherId(null);
        Map<String, Object> landing = Map.of("htmlTemplate", "<img>",
                "data", Map.of("img", Map.of("_type", "file", "path", "1/x.png",
                        "name", "x.png", "mimeType", "image/png", "size", 1)));

        Map<String, Object> out = rewriter.rewriteLanding(landing, pub);

        // Same content, not the same instance: an internal key must not survive a path that
        // merely declined to rewrite the payload, so every exit returns a stripped copy.
        assertThat(out).containsAllEntriesOf(landing);
    }

    @Test
    @DisplayName("PR2 round-7 (D4): rewriteLanding handles null/empty landing gracefully")
    void rewriteLandingHandlesNullOrEmpty() {
        assertThat(rewriter.rewriteLanding(null, pub)).isNull();
        Map<String, Object> empty = new LinkedHashMap<>();
        assertThat(rewriter.rewriteLanding(empty, pub)).isSameAs(empty);
    }

    @Test
    @DisplayName("PR2 regression: FileRef with empty `path` is NOT signed - guards against shape with discriminator but no payload")
    void doesNotSignFileRefWithEmptyPath() {
        Map<String, Object> emptyPathRef = Map.of("_type", "file", "path", "",
                "name", "x.png", "mimeType", "image/png", "size", 1);
        List<Map<String, Object>> items = List.of(Map.of("data", Map.of("img", emptyPathRef)));

        List<Map<String, Object>> out = rewriter.rewriteItems(items, pub);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) out.get(0).get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> img = (Map<String, Object>) data.get("img");
        // path stays empty - no URL minted, no sign attempted.
        assertThat(img).containsEntry("path", "");
        // The meter name here was wrong (no counter is registered under it), so
        // find(...).counter() returned null and satisfiesAnyOf held whatever the code did.
        // Same vacuous assertion as the one already corrected above; corrected the same way.
        // The real counter is registered in the constructor, so it exists and reads zero.
        assertThat(meterRegistry.find("publication_showcase_presign_total")
                .tag("status", "ok").counter().count()).isEqualTo(0.0);
    }

    // ========================================================================
    // File references that reached the snapshot as URL STRINGS
    // (core:public_link output, interface-rendered proxy URLs)
    // ========================================================================

    @Test
    @DisplayName("Re-signs an ABSOLUTE core:public_link URL with a FRESH exp - regression: the frozen exp made every media on the marketplace page 403 four hours after publish, permanently")
    void reSignsAbsolutePublicLinkUrlWithFreshExpiry() {
        long deadExp = 1787778662L; // 2026-08-26T21:11:02Z, long past
        String frozen = "https://livecontext.ai/api/files/proxy-signed"
                + "?key=1%2Fed253a2d%2Frun_1%2Fcore%3Awatermark%2Fclip.mp4"
                + "&exp=" + deadExp + "&disposition=inline&sig=" + signer.sign(
                        "1/ed253a2d/run_1/core:watermark/clip.mp4", deadExp, "inline");
        List<Map<String, Object>> items = List.of(Map.of("data", mutableData("final_video", frozen)));
        long before = java.time.Instant.now().getEpochSecond();

        List<Map<String, Object>> out = rewriter.rewriteItems(items, pub);

        String url = (String) dataOf(out).get("final_video");
        assertThat(url).startsWith("/api/files/proxy-signed?");
        Map<String, String> q = parseQuery(url);
        assertThat(q.get("key")).isEqualTo("1%2Fed253a2d%2Frun_1%2Fcore%3Awatermark%2Fclip.mp4");
        assertThat(Long.parseLong(q.get("exp"))).isGreaterThanOrEqualTo(before + 15 * 60L);
        // A fresh expiry means a fresh signature - serving the old one would 403.
        assertThat(q.get("sig")).isNotEqualTo(signer.sign(
                "1/ed253a2d/run_1/core:watermark/clip.mp4", deadExp, "inline"));
        assertThat(signer.verify("1/ed253a2d/run_1/core:watermark/clip.mp4",
                Long.parseLong(q.get("exp")), "inline",
                java.net.URLDecoder.decode(q.get("sig"), java.nio.charset.StandardCharsets.UTF_8),
                java.time.Instant.now().getEpochSecond())).isTrue();
    }

    @Test
    @DisplayName("Re-signs an authenticated /api/files/proxy URL - an anonymous visitor cannot resolve that shape at all")
    void reSignsAuthenticatedProxyUrl() {
        List<Map<String, Object>> items = List.of(Map.of("data", mutableData(
                "profilePic", "/api/files/proxy?key=1%2Frun%2Fstep%2Fphoto.jpg&disposition=inline")));

        List<Map<String, Object>> out = rewriter.rewriteItems(items, pub);

        String url = (String) dataOf(out).get("profilePic");
        assertThat(url).startsWith("/api/files/proxy-signed?");
        assertThat(parseQuery(url).get("key")).isEqualTo("1%2Frun%2Fstep%2Fphoto.jpg");
    }

    @Test
    @DisplayName("Re-signing is idempotent: a URL this rewriter already minted comes back with the same key and a fresh exp")
    void reSigningItsOwnOutputIsIdempotent() {
        List<Map<String, Object>> items = List.of(Map.of("data", mutableData(
                "img", Map.of("_type", "file", "path", "1/a/b.png", "name", "b.png",
                        "mimeType", "image/png", "size", 1))));

        String once = (String) dataOf(rewriter.rewriteItems(items, pub)).get("img");
        String twice = (String) dataOf(rewriter.rewriteItems(
                List.of(Map.of("data", mutableData("img", once))), pub)).get("img");

        assertThat(parseQuery(twice).get("key")).isEqualTo(parseQuery(once).get("key"));
        assertThat(twice).startsWith("/api/files/proxy-signed?");
    }

    @Test
    @DisplayName("Refuses a URL string whose key belongs to another tenant EVEN WITH A VALID SIGNATURE - a public link is shareable, so its signature attests to the minter, not to whoever pasted it")
    void doesNotReSignForeignKeyUrl() {
        // Signed by us on purpose: this is the case where provenance alone would say yes.
        String foreign = "https://livecontext.ai/api/files/proxy-signed?key=99%2Fsecret%2Fleak.pdf"
                + "&exp=1&disposition=inline&sig=" + signer.sign("99/secret/leak.pdf", 1L, "inline");
        List<Map<String, Object>> items = List.of(Map.of("data", mutableData("doc", foreign)));

        List<Map<String, Object>> out = rewriter.rewriteItems(items, pub);

        // Left verbatim. The refusal now happens in PublicationFileUrlResolver, BEFORE the
        // string is even considered a file reference, so mintSignedUrl is never reached and
        // its skipped_foreign_key counter does not move. The FileRef-map path still counts
        // there; this one is refused earlier and logged by the resolver.
        assertThat(dataOf(out).get("doc")).isEqualTo(foreign);
    }

    @Test
    @DisplayName("Refuses a signed URL this install did not sign - the string lives in publisher-controlled data, so its shape is a claim and the HMAC is the only proof")
    void doesNotReSignForgedSignature() {
        String forged = "https://livecontext.ai/api/files/proxy-signed?key=1%2Fa%2Fclip.mp4"
                + "&exp=99999999999&disposition=inline&sig=" + java.util.Base64.getUrlEncoder()
                .withoutPadding().encodeToString("not-our-hmac-not-our-hmac-not!!!".getBytes());
        List<Map<String, Object>> items = List.of(Map.of("data", mutableData("clip", forged)));

        List<Map<String, Object>> out = rewriter.rewriteItems(List.of(items.get(0)), pub);

        assertThat(dataOf(out).get("clip")).isEqualTo(forged);
    }

    @Test
    @DisplayName("Refuses an unsigned proxy URL that names a HOST - the relative form is what the renderer bakes in; an absolute one is somebody else's URL")
    void doesNotReSignAbsoluteUnsignedProxyUrl() {
        String foreignHost = "https://attacker.example/api/files/proxy?key=1%2Fa%2Fclip.mp4&disposition=inline";
        List<Map<String, Object>> items = List.of(Map.of("data", mutableData("clip", foreignHost)));

        List<Map<String, Object>> out = rewriter.rewriteItems(items, pub);

        assertThat(dataOf(out).get("clip")).isEqualTo(foreignHost);
    }

    @Test
    @DisplayName("Leaves a plain string alone - a caption that is not a file URL must survive the walk untouched")
    void leavesOrdinaryStringsUntouched() {
        List<Map<String, Object>> items = List.of(Map.of("data", mutableData(
                "caption", "See https://livecontext.ai/api/files/proxy-signed?key=1%2Fa.png for the file")));

        List<Map<String, Object>> out = rewriter.rewriteItems(items, pub);

        assertThat(dataOf(out).get("caption"))
                .isEqualTo("See https://livecontext.ai/api/files/proxy-signed?key=1%2Fa.png for the file");
    }

    @Test
    @DisplayName("Re-signs proxy URLs nested inside a JSON-encoded string field (postsJson from a js_template)")
    void reSignsProxyUrlsInsideJsonEncodedString() {
        String postsJson = "[{\"image\":\"https://livecontext.ai/api/files/proxy-signed?key=1%2Frun%2Fp1.jpg"
                + "&exp=1&disposition=inline&sig=" + signer.sign("1/run/p1.jpg", 1L, "inline") + "\"}]";
        List<Map<String, Object>> items = List.of(Map.of("data", mutableData("postsJson", postsJson)));

        List<Map<String, Object>> out = rewriter.rewriteItems(items, pub);

        String rewritten = (String) dataOf(out).get("postsJson");
        assertThat(rewritten).contains("/api/files/proxy-signed?key=1%2Frun%2Fp1.jpg");
        assertThat(rewritten).doesNotContain("https://livecontext.ai");
        // The frozen expiry is gone. Asserted on `exp`, not on the old `sig=x`: a fresh
        // signature is base64url, so roughly one run in 64 starts with an 'x' and a
        // `doesNotContain("sig=x")` would fail for no reason.
        assertThat(rewritten).doesNotContain("&exp=1&");
    }

    @Test
    @DisplayName("rewriteLanding re-signs a public-link URL too - the marketplace card reads the landing payload, not items")
    void rewriteLandingReSignsPublicLinkUrl() {
        Map<String, Object> landing = new LinkedHashMap<>();
        landing.put("htmlTemplate", "<video src='{{clip}}'>");
        landing.put("data", mutableData("clip",
                "https://livecontext.ai/api/files/proxy-signed?key=1%2Fa%2Fclip.mp4&exp=1&disposition=inline&sig="
                        + signer.sign("1/a/clip.mp4", 1L, "inline")));

        Map<String, Object> out = rewriter.rewriteLanding(landing, pub);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) out.get("data");
        assertThat((String) data.get("clip")).startsWith("/api/files/proxy-signed?key=1%2Fa%2Fclip.mp4");
        assertThat(out.get("htmlTemplate")).isEqualTo("<video src='{{clip}}'>");
    }

    @Test
    @DisplayName("refuses to sign a key that walks out of the publisher's prefix - a path the copy pass refused is deliberately left in the snapshot, and at the other end the signature IS the whole authorization")
    void doesNotSignAKeyThatEscapesItsPrefix() {
        Map<String, Object> escaping = Map.of("_type", "file", "path", "1/../3/private/contract.pdf",
                "name", "contract.pdf", "mimeType", "application/pdf", "size", 1);
        List<Map<String, Object>> items = List.of(Map.of("data", mutableData("doc", escaping)));

        List<Map<String, Object>> out = rewriter.rewriteItems(items, pub);

        // Left as the raw FileRef, never as a signed URL.
        assertThat(dataOf(out).get("doc")).isInstanceOf(Map.class);
        assertThat(meterRegistry.find("publication_showcase_presign_total")
                .tag("status", "skipped_foreign_key").counter().count()).isEqualTo(1.0);
    }

    private static Map<String, Object> mutableData(String key, Object value) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(key, value);
        return data;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(List<Map<String, Object>> items) {
        return (Map<String, Object>) items.get(0).get("data");
    }

    private static Map<String, String> parseQuery(String url) {
        Map<String, String> out = new LinkedHashMap<>();
        int q = url.indexOf('?');
        if (q < 0) return out;
        for (String pair : url.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            out.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return out;
    }

    // ===================== stepFiles (canvas node file pills) =====================

    private static Map<String, Object> stepFileRef(String path) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("_type", "file");
        ref.put("path", path);
        ref.put("name", "clip.mp4");
        ref.put("mimeType", "video/mp4");
        ref.put("size", 2048L);
        ref.put("id", "st-9");
        return ref;
    }

    @Test
    @DisplayName("stepFiles: keeps the fields the pill renders and swaps path for a signed URL")
    void stepFilesKeepsDisplayFieldsAndSignsTheUrl() {
        // Unlike items[*].data, a node file pill is LABELLED - it shows the file name and its
        // human size before anything is expanded, so collapsing the ref to a bare URL string
        // (what rewriteItems does) would leave the canvas with an unnamed pill.
        Map<String, Object> section = Map.of("1", Map.of("download_file", stepFileRef("1/wf/run/clip.mp4")));

        Map<String, Object> out = rewriter.rewriteStepFiles(section, pub);

        Map<String, Object> pill = (Map<String, Object>) ((Map<String, Object>) out.get("1")).get("download_file");
        assertThat(pill).containsEntry("name", "clip.mp4")
                .containsEntry("mimeType", "video/mp4")
                .containsEntry("size", 2048L);
        assertThat((String) pill.get("url")).startsWith("/api/files/proxy-signed?").contains("sig=").contains("exp=");
    }

    @Test
    @DisplayName("stepFiles: the pill carries only what it renders plus the URL - no storage id, and no fields the canvas has no use for")
    void stepFilesDropsInternalHandles() {
        // NOT a secrecy measure: the signed URL carries the storage key verbatim in ?key=,
        // exactly as rewriteItems has always done. What protects the file is that the HMAC
        // binds key and expiry together. The handles are dropped because the pill's only way
        // to the bytes is the URL, so a storage id here would just invite a caller to try an
        // authenticated by-id route it has no token for.
        Map<String, Object> section = Map.of("1", Map.of("download_file", stepFileRef("1/wf/run/clip.mp4")));

        Map<String, Object> out = rewriter.rewriteStepFiles(section, pub);

        Map<String, Object> pill = (Map<String, Object>) ((Map<String, Object>) out.get("1")).get("download_file");
        assertThat(pill).containsOnlyKeys("name", "mimeType", "size", "url");
    }

    @Test
    @DisplayName("stepFiles: the signature covers the key AND the expiry, which is what makes a leaked URL bounded rather than a key handout")
    void stepFilesUrlIsBoundToKeyAndExpiry() {
        Map<String, Object> section = Map.of("1", Map.of("download_file", stepFileRef("1/wf/run/clip.mp4")));

        Map<String, Object> out = rewriter.rewriteStepFiles(section, pub);
        String url = (String) ((Map<String, Object>) ((Map<String, Object>) out.get("1")).get("download_file")).get("url");

        java.util.Map<String, String> q = parseQuery(url);
        assertThat(signer.verify(
                java.net.URLDecoder.decode(q.get("key"), java.nio.charset.StandardCharsets.UTF_8),
                Long.parseLong(q.get("exp")), q.get("disposition"), q.get("sig"),
                java.time.Instant.now().getEpochSecond())).isTrue();
        // Swapping in another key with the same signature is refused: the visitor cannot walk
        // out of the file they were given.
        assertThat(signer.verify("1/wf/run/other.mp4", Long.parseLong(q.get("exp")),
                q.get("disposition"), q.get("sig"), java.time.Instant.now().getEpochSecond())).isFalse();
    }

    @Test
    @DisplayName("stepFiles: a publication with no publisher identity yields nothing rather than an unowned URL")
    void stepFilesNeedsAPublisher() {
        WorkflowPublicationEntity orphan = new WorkflowPublicationEntity();
        orphan.setId(UUID.randomUUID());
        Map<String, Object> section = Map.of("1", Map.of("download_file", stepFileRef("1/wf/run/clip.mp4")));

        assertThat(rewriter.rewriteStepFiles(section, orphan)).isEmpty();
    }

    @Test
    @DisplayName("stepFiles: a malformed section is skipped entry by entry, never thrown over")
    void stepFilesToleratesMalformedEntries() {
        // The section comes back from JSONB and is only as well-shaped as the capture that
        // wrote it; one bad entry must not cost a whole canvas its pills.
        Map<String, Object> good = stepFileRef("1/wf/run/clip.mp4");
        Map<String, Object> notAFileRef = Map.of("name", "clip.mp4", "size", 1);
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("notAMap", "1/wf/run/clip.mp4");
        Map<String, Object> epoch = new LinkedHashMap<>();
        epoch.put("aliasNotAMap", "clip.mp4");
        epoch.put("notAFileRef", notAFileRef);
        epoch.put("download_file", good);
        section.put("1", epoch);

        Map<String, Object> out = rewriter.rewriteStepFiles(section, pub);

        assertThat(out).containsOnlyKeys("1");
        assertThat((Map<String, Object>) out.get("1")).containsOnlyKeys("download_file");
    }

    @Test
    @DisplayName("stepFiles: a key owned by neither the publisher nor this publication is DROPPED, not served")
    void stepFilesDropsForeignKey() {
        // mintSignedUrl refuses to sign it, and a pill naming a file that will never open is
        // worse than no pill at all.
        Map<String, Object> section = Map.of("1", Map.of(
                "mine", stepFileRef("1/wf/run/ok.mp4"),
                "theirs", stepFileRef("999/private/contract.pdf")));

        Map<String, Object> out = rewriter.rewriteStepFiles(section, pub);

        assertThat((Map<String, Object>) out.get("1")).containsOnlyKeys("mine");
    }

    @Test
    @DisplayName("stepFiles: an epoch left with nothing signable disappears rather than becoming an empty map")
    void stepFilesDropsEmptyEpoch() {
        Map<String, Object> section = Map.of("1", Map.of("theirs", stepFileRef("999/private/contract.pdf")));

        assertThat(rewriter.rewriteStepFiles(section, pub)).isEmpty();
    }

    @Test
    @DisplayName("stepFiles: a publication's own namespace is signable - that is where the publish copy put the bytes")
    void stepFilesSignsThePublicationNamespace() {
        // This is the whole point of the copy pass: after it, the path no longer belongs to the
        // publisher's tenant, and the preview must keep working when the source file is deleted.
        String namespaced = "_publications/" + pub.getId() + "/run-outputs/abc/clip.mp4";
        Map<String, Object> section = Map.of("1", Map.of("download_file", stepFileRef(namespaced)));

        Map<String, Object> out = rewriter.rewriteStepFiles(section, pub);

        Map<String, Object> pill = (Map<String, Object>) ((Map<String, Object>) out.get("1")).get("download_file");
        assertThat((String) pill.get("url")).contains("_publications");
    }

    @Test
    @DisplayName("stepFiles: no signing secret means no pills, never unsigned ones")
    void stepFilesNeedsASigner() {
        ShowcaseFileRefRewriter unsigned = new ShowcaseFileRefRewriter(
                new ShowcaseUrlSigner(null), new PublicationFileUrlResolver(new ShowcaseUrlSigner(null)),
                new com.fasterxml.jackson.databind.ObjectMapper(), new SimpleMeterRegistry(), 15);
        Map<String, Object> section = Map.of("1", Map.of("download_file", stepFileRef("1/wf/run/clip.mp4")));

        assertThat(unsigned.rewriteStepFiles(section, pub)).isEmpty();
    }

    @Test
    @DisplayName("stepFiles: an empty or absent section is answered with an empty map, never null")
    void stepFilesToleratesNothing() {
        assertThat(rewriter.rewriteStepFiles(null, pub)).isEmpty();
        assertThat(rewriter.rewriteStepFiles(Map.of(), pub)).isEmpty();
    }
}
