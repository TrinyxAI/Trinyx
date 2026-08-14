package com.apimarketplace.catalog.service.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Projection of unified parameters onto a provider's own request shape.
 *
 * <p>Everything past this class costs the customer money, so the validation
 * cases carry as much weight as the projection ones: a call that should have
 * been refused here is a call that gets paid for and then fails.
 */
class GenerationRequestBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static GenerationSpec spec(String json) {
        try {
            return GenerationSpec.parse(MAPPER.readTree(json), "test").orElseThrow();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Seedance-shaped: nested array prompt, constants, allowed-value constraints. */
    private static final GenerationSpec VIDEO = spec("""
            {
              "kind": "video",
              "modelParam": "model",              "assetPath": "content.video_url",
              "paramMap": {
                "prompt": "content[0].text",
                "aspect_ratio": "ratio",
                "duration_seconds": "duration",
                "seed": "seed"
              },
              "constants": { "content[0].type": "text" },
              "models": [{
                "id": "vid-1", "upstream": "vendor-vid-1", "label": "Vid 1",
                "capabilities": ["prompt", "aspect_ratio", "duration_seconds", "seed"],
                "constraints": {
                  "duration_seconds": { "allowed": [5, 10] },
                  "aspect_ratio": { "allowed": ["16:9", "9:16"] }
                },
                "price": { "unit": "second", "unitCredits": 60 }
              }]
            }
            """);

    /** ElevenLabs-shaped: binary response, scaled unit, no model selector on one variant. */
    private static final GenerationSpec MUSIC = spec("""
            {
              "kind": "music",
              "modelParam": "model_id",
              "assetPath": "$binary",
              "paramMap": {
                "prompt": "prompt",
                "duration_seconds": { "path": "music_length_ms", "scale": 1000 }
              },
              "models": [{
                "id": "music-1", "upstream": "music_v1", "label": "Music 1",
                "capabilities": ["prompt", "duration_seconds"],
                "price": { "unit": "second", "unitCredits": 8 }
              }]
            }
            """);

    private static final GenerationSpec SPEECH = spec("""
            {
              "kind": "voice",
              "assetPath": "$binary",
              "paramMap": { "prompt": "text", "voice": "voice_id" },
              "models": [{
                "id": "tts-1", "label": "TTS 1",
                "capabilities": ["prompt", "voice"],
                "price": { "unit": "character", "unitCredits": 0.2, "minCredits": 1 }
              }]
            }
            """);

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Nested
    @DisplayName("projection")
    class Projection {

        @Test
        @DisplayName("an indexed path builds the nested array the provider expects")
        void buildsNestedArray() {
            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    VIDEO, VIDEO.model("vid-1").orElseThrow(),
                    params("prompt", "a cat", "duration_seconds", 10, "aspect_ratio", "16:9"));

            assertThat(built.ok()).isTrue();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> content = (List<Map<String, Object>>) built.params().get("content");
            assertThat(content).hasSize(1);
            assertThat(content.get(0))
                    .containsEntry("type", "text")     // the constant
                    .containsEntry("text", "a cat");   // the mapped prompt
            assertThat(built.params()).containsEntry("duration", 10);
            assertThat(built.params()).containsEntry("ratio", "16:9");
        }

        @Test
        @DisplayName("the model selector is sent under the provider's own parameter name")
        void sendsModelSelector() {
            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    VIDEO, VIDEO.model("vid-1").orElseThrow(), params("prompt", "x"));
            assertThat(built.params()).containsEntry("model", "vendor-vid-1");
        }

        @Test
        @DisplayName("a single-model endpoint receives NO model parameter it would not understand")
        void omitsModelSelectorWhenAbsent() {
            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    SPEECH, SPEECH.model("tts-1").orElseThrow(), params("prompt", "hello"));
            assertThat(built.ok()).isTrue();
            assertThat(built.params()).containsOnlyKeys("text");
        }

        @Test
        @DisplayName("the tier a model pins is sent, so the provider runs the one the price was quoted for")
        void sendsThePinnedTier() {
            // Two models, one upstream, two prices: the only thing that makes
            // the cheap one cheap is the value pinned here reaching the body.
            GenerationSpec tiered = spec("""
                    {
                      "kind": "image", "modelParam": "model",
                      "assetPath": "$base64:data[0].b64_json",
                      "paramMap": { "prompt": "prompt" },
                      "models": [
                        { "id": "img-low", "upstream": "img", "capabilities": ["prompt"],
                          "constants": { "quality": "low" },
                          "price": { "unit": "call", "baseCredits": 6 } },
                        { "id": "img-high", "upstream": "img", "capabilities": ["prompt"],
                          "constants": { "quality": "high" },
                          "price": { "unit": "call", "baseCredits": 211 } }
                      ]
                    }
                    """);

            GenerationRequestBuilder.Built low = GenerationRequestBuilder.build(
                    tiered, tiered.model("img-low").orElseThrow(), params("prompt", "a boat"));
            GenerationRequestBuilder.Built high = GenerationRequestBuilder.build(
                    tiered, tiered.model("img-high").orElseThrow(), params("prompt", "a boat"));

            assertThat(low.params()).containsEntry("quality", "low").containsEntry("model", "img");
            assertThat(high.params()).containsEntry("quality", "high").containsEntry("model", "img");
        }

        @Test
        @DisplayName("a prompt past the provider's cap is refused here, where refusing is free")
        void refusesAnOverlongPromptBeforeDispatch() {
            // Wired end to end rather than on the Constraint alone: the cap only
            // protects anyone if build() consults it, and this is the path a
            // real call takes.
            GenerationSpec capped = spec("""
                    {
                      "kind": "image", "assetPath": "$binary",
                      "paramMap": { "prompt": "prompt" },
                      "models": [{
                        "id": "img-1", "capabilities": ["prompt"],
                        "constraints": { "prompt": { "maxLength": 12 } },
                        "price": { "unit": "call", "baseCredits": 5 }
                      }]
                    }
                    """);

            GenerationRequestBuilder.Built tooLong = GenerationRequestBuilder.build(
                    capped, capped.model("img-1").orElseThrow(),
                    params("prompt", "a paper boat drifting down a rain gutter"));
            GenerationRequestBuilder.Built fits = GenerationRequestBuilder.build(
                    capped, capped.model("img-1").orElseThrow(), params("prompt", "a boat"));

            assertThat(tooLong.ok()).isFalse();
            assertThat(tooLong.errors()).anySatisfy(e ->
                    assertThat(e).contains("at most 12 characters").contains("got 40"));
            assertThat(fits.ok()).isTrue();
        }

        @Test
        @DisplayName("a caller cannot reach a pinned value through a parameter of another name")
        void aPinnedValueIsNotCallerSettable() {
            GenerationSpec tiered = spec("""
                    {
                      "kind": "image", "modelParam": "model",
                      "assetPath": "$base64:data[0].b64_json",
                      "paramMap": { "prompt": "prompt", "style": "style" },
                      "models": [
                        { "id": "img-low", "upstream": "img", "capabilities": ["prompt", "style"],
                          "constants": { "quality": "low" },
                          "price": { "unit": "call", "baseCredits": 6 } }
                      ]
                    }
                    """);

            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    tiered, tiered.model("img-low").orElseThrow(),
                    params("prompt", "a boat", "style", "vivid", "quality", "high"));

            // 'quality' is not a capability of this model, so it is refused
            // outright rather than quietly dropped or, worse, written.
            assertThat(built.ok()).isFalse();
            assertThat(built.errors()).anySatisfy(e ->
                    assertThat(e).contains("does not accept 'quality'"));
        }

        @Test
        @DisplayName("a scaled binding converts the platform's seconds into the provider's milliseconds")
        void appliesScale() {
            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    MUSIC, MUSIC.model("music-1").orElseThrow(),
                    params("prompt", "lofi", "duration_seconds", 30));
            assertThat(built.params()).containsEntry("music_length_ms", 30_000L);
        }

        @Test
        @DisplayName("a null value is skipped rather than sent as an explicit null")
        void skipsNulls() {
            Map<String, Object> p = params("prompt", "x");
            p.put("seed", null);
            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    VIDEO, VIDEO.model("vid-1").orElseThrow(), p);
            assertThat(built.ok()).isTrue();
            assertThat(built.params()).doesNotContainKey("seed");
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("an unknown parameter is refused with the list this model accepts")
        void refusesUnknownParam() {
            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    VIDEO, VIDEO.model("vid-1").orElseThrow(),
                    params("prompt", "x", "resolution", "4k"));

            assertThat(built.ok()).isFalse();
            assertThat(built.errors()).anySatisfy(e -> assertThat(e)
                    .contains("does not accept 'resolution'")
                    .contains("aspect_ratio, duration_seconds, prompt, seed"));
        }

        @Test
        @DisplayName("a parameter outside the platform vocabulary is refused before anything else")
        void refusesNonVocabularyParam() {
            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    VIDEO, VIDEO.model("vid-1").orElseThrow(),
                    params("prompt", "x", "temperature", 0.7));
            assertThat(built.errors()).anySatisfy(e -> assertThat(e)
                    .contains("'temperature' is not a generation parameter"));
        }

        @Test
        @DisplayName("a value outside an allowed list is refused BEFORE the call is paid for")
        void refusesDisallowedValue() {
            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    VIDEO, VIDEO.model("vid-1").orElseThrow(),
                    params("prompt", "x", "duration_seconds", 7));

            assertThat(built.ok()).isFalse();
            assertThat(built.errors()).anySatisfy(e -> assertThat(e)
                    .contains("'duration_seconds'")
                    .contains("must be one of"));
        }

        @Test
        @DisplayName("a missing prompt is refused for a model that needs one")
        void refusesMissingPrompt() {
            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    VIDEO, VIDEO.model("vid-1").orElseThrow(), params("duration_seconds", 5));
            assertThat(built.errors()).anySatisfy(e -> assertThat(e).contains("'prompt' is required"));
        }

        @Test
        @DisplayName("a blank prompt counts as missing, not as a valid empty generation")
        void refusesBlankPrompt() {
            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    VIDEO, VIDEO.model("vid-1").orElseThrow(), params("prompt", "   "));
            assertThat(built.errors()).anySatisfy(e -> assertThat(e).contains("'prompt' is required"));
        }

        @Test
        @DisplayName("a parameter the PROVIDER requires is refused here, not after the call is paid for")
        void refusesMissingProviderRequiredParam() {
            // voice is optional to the platform vocabulary but a required path
            // parameter to ElevenLabs. Without this the call passes every check,
            // dispatches, and fails upstream on something already billed.
            GenerationSpec speechWithRequiredVoice = spec("""
                    {
                      "kind": "voice", "assetPath": "$binary",
                      "paramMap": { "prompt": "text", "voice": "voice_id" },
                      "models": [{ "id": "tts-1", "capabilities": ["prompt", "voice"],
                                   "required": ["voice"],
                                   "price": { "unit": "character", "unitCredits": 0.2 } }]
                    }
                    """);

            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    speechWithRequiredVoice, speechWithRequiredVoice.model("tts-1").orElseThrow(),
                    params("prompt", "hello"));

            assertThat(built.ok()).isFalse();
            assertThat(built.errors()).anySatisfy(e -> assertThat(e).contains("'voice' is required"));
        }

        @Test
        @DisplayName("several problems are reported together so one turn fixes them all")
        void reportsAllErrors() {
            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    VIDEO, VIDEO.model("vid-1").orElseThrow(),
                    params("duration_seconds", 7, "aspect_ratio", "4:3"));
            assertThat(built.errors()).hasSizeGreaterThanOrEqualTo(3);
        }
    }

    /** A per-MINUTE list price on a model whose calls are still measured in seconds. */
    private static final GenerationSpec MUSIC_PER_MINUTE = spec("""
            {
              "kind": "music",
              "modelParam": "model_id",
              "assetPath": "$binary",
              "paramMap": {
                "prompt": "prompt",
                "duration_seconds": { "path": "music_length_ms", "scale": 1000 }
              },
              "models": [{
                "id": "music-min", "upstream": "music_v1", "label": "Music per minute",
                "capabilities": ["prompt", "duration_seconds"],
                "price": { "unit": "minute", "unitCredits": 480 }
              }]
            }
            """);

    @Nested
    @DisplayName("billable quantity")
    class Quantity {

        @Test
        @DisplayName("a per-minute model still reports SECONDS, because that is what the platform measures")
        void perMinuteModelIsStillMeasuredInSeconds() {
            // The quantity is never pre-converted into the price's unit. The
            // rate lives on the published row, which an admin can re-express at
            // any time, so a quantity divided by 60 here and multiplied by a
            // per-second rate there is a 60x error nothing reconciles. Reporting
            // 1 (minute) for a one minute clip is exactly what did that.
            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    MUSIC_PER_MINUTE, MUSIC_PER_MINUTE.model("music-min").orElseThrow(),
                    params("prompt", "lofi", "duration_seconds", 60));

            assertThat(built.quantity()).isEqualByComparingTo("60");
            assertThat(built.quantityUnit()).isEqualTo("second");
        }

        @Test
        @DisplayName("the unit reported is the one the size was MEASURED in, not the list price's")
        void reportsThePlatformUnit() {
            assertThat(GenerationRequestBuilder.platformUnitFor(
                    MUSIC_PER_MINUTE.model("music-min").orElseThrow())).isEqualTo("second");
            assertThat(GenerationRequestBuilder.platformUnitFor(
                    VIDEO.model("vid-1").orElseThrow())).isEqualTo("second");
            assertThat(GenerationRequestBuilder.platformUnitFor(
                    SPEECH.model("tts-1").orElseThrow())).isEqualTo("character");
        }

        @Test
        @DisplayName("a per-second model reports the requested duration")
        void perSecond() {
            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    VIDEO, VIDEO.model("vid-1").orElseThrow(),
                    params("prompt", "x", "duration_seconds", 10));
            assertThat(built.quantity()).isEqualByComparingTo("10");
        }

        @Test
        @DisplayName("a per-character model reports the prompt length, which is what speech is sold by")
        void perCharacter() {
            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    SPEECH, SPEECH.model("tts-1").orElseThrow(),
                    params("prompt", "hello world"));
            assertThat(built.quantity()).isEqualByComparingTo("11");
        }

        @Test
        @DisplayName("the quantity is the UNSCALED platform value, so pricing stays in seconds")
        void quantityIgnoresProviderScale() {
            // 30 s reaches the provider as 30000 ms, but is billed as 30 seconds.
            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    MUSIC, MUSIC.model("music-1").orElseThrow(),
                    params("prompt", "lofi", "duration_seconds", 30));
            assertThat(built.params()).containsEntry("music_length_ms", 30_000L);
            assertThat(built.quantity()).isEqualByComparingTo("30");
        }

        @Test
        @DisplayName("a size the model can default is never reported as zero")
        void missingQuantityIsNeverZero() {
            // Zero is what made the flagship model unusable: it prices a
            // per-second call at its base, which is nothing, and the refusal
            // that follows blames an administrator for a published, correct
            // price. The size is stated one way or another, never omitted.
            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    VIDEO, VIDEO.model("vid-1").orElseThrow(), params("prompt", "x"));

            assertThat(built.ok()).isTrue();
            assertThat(built.quantity()).isEqualByComparingTo("5");
        }
    }

    /** A per-second model whose sizes are a bare range, with nothing requiring one. */
    private static final GenerationSpec RANGE_ONLY = spec("""
            {
              "kind": "audio",
              "assetPath": "$binary",
              "paramMap": { "prompt": "text", "duration_seconds": "duration_seconds" },
              "models": [{
                "id": "sfx-1", "label": "SFX",
                "capabilities": ["prompt", "duration_seconds"],
                "constraints": { "duration_seconds": { "min": 0.5, "max": 30 } },
                "price": { "unit": "second", "unitCredits": 4 }
              }]
            }
            """);

    /** The same model as the seed ships it: sized by declaring the parameter required. */
    private static final GenerationSpec RANGE_REQUIRED = spec("""
            {
              "kind": "audio",
              "assetPath": "$binary",
              "paramMap": { "prompt": "text", "duration_seconds": "duration_seconds" },
              "models": [{
                "id": "sfx-1", "label": "SFX",
                "capabilities": ["prompt", "duration_seconds"],
                "required": ["duration_seconds"],
                "constraints": { "duration_seconds": { "min": 0.5, "max": 30 } },
                "price": { "unit": "second", "unitCredits": 4 }
              }]
            }
            """);

    /** A per-image model: nothing constrains the count, and one asset is the norm. */
    private static final GenerationSpec IMAGE = spec("""
            {
              "kind": "image",
              "modelParam": "model",
              "assetPath": "data[0].url",
              "paramMap": { "prompt": "prompt", "n": "n" },
              "models": [{
                "id": "img-1", "upstream": "vendor-img-1", "label": "Img 1",
                "capabilities": ["prompt", "n"],
                "price": { "unit": "image", "unitCredits": 40 }
              }]
            }
            """);

    @Nested
    @DisplayName("the size of the call")
    class CallSize {

        @Test
        @DisplayName("a per-second model called with only a prompt is ACCEPTED at the model's shortest size")
        void perSecondModelWithOnlyAPromptIsAccepted() {
            // The flagship call: generation(action='create', model=..., prompt='a cat').
            // Nothing about it is invalid, so nothing about it may be refused.
            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    VIDEO, VIDEO.model("vid-1").orElseThrow(), params("prompt", "a cat"));

            assertThat(built.ok()).isTrue();
            assertThat(built.errors()).isEmpty();
            assertThat(built.quantity()).isEqualByComparingTo("5");
            assertThat(built.quantityUnit()).isEqualTo("second");
        }

        @Test
        @DisplayName("the defaulted size is SENT to the provider, so the clip is the length it is billed as")
        void defaultedSizeIsSentUpstream() {
            // Billing a default the provider was never told about is how a ten
            // second clip gets charged as five, or the reverse.
            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    VIDEO, VIDEO.model("vid-1").orElseThrow(), params("prompt", "a cat"));

            assertThat(built.params()).containsEntry("duration", 5L);
        }

        @Test
        @DisplayName("a defaulted size is converted for the provider exactly like a stated one")
        void defaultedSizeGoesThroughTheBinding() {
            GenerationSpec scaled = spec("""
                    {
                      "kind": "music", "modelParam": "model_id", "assetPath": "$binary",
                      "paramMap": {
                        "prompt": "prompt",
                        "duration_seconds": { "path": "music_length_ms", "scale": 1000 }
                      },
                      "models": [{ "id": "m1", "upstream": "M1",
                                   "capabilities": ["prompt", "duration_seconds"],
                                   "constraints": { "duration_seconds": { "allowed": [30, 60] } },
                                   "price": { "unit": "second", "unitCredits": 8 } }]
                    }
                    """);

            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    scaled, scaled.model("m1").orElseThrow(), params("prompt", "lofi"));

            assertThat(built.params()).containsEntry("music_length_ms", 30_000L);
            assertThat(built.quantity()).isEqualByComparingTo("30");
        }

        @Test
        @DisplayName("a per-image model called without n bills one asset and asks for one")
        void perImageModelDefaultsToOneAsset() {
            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    IMAGE, IMAGE.model("img-1").orElseThrow(), params("prompt", "a cat"));

            assertThat(built.ok()).isTrue();
            assertThat(built.quantity()).isEqualByComparingTo("1");
            assertThat(built.quantityUnit()).isEqualTo("image");
            assertThat(built.params()).containsEntry("n", 1L);
        }

        @Test
        @DisplayName("a size the model cannot default REFUSES the call, naming the parameter")
        void unsizeableCallNamesTheParameter() {
            // The one thing it must never be is a quantity of zero: that prices
            // the call at nothing and gets reported as an administrator's
            // missing price.
            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    RANGE_ONLY, RANGE_ONLY.model("sfx-1").orElseThrow(), params("prompt", "door creak"));

            assertThat(built.ok()).isFalse();
            assertThat(built.errors()).anySatisfy(e -> assertThat(e)
                    .contains("'duration_seconds' is required")
                    .contains("priced by"));
            assertThat(built.errors()).noneSatisfy(e -> assertThat(e).contains("administrator"));
        }

        @Test
        @DisplayName("a model that requires its size says so ONCE, not twice")
        void requiredSizeIsReportedOnce() {
            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    RANGE_REQUIRED, RANGE_REQUIRED.model("sfx-1").orElseThrow(),
                    params("prompt", "door creak"));

            assertThat(built.ok()).isFalse();
            assertThat(built.errors().stream()
                    .filter(e -> e.contains("duration_seconds")).count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("a stated size always wins over the default")
        void statedSizeWins() {
            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    VIDEO, VIDEO.model("vid-1").orElseThrow(),
                    params("prompt", "a cat", "duration_seconds", 10));

            assertThat(built.quantity()).isEqualByComparingTo("10");
            assertThat(built.params()).containsEntry("duration", 10);
        }

        @Test
        @DisplayName("a flat per-call model is one call, with or without a duration")
        void flatModelIsAlwaysOne() {
            GenerationSpec flat = spec("""
                    {
                      "kind": "image", "assetPath": "url",
                      "paramMap": { "prompt": "prompt" },
                      "models": [{ "id": "flat-1", "capabilities": ["prompt"],
                                   "price": { "unit": "call", "baseCredits": 25 } }]
                    }
                    """);

            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    flat, flat.model("flat-1").orElseThrow(), params("prompt", "x"));

            assertThat(built.quantity()).isEqualByComparingTo("1");
            assertThat(built.quantityUnit()).isEqualTo("call");
        }
    }

    @Nested
    @DisplayName("the models as they actually ship")
    class ShippedModels {

        /** seedance.json, verbatim: no `required`, sizes enumerated as 5 or 10. */
        private static final GenerationSpec SEEDANCE = spec("""
                {
                  "kind": "video",
                  "modelParam": "model",
                  "assetPath": "content.video_url",
                  "paramMap": {
                    "prompt": "content[0].text",
                    "aspect_ratio": "ratio",
                    "resolution": "resolution",
                    "duration_seconds": "duration",
                    "seed": "seed"
                  },
                  "constants": { "content[0].type": "text" },
                  "models": [{
                    "id": "seedance-2.0", "upstream": "dreamina-seedance-2-0-260128",
                    "label": "Seedance 2.0",
                    "capabilities": ["prompt", "aspect_ratio", "resolution", "duration_seconds", "seed"],
                    "constraints": {
                      "duration_seconds": { "allowed": [5, 10] },
                      "resolution": { "allowed": ["480p", "720p", "1080p"] },
                      "aspect_ratio": { "allowed": ["16:9", "9:16", "1:1"] }
                    },
                    "price": { "unit": "second", "unitCredits": 60 }
                  }]
                }
                """);

        /** elevenlabs.json sound effects, verbatim: a bare range, so `required`. */
        private static final GenerationSpec SOUND_EFFECTS = spec("""
                {
                  "kind": "audio",
                  "assetPath": "$binary",
                  "paramMap": { "prompt": "text", "duration_seconds": "duration_seconds" },
                  "models": [{
                    "id": "eleven-sound-effects", "label": "ElevenLabs Sound Effects",
                    "capabilities": ["prompt", "duration_seconds"],
                    "required": ["duration_seconds"],
                    "constraints": { "duration_seconds": { "min": 0.5, "max": 30 } },
                    "price": { "unit": "second", "unitCredits": 4, "minCredits": 8 }
                  }]
                }
                """);

        /** elevenlabs.json speech, verbatim: measured by the prompt it speaks. */
        private static final GenerationSpec SPEECH_V3 = spec("""
                {
                  "kind": "voice",
                  "modelParam": "model_id",
                  "assetPath": "$binary",
                  "paramMap": { "prompt": "text", "voice": "voice_id", "language": "language_code",
                                "seed": "seed" },
                  "models": [{
                    "id": "eleven-v3", "upstream": "eleven_v3", "label": "Eleven v3",
                    "capabilities": ["prompt", "voice", "seed", "language"],
                    "required": ["prompt", "voice"],
                    "price": { "unit": "character", "unitCredits": 0.3, "minCredits": 1 }
                  }]
                }
                """);

        @Test
        @DisplayName("seedance-2.0 with only a prompt runs, at 5 seconds and 300 credits")
        void seedanceWithOnlyAPrompt() {
            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    SEEDANCE, SEEDANCE.model("seedance-2.0").orElseThrow(),
                    params("prompt", "a cat"));

            assertThat(built.ok()).isTrue();
            assertThat(built.quantity()).isEqualByComparingTo("5");
            assertThat(built.params()).containsEntry("duration", 5L);
            // 60 credits a second on the seed's own rate: a priceable call.
            assertThat(built.quantity().multiply(new BigDecimal("60"))).isEqualByComparingTo("300");
        }

        @Test
        @DisplayName("eleven-sound-effects with only a prompt is refused naming duration_seconds")
        void soundEffectsWithOnlyAPrompt() {
            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    SOUND_EFFECTS, SOUND_EFFECTS.model("eleven-sound-effects").orElseThrow(),
                    params("prompt", "door creak"));

            assertThat(built.ok()).isFalse();
            assertThat(built.errors()).anySatisfy(e -> assertThat(e)
                    .contains("'duration_seconds' is required"));
        }

        @Test
        @DisplayName("eleven-sound-effects with a length runs and bills that length")
        void soundEffectsWithALength() {
            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    SOUND_EFFECTS, SOUND_EFFECTS.model("eleven-sound-effects").orElseThrow(),
                    params("prompt", "door creak", "duration_seconds", 3));

            assertThat(built.ok()).isTrue();
            assertThat(built.quantity()).isEqualByComparingTo("3");
            assertThat(built.params()).containsEntry("duration_seconds", 3);
        }

        @Test
        @DisplayName("eleven-v3 needs no size at all: the prompt it speaks IS the size")
        void speechIsMeasuredByItsPrompt() {
            GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                    SPEECH_V3, SPEECH_V3.model("eleven-v3").orElseThrow(),
                    params("prompt", "Welcome aboard.", "voice", "rachel"));

            assertThat(built.ok()).isTrue();
            assertThat(built.quantity()).isEqualByComparingTo("15");
            assertThat(built.quantityUnit()).isEqualTo("character");
        }
    }

    @Nested
    @DisplayName("path writing")
    class Paths {

        @Test
        @DisplayName("a dotted path creates the intermediate objects")
        void nestedObject() {
            Map<String, Object> root = new LinkedHashMap<>();
            List<String> errors = new ArrayList<>();
            GenerationRequestBuilder.setByPath(root, "config.video.fps", 24, errors);

            assertThat(errors).isEmpty();
            @SuppressWarnings("unchecked")
            Map<String, Object> config = (Map<String, Object>) root.get("config");
            @SuppressWarnings("unchecked")
            Map<String, Object> video = (Map<String, Object>) config.get("video");
            assertThat(video).containsEntry("fps", 24);
        }

        @Test
        @DisplayName("an out-of-order index fills the gap so the body stays well formed")
        void fillsArrayGaps() {
            Map<String, Object> root = new LinkedHashMap<>();
            List<String> errors = new ArrayList<>();
            GenerationRequestBuilder.setByPath(root, "items[2].v", "third", errors);

            assertThat(errors).isEmpty();
            @SuppressWarnings("unchecked")
            List<Object> items = (List<Object>) root.get("items");
            assertThat(items).hasSize(3);
            assertThat(items.get(0)).isInstanceOf(Map.class);
        }

        @Test
        @DisplayName("two writes into the same array element merge instead of overwriting")
        void mergesIntoSameElement() {
            Map<String, Object> root = new LinkedHashMap<>();
            List<String> errors = new ArrayList<>();
            GenerationRequestBuilder.setByPath(root, "content[0].type", "text", errors);
            GenerationRequestBuilder.setByPath(root, "content[0].text", "hi", errors);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> content = (List<Map<String, Object>>) root.get("content");
            assertThat(content.get(0)).containsEntry("type", "text").containsEntry("text", "hi");
        }

        @Test
        @DisplayName("a malformed path is reported rather than silently dropping the value")
        void reportsMalformedPath() {
            Map<String, Object> root = new LinkedHashMap<>();
            List<String> errors = new ArrayList<>();
            GenerationRequestBuilder.setByPath(root, "bad[[0]", "x", errors);
            assertThat(errors).anySatisfy(e -> assertThat(e).contains("malformed upstream path"));
        }

        @Test
        @DisplayName("an indexed write over a scalar is reported, not thrown as a ClassCastException")
        void reportsIndexedWriteOverScalar() {
            // Unreachable through a parsed descriptor (colliding paths are now
            // refused at parse), but GenerationSpec is a public record, so a
            // directly built one must still fail in a way the caller can read.
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("content", "already a string");
            List<String> errors = new ArrayList<>();
            GenerationRequestBuilder.setByPath(root, "content[0].text", "x", errors);
            assertThat(errors).anySatisfy(e -> assertThat(e).contains("over a non-array value"));
        }

        @Test
        @DisplayName("a path colliding with an existing scalar is reported, not forced")
        void reportsCollision() {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("a", "already a string");
            List<String> errors = new ArrayList<>();
            GenerationRequestBuilder.setByPath(root, "a.b", "x", errors);
            assertThat(errors).anySatisfy(e -> assertThat(e).contains("collides with a non-object"));
        }
    }

    @Nested
    @DisplayName("model id normalisation")
    class Normalisation {

        @Test
        @DisplayName("casing and padding never cause a lookup miss")
        void normalises() {
            assertThat(GenerationRequestBuilder.normalizeModelId("  Seedance-2.0  "))
                    .isEqualTo("seedance-2.0");
            assertThat(GenerationRequestBuilder.normalizeModelId(null)).isNull();
        }
    }

    @Test
    @DisplayName("a valid call produces no errors and a usable quantity, end to end")
    void endToEnd() {
        GenerationRequestBuilder.Built built = GenerationRequestBuilder.build(
                VIDEO, VIDEO.model("vid-1").orElseThrow(),
                params("prompt", "a cat surfing", "duration_seconds", 5,
                        "aspect_ratio", "9:16", "seed", 42));

        assertThat(built.ok()).isTrue();
        assertThat(built.quantity()).isEqualByComparingTo("5");
        assertThat(built.params()).containsEntry("seed", 42);
        assertThat(built.quantity().multiply(new BigDecimal("60"))).isEqualByComparingTo("300");
    }
}
