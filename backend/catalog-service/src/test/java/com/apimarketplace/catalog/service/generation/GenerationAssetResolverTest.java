package com.apimarketplace.catalog.service.generation;

import com.apimarketplace.catalog.service.execution.BinaryResponseHandler;
import com.apimarketplace.common.web.UrlSafetyValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Turning a provider's answer into a stored file.
 *
 * <p>Everything here happens AFTER the customer has been charged, so the
 * failure paths matter more than the happy one: a generation that produced
 * nothing must say so loudly rather than return an empty success, and a
 * provider URL must never be able to point the fetch somewhere it should not
 * go.
 */
class GenerationAssetResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BinaryResponseHandler binaryHandler;
    private GenerationAssetResolver resolver;

    private static GenerationSpec spec(String json) {
        try {
            return GenerationSpec.parse(MAPPER.readTree(json), "test").orElseThrow();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final GenerationSpec URL_SPEC = spec("""
            {
              "kind": "video", "assetPath": "content.video_url",
              "paramMap": { "prompt": "p" },
              "models": [{ "id": "vid-1", "capabilities": ["prompt"] }]
            }
            """);

    private static final GenerationSpec BINARY_SPEC = spec("""
            {
              "kind": "voice", "assetPath": "$binary",
              "paramMap": { "prompt": "text" },
              "models": [{ "id": "tts-1", "capabilities": ["prompt"] }]
            }
            """);

    private static GenerationSpec.Model model(GenerationSpec s, String id) {
        return s.model(id).orElseThrow();
    }

    @BeforeEach
    void setUp() {
        binaryHandler = mock(BinaryResponseHandler.class);
        resolver = new GenerationAssetResolver(binaryHandler, 10_000_000L, 5L);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Nested
    @DisplayName("bytes camp")
    class BytesCamp {

        @Test
        @DisplayName("a file the catalog already stored is found and returned as-is")
        void findsStoredFile() {
            Map<String, Object> response = map("file",
                    map("_type", "file", "path", "tenant/a.mp3", "mimeType", "audio/mpeg"));

            GenerationAssetResolver.Resolved r =
                    resolver.resolve(BINARY_SPEC, model(BINARY_SPEC, "tts-1"), response, "tenant");

            assertThat(r.ok()).isTrue();
            assertThat(r.fileRef()).containsEntry("path", "tenant/a.mp3");
            // Nothing is re-uploaded: the catalog already did it on the way through.
            verify(binaryHandler, never()).storeBytes(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a stored file nested under the result envelope is still found")
        void findsStoredFileUnderEnvelope() {
            Map<String, Object> response = map("result",
                    map("audio", map("_type", "file", "path", "tenant/b.wav")));

            GenerationAssetResolver.Resolved r =
                    resolver.resolve(BINARY_SPEC, model(BINARY_SPEC, "tts-1"), response, "tenant");

            assertThat(r.ok()).isTrue();
            assertThat(r.fileRef()).containsEntry("path", "tenant/b.wav");
        }

        @Test
        @DisplayName("no stored file is a REPORTED failure, never an empty success")
        void missingStoredFileFails() {
            GenerationAssetResolver.Resolved r = resolver.resolve(
                    BINARY_SPEC, model(BINARY_SPEC, "tts-1"), map("status", "ok"), "tenant");

            assertThat(r.ok()).isFalse();
            assertThat(r.error()).contains("no stored file");
        }
    }

    @Nested
    @DisplayName("base64 camp")
    class Base64Camp {

        /** A 1x1 PNG, so the sniffed mime is a real one rather than a guess. */
        private static final byte[] PNG = new byte[] {
                (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13
        };

        private final GenerationSpec base64Spec = spec("""
                {
                  "kind": "image", "assetPath": "$base64:data[0].b64_json",
                  "paramMap": { "prompt": "prompt" },
                  "models": [{ "id": "img-1", "capabilities": ["prompt"] }]
                }
                """);

        private final GenerationSpec wildcardSpec = spec("""
                {
                  "kind": "image",
                  "assetPath": "$base64:candidates[0].content.parts[*].inlineData.data",
                  "paramMap": { "prompt": "p" },
                  "models": [{ "id": "img-2", "capabilities": ["prompt"] }]
                }
                """);

        private String encoded() {
            return java.util.Base64.getEncoder().encodeToString(PNG);
        }

        @BeforeEach
        void storeSucceeds() {
            when(binaryHandler.extensionForMime(anyString())).thenReturn(".png");
            when(binaryHandler.storeBytes(anyString(), anyString(), anyString(), anyString(), any()))
                    .thenReturn(map("_type", "file", "path", "tenant/stored.png"));
        }

        @Test
        @DisplayName("base64 still in the body is decoded and stored, so a small asset is a file like any other")
        void decodesAndStores() {
            Map<String, Object> response = map("data", List.of(map("b64_json", encoded())));

            GenerationAssetResolver.Resolved r =
                    resolver.resolve(base64Spec, model(base64Spec, "img-1"), response, "tenant");

            assertThat(r.ok()).isTrue();
            assertThat(r.fileRef()).containsEntry("path", "tenant/stored.png");
            // The bytes reach storage intact, and under the type they really are
            // rather than octet-stream: a preview depends on that mime.
            verify(binaryHandler).storeBytes(eq("tenant"), anyString(), anyString(),
                    eq("image/png"), eq(PNG));
        }

        @Test
        @DisplayName("a FileRef the catalog already dehydrated is reused, never stored a second time")
        void reusesDehydratedFileRef() {
            // The dehydrator replaces any base64 leaf over 64 KB with a FileRef
            // before this runs. Re-decoding it would store the same image twice
            // and bill the customer's storage for both.
            Map<String, Object> response = map("data", List.of(
                    map("b64_json", map("_type", "file", "path", "tenant/already.png"))));

            GenerationAssetResolver.Resolved r =
                    resolver.resolve(base64Spec, model(base64Spec, "img-1"), response, "tenant");

            assertThat(r.ok()).isTrue();
            assertThat(r.fileRef()).containsEntry("path", "tenant/already.png");
            verify(binaryHandler, never()).storeBytes(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("the wildcard skips a leading text part and finds the image, which a fixed index would miss")
        void wildcardSkipsTheTextPart() {
            // Gemini emits its parts in an order that moves: with a fixed [0]
            // this response reads the text part and the call returns nothing,
            // having been charged.
            Map<String, Object> response = map("candidates", List.of(map("content", map("parts", List.of(
                    map("text", "Here is your picture"),
                    map("inlineData", map("mimeType", "image/png", "data", encoded())))))));

            GenerationAssetResolver.Resolved r =
                    resolver.resolve(wildcardSpec, model(wildcardSpec, "img-2"), response, "tenant");

            assertThat(r.ok()).isTrue();
            assertThat(r.fileRef()).containsEntry("path", "tenant/stored.png");
        }

        @Test
        @DisplayName("a wildcard that matches no element fails rather than storing the wrong part")
        void wildcardWithNoMatchFails() {
            Map<String, Object> response = map("candidates", List.of(map("content", map("parts", List.of(
                    map("text", "I cannot draw that"))))));

            GenerationAssetResolver.Resolved r =
                    resolver.resolve(wildcardSpec, model(wildcardSpec, "img-2"), response, "tenant");

            assertThat(r.ok()).isFalse();
            assertThat(r.error()).contains("no asset at");
            verify(binaryHandler, never()).storeBytes(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a data: URL prefix is tolerated, since providers disagree on whether to send one")
        void toleratesDataUrlPrefix() {
            Map<String, Object> response = map("data",
                    List.of(map("b64_json", "data:image/png;base64," + encoded())));

            GenerationAssetResolver.Resolved r =
                    resolver.resolve(base64Spec, model(base64Spec, "img-1"), response, "tenant");

            assertThat(r.ok()).isTrue();
            verify(binaryHandler).storeBytes(anyString(), anyString(), anyString(),
                    eq("image/png"), eq(PNG));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "not base64 !!!",
                // The one that matters: a provider answering with a sentence
                // where the descriptor expects an image. The MIME decoder
                // silently drops every character outside the alphabet, so each
                // of these decodes to a handful of bytes and reports SUCCESS,
                // and the customer is handed that rubbish as the thing they paid
                // for. Only a check made BEFORE decoding can tell them apart.
                "hello world",
                "I'm sorry, I can't help with that.",
                "{\"error\":\"quota exceeded\"}",
                "The image could not be generated",
                // Single words, which the alphabet check alone still let
                // through: every character is legal base64, so only the
                // four-character grouping tells them apart from a payload.
                "moderation_blocked",
                "unavailable",
                "Error1234",
        })
        @DisplayName("prose where an asset belongs is reported, never stored as a file of garbage")
        void refusesNonBase64(String notAnAsset) {
            Map<String, Object> response = map("data", List.of(map("b64_json", notAnAsset)));

            GenerationAssetResolver.Resolved r =
                    resolver.resolve(base64Spec, model(base64Spec, "img-1"), response, "tenant");

            assertThat(r.ok()).isFalse();
            assertThat(r.error()).contains("not decodable base64");
            verify(binaryHandler, never()).storeBytes(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a payload wrapped at 76 columns still decodes, which is how providers send long ones")
        void acceptsWrappedBase64() {
            String wrapped = java.util.Base64.getMimeEncoder(
                    16, new byte[] {'\r', '\n'}).encodeToString(new byte[600]);
            assertThat(wrapped).contains("\r\n");
            Map<String, Object> response = map("data", List.of(map("b64_json", wrapped)));

            GenerationAssetResolver.Resolved r =
                    resolver.resolve(base64Spec, model(base64Spec, "img-1"), response, "tenant");

            assertThat(r.ok()).isTrue();
        }

        @Test
        @DisplayName("the wildcard steps over an element whose leaf is present but empty")
        void wildcardSkipsABlankLeaf() {
            // A blank field is not an answer. Stopping on it would report no
            // asset for a generation sitting in the very next element, after
            // the call had been charged.
            Map<String, Object> response = map("candidates", List.of(map("content", map("parts", List.of(
                    map("inlineData", map("data", "   ")),
                    map("inlineData", map("mimeType", "image/png", "data", encoded())))))));

            GenerationAssetResolver.Resolved r =
                    resolver.resolve(wildcardSpec, model(wildcardSpec, "img-2"), response, "tenant");

            assertThat(r.ok()).isTrue();
            assertThat(r.fileRef()).containsEntry("path", "tenant/stored.png");
        }

        @Test
        @DisplayName("an asset over the storage cap is refused instead of filling the heap")
        void refusesOversizedAsset() {
            GenerationAssetResolver small = new GenerationAssetResolver(binaryHandler, 4L, 5L);
            Map<String, Object> response = map("data", List.of(map("b64_json", encoded())));

            GenerationAssetResolver.Resolved r =
                    small.resolve(base64Spec, model(base64Spec, "img-1"), response, "tenant");

            assertThat(r.ok()).isFalse();
            assertThat(r.error()).contains("exceeds");
            verify(binaryHandler, never()).storeBytes(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a missing path points at async_poll, the usual cause of reading a job that has not finished")
        void missingPathNamesTheLikelyCause() {
            GenerationAssetResolver.Resolved r = resolver.resolve(
                    base64Spec, model(base64Spec, "img-1"), map("status", "processing"), "tenant");

            assertThat(r.ok()).isFalse();
            assertThat(r.error()).contains("async_poll");
        }

        @Nested
        @DisplayName("through the shaper, which is what a real response goes through")
        class ThroughTheShaper {

            /**
             * The window this covers, and why one size is not enough to prove
             * it closed.
             *
             * <p>The shaper destroys a base64 leaf in three different ways, and
             * they bite at different sizes: over 4 KB pass 1 replaces it with
             * "[BASE64_CONTENT: n KB]"; over 64 KB of SERIALISED text pass 2
             * digests the array around it; and pass 1.5 then re-clips at 1 KB.
             * The catalog's dehydrator only takes the asset out of harm's way at
             * 64 KB of DECODED bytes, which is 4/3 further out. A single sample
             * inside the first window passes while the other two are still open,
             * which is exactly what happened: the fix was declared complete and
             * a 48 to 64 KB asset was still a charged loss.
             *
             * <p>Every other test in this class hands the resolver a map built
             * by hand, so none of them could see any of this.
             */
            private final com.apimarketplace.catalog.service.ResponseShaper shaper =
                    new com.apimarketplace.catalog.service.ResponseShaper();

            private Map<String, Object> upstream(int decodedBytes) {
                byte[] image = new byte[decodedBytes];
                System.arraycopy(PNG, 0, image, 0, PNG.length);
                return map("created", 1, "data", List.of(
                        map("b64_json", java.util.Base64.getEncoder().encodeToString(image),
                                "revised_prompt", "a paper boat")));
            }

            @SuppressWarnings("unchecked")
            private Map<String, Object> shapedWith(List<String> expand, int decodedBytes) {
                return (Map<String, Object>) shaper
                        .shape(upstream(decodedBytes), expand, null,
                                com.apimarketplace.catalog.service.ResponseShaper.Mode.AGENT, true)
                        .data();
            }

            @Test
            @DisplayName("without the expand the asset is replaced by a marker, and the call is charged for nothing")
            void unexpandedAssetIsDestroyed() {
                // Pinned as the BEHAVIOUR OF THE SHAPER, not as something the
                // platform does: it is what makes the expand necessary, and if
                // the shaper ever stops doing it this test says so.
                Map<String, Object> shaped = shapedWith(List.of(), 12 * 1024);

                GenerationAssetResolver.Resolved r = resolver.resolve(
                        base64Spec, model(base64Spec, "img-1"), shaped, "tenant");

                assertThat(r.ok()).isFalse();
                assertThat(r.error()).contains("not decodable base64");
            }

            @ParameterizedTest(name = "{0} bytes")
            @ValueSource(ints = {
                    5 * 1024,    // past pass 1's 4 KB leaf cap
                    48 * 1024,   // past pass 2's 64 KB serialised budget: the one that was missed
                    60 * 1024,   // deep into pass 1.5's territory
                    63 * 1024,   // the last size before the dehydrator takes over
            })
            @DisplayName("with the expand the bytes survive at EVERY size the dehydrator does not cover")
            void expandedAssetSurvivesAtEverySize(int decodedBytes) {
                // The root segment of the descriptor's own assetPath, which is
                // what the module sends for a base64 model.
                Map<String, Object> shaped = shapedWith(List.of("data"), decodedBytes);

                GenerationAssetResolver.Resolved r = resolver.resolve(
                        base64Spec, model(base64Spec, "img-1"), shaped, "tenant");

                assertThat(r.ok()).isTrue();
                verify(binaryHandler).storeBytes(eq("tenant"), anyString(), anyString(),
                        eq("image/png"), any());
            }

            @Test
            @DisplayName("the Gemini shape survives too, since its asset sits under a different root")
            void theWildcardShapeSurvivesAsWell() {
                Map<String, Object> upstream = map("candidates", List.of(map("content", map("parts",
                        List.of(map("text", "here it is"),
                                map("inlineData", map("mimeType", "image/png", "data",
                                        java.util.Base64.getEncoder()
                                                .encodeToString(new byte[48 * 1024]))))))));

                @SuppressWarnings("unchecked")
                Map<String, Object> shaped = (Map<String, Object>) shaper
                        .shape(upstream, List.of("candidates"), null,
                                com.apimarketplace.catalog.service.ResponseShaper.Mode.AGENT, true)
                        .data();

                GenerationAssetResolver.Resolved r = resolver.resolve(
                        wildcardSpec, model(wildcardSpec, "img-2"), shaped, "tenant");

                assertThat(r.ok()).isTrue();
            }
        }

        @Nested
        @DisplayName("pruning the payload once it is a file")
        class Pruning {

            @Test
            @DisplayName("the base64 leaf is removed, so the agent is not handed the bytes it already has as a file")
            void removesTheLeaf() {
                Map<String, Object> response = map(
                        "created", 1,
                        "data", List.of(map("b64_json", encoded(), "revised_prompt", "a boat")));

                Map<String, Object> pruned = GenerationAssetResolver
                        .withoutPath(response, "data[0].b64_json").orElseThrow();

                assertThat(pruned).containsEntry("created", 1);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> data = (List<Map<String, Object>>) pruned.get("data");
                assertThat(data.get(0))
                        .doesNotContainKey("b64_json")
                        .containsEntry("revised_prompt", "a boat");
            }

            @Test
            @DisplayName("the ORIGINAL is untouched, because the catalog cached that very map")
            void neverMutatesTheOriginal() {
                // Removing in place would empty the asset out of every later
                // cache hit as well, for a response the caller was billed for.
                Map<String, Object> inner = map("b64_json", encoded());
                Map<String, Object> response = map("data", List.of(inner));

                GenerationAssetResolver.withoutPath(response, "data[0].b64_json").orElseThrow();

                assertThat(inner).containsKey("b64_json");
            }

            @Test
            @DisplayName("a wildcard prunes the element that actually carried the asset")
            void prunesThroughAWildcard() {
                Map<String, Object> response = map("candidates", List.of(map("content", map("parts", List.of(
                        map("text", "Here it is"),
                        map("inlineData", map("mimeType", "image/png", "data", encoded())))))));

                Map<String, Object> pruned = GenerationAssetResolver.withoutPath(
                        response, "candidates[0].content.parts[*].inlineData.data").orElseThrow();

                assertThat(GenerationAssetResolver.readPath(
                        pruned, "candidates[0].content.parts[*].inlineData.data")).isEmpty();
                // The text part and the media type stay: only the bytes go.
                assertThat(GenerationAssetResolver.readPath(
                        pruned, "candidates[0].content.parts[*].inlineData.mimeType"))
                        .contains("image/png");
            }

            @Test
            @DisplayName("the wildcard prunes the element the READER took, not an earlier blank one")
            void prunesTheElementTheReaderTook() {
                // Pruning the first element that merely HAS the leaf would empty
                // a decoy and leave the real payload in the reply, which is the
                // whole thing this pruning exists to avoid.
                Map<String, Object> response = map("candidates", List.of(map("content", map("parts", List.of(
                        map("inlineData", map("data", "   ")),
                        map("inlineData", map("mimeType", "image/png", "data", encoded())))))));

                Map<String, Object> pruned = GenerationAssetResolver.withoutPath(
                        response, "candidates[0].content.parts[*].inlineData.data").orElseThrow();

                assertThat(GenerationAssetResolver.readPath(
                        pruned, "candidates[0].content.parts[*].inlineData.data")).isEmpty();
            }

            @Test
            @DisplayName("a path that is not there prunes nothing, so the payload passes through as it stands")
            void absentPathChangesNothing() {
                assertThat(GenerationAssetResolver.withoutPath(
                        map("status", "processing"), "data[0].b64_json")).isEmpty();
            }
        }

        @Test
        @DisplayName("an object at the path that is not a FileRef is reported rather than stringified into storage")
        void refusesNonFileRefObject() {
            Map<String, Object> response =
                    map("data", List.of(map("b64_json", map("unexpected", "shape"))));

            GenerationAssetResolver.Resolved r =
                    resolver.resolve(base64Spec, model(base64Spec, "img-1"), response, "tenant");

            assertThat(r.ok()).isFalse();
            assertThat(r.error()).contains("object rather than the base64 asset");
        }
    }

    @Nested
    @DisplayName("URL camp")
    class UrlCamp {

        @Test
        @DisplayName("a missing asset URL explains what to fix rather than just failing")
        void missingUrlIsActionable() {
            GenerationAssetResolver.Resolved r = resolver.resolve(
                    URL_SPEC, model(URL_SPEC, "vid-1"), map("id", "job-123", "status", "running"), "tenant");

            assertThat(r.ok()).isFalse();
            assertThat(r.error())
                    .contains("no asset URL at 'content.video_url'")
                    .contains("async_poll");
        }

        @Test
        @DisplayName("a non-http scheme is refused, so a descriptor cannot turn this into a local file read")
        void refusesNonHttpScheme() {
            Map<String, Object> response = map("content", map("video_url", "file:///etc/passwd"));

            GenerationAssetResolver.Resolved r =
                    resolver.resolve(URL_SPEC, model(URL_SPEC, "vid-1"), response, "tenant");

            assertThat(r.ok()).isFalse();
            assertThat(r.error()).contains("scheme 'file' is not allowed");
            verify(binaryHandler, never()).storeBytes(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a malformed URL is refused without attempting a fetch")
        void refusesMalformedUrl() {
            Map<String, Object> response = map("content", map("video_url", "http://[bad"));

            GenerationAssetResolver.Resolved r =
                    resolver.resolve(URL_SPEC, model(URL_SPEC, "vid-1"), response, "tenant");

            assertThat(r.ok()).isFalse();
            verify(binaryHandler, never()).storeBytes(any(), any(), any(), any(), any());
        }

        /**
         * Billing commits before this resolver runs, so a failure here is a
         * customer who has paid and received nothing. The provider's link is
         * the only remaining route to the asset and it expires in minutes, so
         * discarding it converts a transient hiccup into a total loss.
         */
        @Test
        @DisplayName("a failed fetch keeps the provider link, so a paid asset is still reachable")
        void failedFetchKeepsTheProviderLink() {
            String url = "http://127.0.0.1:1/asset.mp4";
            Map<String, Object> response = map("content", map("video_url", url));

            try (MockedStatic<UrlSafetyValidator> guard = mockStatic(UrlSafetyValidator.class)) {
                guard.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(inv -> null);

                GenerationAssetResolver.Resolved r =
                        resolver.resolve(URL_SPEC, model(URL_SPEC, "vid-1"), response, "tenant");

                assertThat(r.ok()).isFalse();
                assertThat(r.assetUrl())
                        .as("the link the provider named must survive the failure")
                        .isEqualTo(url);
            }
        }

        @Test
        @DisplayName("a refused URL keeps it too: the refusal is ours, the asset was still paid for")
        void refusedUrlKeepsTheProviderLink() {
            // Nothing was fetched, but something WAS generated and charged. The
            // caller still needs to know where the provider put it, even when
            // this service will not go there itself.
            Map<String, Object> response = map("content", map("video_url", "http://10.42.0.5/asset.mp4"));

            GenerationAssetResolver.Resolved r =
                    resolver.resolve(URL_SPEC, model(URL_SPEC, "vid-1"), response, "tenant");

            assertThat(r.ok()).isFalse();
            assertThat(r.assetUrl()).isEqualTo("http://10.42.0.5/asset.mp4");
        }

        @Test
        @DisplayName("no URL was ever read, so none is invented")
        void noUrlFoundReportsNoUrl() {
            GenerationAssetResolver.Resolved r = resolver.resolve(
                    URL_SPEC, model(URL_SPEC, "vid-1"), map("id", "job-123"), "tenant");

            assertThat(r.ok()).isFalse();
            assertThat(r.assetUrl()).isNull();
        }

        @Test
        @DisplayName("an unreachable provider URL fails with the reason, not with a silent empty result")
        void unreachableUrlFails() {
            // Port 1 on loopback refuses immediately, so this exercises the real
            // IO failure path without depending on the network. The safety check
            // is satisfied here on purpose: this test is about the IO branch, and
            // the refusal of loopback has its own tests below.
            Map<String, Object> response = map("content", map("video_url", "http://127.0.0.1:1/asset.mp4"));

            try (MockedStatic<UrlSafetyValidator> guard = mockStatic(UrlSafetyValidator.class)) {
                guard.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(inv -> null);

                GenerationAssetResolver.Resolved r =
                        resolver.resolve(URL_SPEC, model(URL_SPEC, "vid-1"), response, "tenant");

                assertThat(r.ok()).isFalse();
                assertThat(r.error()).contains("fetching the generated asset failed");
            }
        }
    }

    /**
     * The bytes fetched here are STORED and handed back to the caller, so an
     * unchecked URL is a read primitive inside the cluster: whatever this fetch
     * reaches becomes a file the customer can download. The provider chooses
     * that URL, and so does anyone who can hand-edit an {@code assetPath} onto
     * a field the provider echoes back.
     */
    @Nested
    @DisplayName("internal-network refusal")
    class InternalNetworkRefusal {

        private GenerationAssetResolver.Resolved fetch(String url) {
            return resolver.resolve(URL_SPEC, model(URL_SPEC, "vid-1"),
                    map("content", map("video_url", url)), "tenant");
        }

        @Test
        @DisplayName("a private-range URL is refused, so a provider cannot read a service on the LAN")
        void refusesPrivateAddress() {
            GenerationAssetResolver.Resolved r = fetch("http://10.42.0.5:8080/api/internal/secrets");

            assertThat(r.ok()).isFalse();
            assertThat(r.error()).contains("not fetchable").contains("private/internal");
            verify(binaryHandler, never()).storeBytes(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a loopback URL is refused, so the fetch cannot read this service's own ports")
        void refusesLoopback() {
            GenerationAssetResolver.Resolved r = fetch("http://127.0.0.1:8081/actuator/env");

            assertThat(r.ok()).isFalse();
            assertThat(r.error()).contains("not fetchable");
            verify(binaryHandler, never()).storeBytes(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("the link-local metadata address is refused, the one that hands out cloud credentials")
        void refusesLinkLocalMetadata() {
            GenerationAssetResolver.Resolved r =
                    fetch("http://169.254.169.254/latest/meta-data/iam/security-credentials/");

            assertThat(r.ok()).isFalse();
            assertThat(r.error()).contains("not fetchable");
            verify(binaryHandler, never()).storeBytes(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("localhost by name is refused too, since a name is all it takes to dodge an IP check")
        void refusesLocalhostByName() {
            GenerationAssetResolver.Resolved r = fetch("http://localhost:8081/actuator/env");

            assertThat(r.ok()).isFalse();
            assertThat(r.error()).contains("not fetchable");
            verify(binaryHandler, never()).storeBytes(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a redirect from a public host to an internal address is refused at the REDIRECT, "
                + "which is how this class of guard is normally defeated")
        void refusesInternalTargetReachedByRedirect() throws Exception {
            String metadata = "http://169.254.169.254/latest/meta-data/";
            // The real guard's verdict on that hop, asserted for real before the
            // validator is stubbed for the reachable test host below.
            assertThatThrownBy(() -> UrlSafetyValidator.validateUrl(metadata))
                    .isInstanceOf(IllegalArgumentException.class);

            try (MockWebServer server = new MockWebServer()) {
                server.start();
                server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", metadata));
                String assetUrl = server.url("/asset.mp4").toString();

                try (MockedStatic<UrlSafetyValidator> guard = mockStatic(UrlSafetyValidator.class)) {
                    // Only the test server is reachable; everything else keeps the
                    // real guard's answer. Without stubbing it the server itself,
                    // being on loopback, could never be fetched at all.
                    guard.when(() -> UrlSafetyValidator.validateUrl(eq(assetUrl))).thenAnswer(inv -> null);
                    guard.when(() -> UrlSafetyValidator.validateUrl(eq(metadata)))
                            .thenThrow(new IllegalArgumentException(
                                    "Requests to private/internal network addresses are not allowed: "
                                            + "169.254.169.254"));

                    GenerationAssetResolver.Resolved r = fetch(assetUrl);

                    assertThat(r.ok()).isFalse();
                    assertThat(r.error()).contains("not fetchable").contains("169.254.169.254");
                    // The hop was CHECKED rather than followed blindly: with the
                    // client following redirects itself, this call never happens
                    // and the metadata body comes back as a stored file.
                    guard.verify(() -> UrlSafetyValidator.validateUrl(metadata));
                }
                assertThat(server.getRequestCount()).isEqualTo(1);
                verify(binaryHandler, never()).storeBytes(any(), any(), any(), any(), any());
            }
        }

        @Test
        @DisplayName("a redirect to an allowed host is still followed, so a CDN hop keeps working")
        void followsAllowedRedirect() throws Exception {
            byte[] bytes = "video-bytes".getBytes(StandardCharsets.UTF_8);
            try (MockWebServer server = new MockWebServer()) {
                server.start();
                String finalUrl = server.url("/final.mp4").toString();
                server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", finalUrl));
                server.enqueue(new MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "video/mp4")
                        .setBody(new Buffer().write(bytes)));
                when(binaryHandler.extensionForMime("video/mp4")).thenReturn(".mp4");
                when(binaryHandler.storeBytes(anyString(), anyString(), anyString(), anyString(), any()))
                        .thenReturn(Map.of("_type", "file", "path", "tenant/x.mp4"));

                try (MockedStatic<UrlSafetyValidator> guard = mockStatic(UrlSafetyValidator.class)) {
                    guard.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(inv -> null);

                    GenerationAssetResolver.Resolved r = fetch(server.url("/asset.mp4").toString());

                    assertThat(r.ok()).isTrue();
                    assertThat(r.fileRef()).containsEntry("path", "tenant/x.mp4");
                }
                assertThat(server.getRequestCount()).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("an endless redirect loop stops rather than spinning on the provider's behalf")
        void stopsOnRedirectLoop() throws Exception {
            try (MockWebServer server = new MockWebServer()) {
                server.start();
                String self = server.url("/asset.mp4").toString();
                for (int i = 0; i <= GenerationAssetResolver.MAX_REDIRECTS + 1; i++) {
                    server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", self));
                }

                try (MockedStatic<UrlSafetyValidator> guard = mockStatic(UrlSafetyValidator.class)) {
                    guard.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(inv -> null);

                    GenerationAssetResolver.Resolved r = fetch(self);

                    assertThat(r.ok()).isFalse();
                    assertThat(r.error()).contains("more than " + GenerationAssetResolver.MAX_REDIRECTS
                            + " redirects");
                }
                assertThat(server.getRequestCount()).isEqualTo(GenerationAssetResolver.MAX_REDIRECTS + 1);
                verify(binaryHandler, never()).storeBytes(any(), any(), any(), any(), any());
            }
        }
    }

    @Nested
    @DisplayName("empty input")
    class EmptyInput {

        @Test
        @DisplayName("an empty provider response is reported rather than treated as success")
        void emptyResponseFails() {
            assertThat(resolver.resolve(URL_SPEC, model(URL_SPEC, "vid-1"), Map.of(), "t").ok()).isFalse();
            assertThat(resolver.resolve(URL_SPEC, model(URL_SPEC, "vid-1"), null, "t").ok()).isFalse();
        }
    }

    @Nested
    @DisplayName("response navigation")
    class Navigation {

        @Test
        @DisplayName("reads a dotted path")
        void readsDottedPath() {
            Map<String, Object> response = map("content", map("video_url", "https://x/a.mp4"));
            assertThat(GenerationAssetResolver.readPath(response, "content.video_url"))
                    .contains("https://x/a.mp4");
        }

        @Test
        @DisplayName("reads an indexed path, which is how several providers return their outputs")
        void readsIndexedPath() {
            Map<String, Object> response = map("output", List.of(map("url", "https://x/0.mp4"),
                                                                 map("url", "https://x/1.mp4")));
            assertThat(GenerationAssetResolver.readPath(response, "output[1].url"))
                    .contains("https://x/1.mp4");
        }

        @Test
        @DisplayName("falls back through the catalog's result envelope so a descriptor written against "
                + "the provider's own docs keeps working")
        void readsThroughEnvelope() {
            Map<String, Object> response = map("result", map("content", map("video_url", "https://x/a.mp4")));
            assertThat(GenerationAssetResolver.readPath(response, "content.video_url"))
                    .contains("https://x/a.mp4");
        }

        @Test
        @DisplayName("an index past the end yields nothing instead of throwing")
        void indexOutOfRangeIsEmpty() {
            Map<String, Object> response = map("output", List.of(map("url", "https://x/0.mp4")));
            assertThat(GenerationAssetResolver.readPath(response, "output[5].url")).isEmpty();
        }

        @Test
        @DisplayName("a path through a missing or non-object node yields nothing")
        void missingNodeIsEmpty() {
            assertThat(GenerationAssetResolver.readPath(map("a", "scalar"), "a.b")).isEmpty();
            assertThat(GenerationAssetResolver.readPath(map(), "a.b")).isEmpty();
        }

        @Test
        @DisplayName("a blank value counts as absent, so an empty string never becomes a fetch")
        void blankValueIsEmpty() {
            assertThat(GenerationAssetResolver.readPath(map("content", map("video_url", "  ")),
                    "content.video_url")).isEmpty();
        }
    }

    @Nested
    @DisplayName("file ref detection")
    class FileRefDetection {

        @Test
        @DisplayName("a map carrying a path is recognised, since path is what consumers need")
        void recognisesByPath() {
            Optional<Map<String, Object>> found = GenerationAssetResolver.findExistingFileRef(
                    map("anything", map("path", "t/x.mp4", "name", "x.mp4")));
            assertThat(found).isPresent();
            assertThat(found.get()).containsEntry("path", "t/x.mp4");
        }

        @Test
        @DisplayName("a map with a blank path is NOT a file, so a placeholder never passes for an asset")
        void rejectsBlankPath() {
            assertThat(GenerationAssetResolver.findExistingFileRef(map("f", map("path", "  ")))).isEmpty();
            assertThat(GenerationAssetResolver.findExistingFileRef(map("f", map("name", "x.mp4")))).isEmpty();
        }
    }
}
