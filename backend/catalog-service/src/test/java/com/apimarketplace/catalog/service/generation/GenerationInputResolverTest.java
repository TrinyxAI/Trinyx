package com.apimarketplace.catalog.service.generation;

import com.apimarketplace.storage.client.StorageClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Handing an input file to a provider that has never heard of a FileRef.
 *
 * <p>Everything here runs BEFORE the reservation, so the failure paths are the
 * point: a file that cannot be read has to stop the call while it is still
 * free. What must never happen is the behaviour this class was written to end,
 * where the platform's own file handle was serialised into the provider's body
 * and the refusal arrived after the customer had paid.
 */
class GenerationInputResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A 1x1 PNG header, enough for the sniffer to name a real type. */
    private static final byte[] PNG = new byte[] {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13
    };

    private StorageClient storage;
    private GenerationInputResolver resolver;

    @BeforeEach
    void setUp() {
        storage = mock(StorageClient.class);
        resolver = new GenerationInputResolver(storage, 1_048_576L);
    }

    private static GenerationSpec spec(String json) {
        try {
            return GenerationSpec.parse(MAPPER.readTree(json), "test").orElseThrow();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final GenerationSpec DATA_URL = spec("""
            {
              "kind": "video", "assetPath": "output[0]",
              "paramMap": {
                "prompt": "promptText",
                "input_image": { "path": "promptImage", "encoding": "data_url", "role": "source" }
              },
              "models": [{ "id": "v-1", "capabilities": ["prompt", "input_image"] }]
            }
            """);

    private static final GenerationSpec BASE64_WITH_MIME = spec("""
            {
              "kind": "image", "assetPath": "$base64:data[0].b64",
              "paramMap": {
                "prompt": "contents[0].parts[0].text",
                "input_image": {
                  "path": "contents[0].parts[1].inlineData.data",
                  "encoding": "base64",
                  "role": "source",
                  "mimePath": "contents[0].parts[1].inlineData.mimeType"
                }
              },
              "models": [{ "id": "i-1", "capabilities": ["prompt", "input_image"] }]
            }
            """);

    private static final GenerationSpec MULTIPART = spec("""
            {
              "kind": "image", "assetPath": "$base64:data[0].b64",
              "paramMap": {
                "prompt": "prompt",
                "input_image": { "path": "image", "encoding": "file_ref", "role": "source" }
              },
              "models": [{ "id": "i-2", "capabilities": ["prompt", "input_image"] }]
            }
            """);

    private static Map<String, Object> fileRef() {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("_type", "file");
        ref.put("path", "tenant-1/uploads/cat.png");
        ref.put("name", "cat.png");
        ref.put("mimeType", "image/png");
        return ref;
    }

    private static Map<String, Object> sizedFileRef(long size) {
        Map<String, Object> ref = fileRef();
        ref.put("size", size);
        return ref;
    }

    private static Map<String, Object> request(String path, Object value) {
        Map<String, Object> req = new LinkedHashMap<>();
        GenerationRequestBuilder.setByPath(req, path, value, new java.util.ArrayList<>());
        return req;
    }

    @Nested
    @DisplayName("conversion")
    class Conversion {

        @Test
        @DisplayName("a data URL carries the bytes and their type in the single field the provider reads")
        void writesDataUrl() {
            when(storage.download("tenant-1", "tenant-1/uploads/cat.png")).thenReturn(PNG);
            Map<String, Object> req = request("promptImage", fileRef());

            GenerationInputResolver.Prepared prepared = resolver.prepare(DATA_URL, req, "tenant-1");

            assertThat(prepared.ok()).isTrue();
            assertThat((String) req.get("promptImage"))
                    .startsWith("data:image/png;base64,")
                    .endsWith(java.util.Base64.getEncoder().encodeToString(PNG));
        }

        @Test
        @DisplayName("bare base64 puts the media type in the second field, since the payload does not carry it")
        void writesBase64AndMime() {
            when(storage.download(anyString(), anyString())).thenReturn(PNG);
            Map<String, Object> req = request("contents[0].parts[1].inlineData.data", fileRef());

            GenerationInputResolver.Prepared prepared =
                    resolver.prepare(BASE64_WITH_MIME, req, "tenant-1");

            assertThat(prepared.ok()).isTrue();
            assertThat(GenerationRequestBuilder.getByPath(req, "contents[0].parts[1].inlineData.data"))
                    .isEqualTo(java.util.Base64.getEncoder().encodeToString(PNG));
            assertThat(GenerationRequestBuilder.getByPath(req, "contents[0].parts[1].inlineData.mimeType"))
                    .isEqualTo("image/png");
        }

        @Test
        @DisplayName("a multipart part keeps its FileRef, because the encoder downloads it further down")
        void leavesFileRefAlone() {
            when(storage.exists("tenant-1", "tenant-1/uploads/cat.png")).thenReturn(true);
            Map<String, Object> req = request("image", fileRef());

            GenerationInputResolver.Prepared prepared = resolver.prepare(MULTIPART, req, "tenant-1");

            assertThat(prepared.ok()).isTrue();
            assertThat(req.get("image")).isEqualTo(fileRef());
            // Converting here would send the same image twice, once inline and
            // once as the part.
            verify(storage, never()).download(anyString(), anyString());
        }

        @Test
        @DisplayName("a multipart part whose file is gone is refused, where the encoder would drop it in silence")
        void refusesAMultipartFileThatIsGone() {
            // The encoder logs and returns, so the request goes out WITHOUT the
            // image and the provider answers "image is required" on a call that
            // has already been dispatched. This branch never downloads, so it
            // has to ask.
            when(storage.exists(anyString(), anyString())).thenReturn(false);
            Map<String, Object> req = request("image", fileRef());

            GenerationInputResolver.Prepared prepared = resolver.prepare(MULTIPART, req, "tenant-1");

            assertThat(prepared.ok()).isFalse();
            assertThat(prepared.errors()).singleElement().asString()
                    .contains("input_image").contains("could not be read");
        }

        @Test
        @DisplayName("the type recorded at upload wins over sniffing, which only sees the first bytes")
        void prefersTheRecordedMimeType() {
            when(storage.download(anyString(), anyString())).thenReturn(PNG);
            Map<String, Object> ref = fileRef();
            ref.put("mimeType", "image/webp");
            Map<String, Object> req = request("promptImage", ref);

            resolver.prepare(DATA_URL, req, "tenant-1");

            assertThat((String) req.get("promptImage")).startsWith("data:image/webp;base64,");
        }

        @Test
        @DisplayName("a FileRef with no recorded type falls back to what the bytes actually are")
        void sniffsWhenNoMimeRecorded() {
            when(storage.download(anyString(), anyString())).thenReturn(PNG);
            Map<String, Object> ref = fileRef();
            ref.remove("mimeType");
            Map<String, Object> req = request("promptImage", ref);

            resolver.prepare(DATA_URL, req, "tenant-1");

            assertThat((String) req.get("promptImage")).startsWith("data:image/png;base64,");
        }

        @Test
        @DisplayName("a call with no input file touches nothing and reads no storage")
        void noAssetIsNoWork() {
            Map<String, Object> req = request("promptText", "a lake at dusk");

            GenerationInputResolver.Prepared prepared = resolver.prepare(DATA_URL, req, "tenant-1");

            assertThat(prepared.ok()).isTrue();
            assertThat(req).containsOnlyKeys("promptText");
            verify(storage, never()).download(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("refusals, all of them before the money moves")
    class Refusals {

        @Test
        @DisplayName("a URL string where a file belongs is refused, naming what to pass instead")
        void refusesAUrlInsteadOfAFile() {
            // The mistake this catches is the natural one: the caller has a link
            // to the image and sends the link. The platform needs the bytes.
            Map<String, Object> req = request("promptImage", "https://example.test/cat.png");

            GenerationInputResolver.Prepared prepared = resolver.prepare(DATA_URL, req, "tenant-1");

            assertThat(prepared.ok()).isFalse();
            assertThat(prepared.errors()).singleElement().asString()
                    .contains("input_image")
                    .contains("whole file object");
            verify(storage, never()).download(anyString(), anyString());
        }

        @Test
        @DisplayName("a FileRef with no storage key is refused rather than sent as an empty field")
        void refusesFileRefWithoutPath() {
            Map<String, Object> ref = fileRef();
            ref.remove("path");
            Map<String, Object> req = request("promptImage", ref);

            GenerationInputResolver.Prepared prepared = resolver.prepare(DATA_URL, req, "tenant-1");

            assertThat(prepared.ok()).isFalse();
            assertThat(prepared.errors()).singleElement().asString().contains("must be a file");
        }

        @Test
        @DisplayName("a storage failure is reported, never swallowed into a call sent without the image")
        void refusesOnStorageFailure() {
            when(storage.download(anyString(), anyString()))
                    .thenThrow(new IllegalStateException("connection reset"));
            Map<String, Object> req = request("promptImage", fileRef());

            GenerationInputResolver.Prepared prepared = resolver.prepare(DATA_URL, req, "tenant-1");

            assertThat(prepared.ok()).isFalse();
            assertThat(prepared.errors()).singleElement().asString().contains("could not be read");
        }

        @Test
        @DisplayName("no storage on this install refuses the call instead of dispatching one without its input")
        void refusesWhenStorageIsAbsent() {
            GenerationInputResolver headless = new GenerationInputResolver(null, 1_048_576L);
            Map<String, Object> req = request("promptImage", fileRef());

            GenerationInputResolver.Prepared prepared = headless.prepare(DATA_URL, req, "tenant-1");

            assertThat(prepared.ok()).isFalse();
            assertThat(prepared.errors()).singleElement().asString().contains("storage is unavailable");
        }

        @Test
        @DisplayName("a file refused by storage is not called gone: three causes reach here and only one is")
        void doesNotBlameTheWrongCause() {
            // StorageClient turns the key-owner 403 into an empty answer, so
            // "gone", "empty" and "belongs to another workspace" are
            // indistinguishable here. Naming one would send the reader looking
            // in the wrong place.
            when(storage.download(anyString(), anyString())).thenReturn(new byte[0]);
            Map<String, Object> req = request("promptImage", fileRef());

            GenerationInputResolver.Prepared prepared = resolver.prepare(DATA_URL, req, "tenant-1");

            assertThat(prepared.errors()).singleElement().asString()
                    .contains("could not be read")
                    .contains("another workspace");
        }

        @Test
        @DisplayName("the size ceiling is described as the platform's, because that is whose it is")
        void doesNotPassOffThePlatformCapAsTheProviders() {
            // One number guards every provider, and a provider may refuse a
            // smaller file than this. Worded as the provider's limit, it would
            // read as a promise that anything under it goes through.
            when(storage.download(anyString(), anyString())).thenReturn(new byte[2 * 1_048_576]);
            Map<String, Object> req = request("promptImage", fileRef());

            GenerationInputResolver.Prepared prepared = resolver.prepare(DATA_URL, req, "tenant-1");

            assertThat(prepared.errors()).singleElement().asString()
                    .contains("this platform will inline")
                    .contains("the provider may cap it lower");
        }

        @Test
        @DisplayName("a call with no tenant asks storage under a named one, rather than sending null")
        void fallsBackToAnAnonymousTenant() {
            // Only the value SENT is pinned, not the outcome: storage refuses a
            // key that does not belong to the tenant it is asked under, so
            // asserting success here would teach a behaviour production cannot
            // produce. What matters is that no null reaches the client.
            Map<String, Object> req = request("promptImage", fileRef());

            resolver.prepare(DATA_URL, req, null);

            verify(storage).download("anonymous", "tenant-1/uploads/cat.png");
        }

        @Test
        @DisplayName("a multipart part is checked too, where a bad handle used to be dropped in silence")
        void refusesABadHandleOnTheMultipartPath() {
            // The encoder further down logs and drops a part it cannot read, so
            // the request goes out WITHOUT the image and the provider answers
            // "image is required": an error pointing nowhere near the mistake,
            // on a call already dispatched.
            Map<String, Object> req = request("image", "https://example.test/cat.png");

            GenerationInputResolver.Prepared prepared = resolver.prepare(MULTIPART, req, "tenant-1");

            assertThat(prepared.ok()).isFalse();
            assertThat(prepared.errors()).singleElement().asString()
                    .contains("input_image").contains("whole file object");
        }

        @Test
        @DisplayName("a file the FileRef itself calls oversized is refused before a single byte moves")
        void refusesFromTheRecordedSize() {
            Map<String, Object> ref = fileRef();
            ref.put("size", 5 * 1_048_576);
            Map<String, Object> req = request("promptImage", ref);

            GenerationInputResolver.Prepared prepared = resolver.prepare(DATA_URL, req, "tenant-1");

            assertThat(prepared.ok()).isFalse();
            assertThat(prepared.errors()).singleElement().asString().contains("5 MB");
            // Rounded UP: truncating division reported a file just over the cap
            // as being exactly AT it, a refusal that contradicted itself.
            assertThat(resolver.prepare(DATA_URL,
                    request("promptImage", sizedFileRef(1_048_576 + 1)), "tenant-1").errors())
                    .singleElement().asString().contains("1.1 MB").contains("above the 1 MB");
            // The point of reading the recorded size: a 100 MB file must not be
            // pulled into the heap to be told it is too big.
            verify(storage, never()).download(anyString(), anyString());
        }

        @Test
        @DisplayName("a file exactly at the ceiling is accepted, so the limit is not off by one")
        void acceptsAFileAtTheCeiling() {
            when(storage.download(anyString(), anyString())).thenReturn(new byte[1_048_576]);
            Map<String, Object> req = request("promptImage", fileRef());

            GenerationInputResolver.Prepared prepared = resolver.prepare(DATA_URL, req, "tenant-1");

            assertThat(prepared.ok()).isTrue();
        }
    }
}
