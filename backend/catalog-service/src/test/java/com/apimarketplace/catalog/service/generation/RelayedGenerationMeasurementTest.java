package com.apimarketplace.catalog.service.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A self-hosted install relaying a generation is a customer's own server. If it
 * declared the size of its own call it could declare a ten second video as one
 * second and be billed for one, which is the hole the gateway already closes by
 * stripping the generation billing headers off every inbound request.
 *
 * <p>So the cloud measures the call itself, from the body it received, using
 * the same descriptor that produced that body. These tests are about that
 * reading being right, because everything downstream multiplies it by money.
 */
class RelayedGenerationMeasurementTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A video endpoint sold per second, whose provider takes seconds too. */
    private static GenerationSpec videoSpec() {
        return parse("""
            {
              "kind": "video",
              "assetPath": "content.video_url",
              "modelParam": "model",
              "paramMap": {
                "prompt": "content[0].text",
                "duration_seconds": "duration"
              },
              "models": [
                {"id": "vid-fast", "upstream": "vendor-fast", "label": "Fast",
                 "capabilities": ["prompt", "duration_seconds"], "required": ["prompt"],
                 "price": {"unit": "second", "baseCredits": 0, "unitCredits": 60}},
                {"id": "vid-pro", "upstream": "vendor-pro", "label": "Pro",
                 "capabilities": ["prompt", "duration_seconds"], "required": ["prompt"],
                 "price": {"unit": "second", "baseCredits": 0, "unitCredits": 120}}
              ]
            }
            """);
    }

    private static GenerationSpec parse(String json) {
        try {
            return GenerationSpec.parse(MAPPER.readTree(json), "test").orElseThrow();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Nested
    @DisplayName("which model the body selects")
    class ModelSelection {

        @Test
        @DisplayName("reads the model back through its UPSTREAM name, which is what the body carries")
        void translatesTheUpstreamNameToThePublicId() {
            // The price is published against the public id; the request carries
            // the vendor's name for the same model. Comparing the two directly
            // would find nothing and price the call as unmeasurable.
            var measured = RelayedGenerationMeasurement.measure(videoSpec(),
                    Map.of("model", "vendor-pro", "duration", 10));

            assertThat(measured.modelId()).isEqualTo("vid-pro");
        }

        @Test
        @DisplayName("distinguishes two models on the same endpoint, which is the whole reason the model is read")
        void picksTheModelTheBodyNames() {
            var fast = RelayedGenerationMeasurement.measure(videoSpec(),
                    Map.of("model", "vendor-fast", "duration", 10));
            var pro = RelayedGenerationMeasurement.measure(videoSpec(),
                    Map.of("model", "vendor-pro", "duration", 10));

            assertThat(fast.modelId()).isEqualTo("vid-fast");
            assertThat(pro.modelId()).isEqualTo("vid-pro");
        }

        @Test
        @DisplayName("an unknown model is not measured, so it cannot be priced as some other model")
        void refusesToGuessAnUnknownModel() {
            var measured = RelayedGenerationMeasurement.measure(videoSpec(),
                    Map.of("model", "something-else", "duration", 10));

            assertThat(measured.modelId()).isNull();
            assertThat(measured.quantity()).isNull();
        }
    }

    @Nested
    @DisplayName("how big the call is")
    class Size {

        @Test
        @DisplayName("reads the size through the binding, so a 10 second clip measures 10 and not 1")
        void readsTheMeasuringParameter() {
            var measured = RelayedGenerationMeasurement.measure(videoSpec(),
                    Map.of("model", "vendor-fast", "duration", 10));

            assertThat(measured.quantity()).isEqualByComparingTo("10");
        }

        @Test
        @DisplayName("a size of ZERO is no size at all, exactly as the direct path reads it")
        void zeroIsNotAMeasurement() {
            // NOT cosmetic, and not symmetry for its own sake. A zero read as a
            // real measurement is worse than a missing one: the resolver reports
            // unitCredits as ZERO whenever any quantity was supplied, so the
            // relay's per-unit refusal cannot fire, and the amount becomes
            // base + rate x 0 clamped UP to minCredits. The install is then
            // billed a floor for a size it never stated, while the provider
            // charges the platform owner for whatever it auto-selected.
            //
            // Null is what makes that refusal fire, which is the same outcome
            // CatalogToolBillingService.sizeMissing already produces for <= 0.
            var measured = RelayedGenerationMeasurement.measure(videoSpec(),
                    Map.of("model", "vendor-fast", "duration", 0));

            assertThat(measured.modelId()).isEqualTo("vid-fast");
            assertThat(measured.quantity())
                    .as("zero seconds is not a call anyone meant to make")
                    .isNull();
        }

        @Test
        @DisplayName("an EMPTY prompt is no size either, on a model measured by its own text")
        void emptyPromptIsNotAMeasurement() {
            GenerationSpec spec = parse("""
                {
                  "kind": "voice",
                  "assetPath": "$binary",
                  "paramMap": {"prompt": "text"},
                  "models": [
                    {"id": "tts", "upstream": "tts-1", "label": "TTS",
                     "capabilities": ["prompt"], "required": ["prompt"],
                     "price": {"unit": "character", "unitCredits": 0.2, "minCredits": 1}}
                  ]
                }
                """);

            var measured = RelayedGenerationMeasurement.measure(spec, Map.of("text", ""));

            assertThat(measured.quantity())
                    .as("a zero-length text reaches the same floor-clamp as a zero duration")
                    .isNull();
        }

        @Test
        @DisplayName("carries the unit the size is counted in, or a rate cannot be checked against it")
        void reportsThePlatformUnit() {
            // A quantity without its unit cannot be checked against a published
            // rate: a row priced per image and a call measured in seconds both
            // arrive as a bare 10. This is the value the relay's dimension
            // guard reads, so without it that guard cannot exist.
            var measured = RelayedGenerationMeasurement.measure(videoSpec(),
                    Map.of("model", "vendor-fast", "duration", 10));

            assertThat(measured.quantityUnit()).isEqualTo("second");
        }

        @Test
        @DisplayName("the unit a probe derives is the one the executing path measures")
        void probeAndExecutionAgreeOnTheUnit() {
            // The quote has no body to read, so it derives the unit from the
            // model. If the two derivations could differ, the quote would
            // answer with an amount the biller refuses, and neither end would
            // show the disagreement: both look like ordinary successes.
            var fromBody = RelayedGenerationMeasurement.measure(videoSpec(),
                    Map.of("model", "vendor-pro", "duration", 4));

            assertThat(RelayedGenerationMeasurement.platformUnitFor(videoSpec(), "vid-pro"))
                    .isEqualTo(fromBody.quantityUnit());
        }

        @Test
        @DisplayName("an unknown model yields NO unit, which means cannot tell and never no unit")
        void unknownModelHasNoUnit() {
            assertThat(RelayedGenerationMeasurement.platformUnitFor(videoSpec(), "not-a-model"))
                    .isNull();
        }

        @Test
        @DisplayName("follows a NESTED binding path, because a real request body has them")
        void readsThroughANestedPath() {
            GenerationSpec spec = parse("""
                {
                  "kind": "voice",
                  "assetPath": "content.audio_url",
                  "paramMap": {"prompt": "input[0].text"},
                  "models": [
                    {"id": "tts", "upstream": "tts-1", "label": "TTS",
                     "capabilities": ["prompt"], "required": ["prompt"],
                     "price": {"unit": "character", "baseCredits": 0, "unitCredits": 1}}
                  ]
                }
                """);

            var measured = RelayedGenerationMeasurement.measure(spec,
                    Map.of("input", List.of(Map.of("text", "hello"))));

            // Speech is measured by its own text, so five characters is five.
            assertThat(measured.quantity()).isEqualByComparingTo("5");
        }

        @Test
        @DisplayName("undoes the writer's scale, so a per-minute model billed in provider seconds is not charged 60x")
        void invertsTheBindingScale() {
            // The builder multiplies by the scale on the way out. A reader that
            // did not divide would read 300 where the platform measures 5.
            GenerationSpec spec = parse("""
                {
                  "kind": "music",
                  "assetPath": "content.audio_url",
                  "paramMap": {
                    "prompt": "text",
                    "duration_seconds": {"path": "length_ms", "scale": 1000}
                  },
                  "models": [
                    {"id": "music-v1", "upstream": "music", "label": "Music",
                     "capabilities": ["prompt", "duration_seconds"], "required": ["prompt"],
                     "price": {"unit": "minute", "baseCredits": 0, "unitCredits": 480}}
                  ]
                }
                """);

            var measured = RelayedGenerationMeasurement.measure(spec,
                    Map.of("text", "a song", "length_ms", 5000));

            assertThat(measured.quantity()).isEqualByComparingTo("5");
        }

        @Test
        @DisplayName("a model sold per CALL measures one without reading anything")
        void aFlatModelIsOneCall() {
            GenerationSpec spec = parse("""
                {
                  "kind": "image",
                  "assetPath": "content.image_url",
                  "paramMap": {"prompt": "prompt"},
                  "models": [
                    {"id": "img", "upstream": "img-1", "label": "Image",
                     "capabilities": ["prompt"], "required": ["prompt"],
                     "price": {"unit": "call", "baseCredits": 40, "unitCredits": 0}}
                  ]
                }
                """);

            var measured = RelayedGenerationMeasurement.measure(spec, Map.of("prompt", "a cat"));

            assertThat(measured.quantity()).isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("a body that does not carry the size is UNMEASURED, never zero")
        void anAbsentSizeIsNullNotZero() {
            // Zero would price the call at its base alone, which for a pure
            // per-second rate is nothing: the platform would hand out its own
            // provider key for free. Null makes the caller refuse instead.
            var measured = RelayedGenerationMeasurement.measure(videoSpec(),
                    Map.of("model", "vendor-fast"));

            assertThat(measured.modelId()).isEqualTo("vid-fast");
            assertThat(measured.quantity()).isNull();
        }

        @Test
        @DisplayName("a negative size is refused rather than clamped, because nobody sends one by accident")
        void aNegativeSizeIsRefused() {
            var measured = RelayedGenerationMeasurement.measure(videoSpec(),
                    Map.of("model", "vendor-fast", "duration", -10));

            assertThat(measured.quantity()).isNull();
        }

        @Test
        @DisplayName("a size that is not a number is refused rather than coerced")
        void aNonNumericSizeIsRefused() {
            var measured = RelayedGenerationMeasurement.measure(videoSpec(),
                    Map.of("model", "vendor-fast", "duration", "ten"));

            assertThat(measured.quantity()).isNull();
        }
    }

    @Nested
    @DisplayName("nothing to measure")
    class NothingToMeasure {

        @Test
        @DisplayName("no descriptor means no measurement, so an ordinary endpoint is untouched")
        void noSpecMeasuresNothing() {
            assertThat(RelayedGenerationMeasurement.measure(null, Map.of("duration", 10)))
                    .isEqualTo(RelayedGenerationMeasurement.Measured.NOTHING);
        }

        @Test
        @DisplayName("an empty body measures nothing, which is the discovery caller's case")
        void noParamsMeasureNothing() {
            assertThat(RelayedGenerationMeasurement.measure(videoSpec(), Map.of()))
                    .isEqualTo(RelayedGenerationMeasurement.Measured.NOTHING);
        }
    }
}
