package com.apimarketplace.agent.catalog.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-fixture tests for the LiteLLM feed parser. The inline fixtures cover
 * every rejection branch (non-native provider, non-chat mode, no-tools,
 * slash id, zero price) plus a happy-path accept per native provider.
 *
 * <p>Field values mirror real LiteLLM shapes - the western providers sampled
 * 2026-04-22, the Moonshot / Qwen / Z.AI rows 2026-07-30 - taken from the live
 * feed and trimmed.
 *
 * <p>{@link LiteLlmProviderCoverageTest} guards the complementary invariant:
 * that no executable provider is missing from {@code PROVIDER_MAP} in the
 * first place.
 */
@DisplayName("LiteLlmFeedParser - filters + normalisation")
class LiteLlmFeedParserTest {

    private LiteLlmFeedParser parser;

    @BeforeEach
    void setUp() {
        parser = new LiteLlmFeedParser(new ObjectMapper());
    }

    @Test
    @DisplayName("Happy path: 8 native providers accepted, non-natives dropped")
    void happyPath() {
        String fixture = """
            {
              "sample_spec": {"litellm_provider": "openai"},
              "claude-opus-4-7": {
                "litellm_provider": "anthropic", "mode": "chat",
                "max_input_tokens": 1000000, "max_output_tokens": 128000,
                "input_cost_per_token": 5e-06, "output_cost_per_token": 2.5e-05,
                "supports_function_calling": true, "supports_vision": true,
                "supports_prompt_caching": true, "supports_reasoning": true,
                "cache_read_input_token_cost": 5e-07
              },
              "gpt-5.4": {
                "litellm_provider": "openai", "mode": "chat",
                "max_input_tokens": 1050000, "max_output_tokens": 128000,
                "input_cost_per_token": 2.5e-06, "output_cost_per_token": 1.5e-05,
                "input_cost_per_token_batches": 1.25e-06,
                "output_cost_per_token_batches": 7.5e-06,
                "supports_function_calling": true, "supports_vision": true,
                "supported_endpoints": ["/v1/chat/completions", "/v1/batch"]
              },
              "gemini/gemini-2.5-pro": {
                "litellm_provider": "gemini", "mode": "chat",
                "input_cost_per_token": 1.25e-06, "output_cost_per_token": 1e-05,
                "max_input_tokens": 1048576, "max_output_tokens": 65535,
                "supports_function_calling": true
              },
              "mistral/mistral-large-latest": {
                "litellm_provider": "mistral", "mode": "chat",
                "input_cost_per_token": 2e-06, "output_cost_per_token": 6e-06,
                "supports_function_calling": true
              },
              "deepseek-chat": {
                "litellm_provider": "deepseek", "mode": "chat",
                "input_cost_per_token": 2.7e-07, "output_cost_per_token": 1.1e-06,
                "supports_function_calling": true
              },
              "xai/grok-3": {
                "litellm_provider": "xai", "mode": "chat",
                "input_cost_per_token": 3e-06, "output_cost_per_token": 1.5e-05,
                "supports_function_calling": true
              },
              "perplexity/sonar-pro": {
                "litellm_provider": "perplexity", "mode": "chat",
                "input_cost_per_token": 3e-06, "output_cost_per_token": 1.5e-05,
                "supports_function_calling": true
              },
              "cohere/command-r-plus-08-2024": {
                "litellm_provider": "cohere_chat", "mode": "chat",
                "input_cost_per_token": 2.5e-06, "output_cost_per_token": 1e-05,
                "supports_function_calling": true
              },
              "vertex_ai/claude-opus-4-7": {
                "litellm_provider": "vertex_ai-language-models", "mode": "chat",
                "input_cost_per_token": 5e-06, "output_cost_per_token": 2.5e-05,
                "supports_function_calling": true
              },
              "bedrock/claude-opus-4-7": {
                "litellm_provider": "bedrock", "mode": "chat",
                "input_cost_per_token": 5e-06, "output_cost_per_token": 2.5e-05,
                "supports_function_calling": true
              }
            }
            """;

        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "test-sha", "2026-04-22T00:00:00Z");

        assertThat(result.isSuccess()).isTrue();
        // 8 native providers accepted; vertex_ai and bedrock rejected.
        assertThat(result.models()).hasSize(8);
        assertThat(result.rejectedProvider()).isEqualTo(2);

        // gemini → google mapping, prefix stripped
        Map<String, Object> gemini = findByModelId(result.models(), "gemini-2.5-pro");
        assertThat(gemini.get("provider")).isEqualTo("google");

        // cohere_chat → cohere alias
        Map<String, Object> cohere = findByModelId(result.models(), "command-r-plus-08-2024");
        assertThat(cohere.get("provider")).isEqualTo("cohere");
    }

    @Test
    @DisplayName("Regression: Kimi / Qwen / GLM are accepted - PROVIDER_MAP used to drop all three")
    void acceptsMoonshotQwenAndZai() {
        // Pre-fix, PROVIDER_MAP held only 8 western providers, so every row
        // below hit the rejectedProvider branch: the sync reported OK with
        // nothing added and Moonshot/Qwen/Z.AI stayed frozen on the ids
        // hardcoded in application.yml. Shapes sampled from the live feed
        // 2026-07-30; note LiteLLM keys Qwen under "dashscope".
        String fixture = """
            {
              "moonshot/kimi-k2.6": {
                "litellm_provider": "moonshot", "mode": "chat",
                "max_input_tokens": 262144, "max_output_tokens": 32768,
                "input_cost_per_token": 9.5e-07, "output_cost_per_token": 4e-06,
                "supports_function_calling": true
              },
              "moonshot/kimi-k2-thinking": {
                "litellm_provider": "moonshot", "mode": "chat",
                "input_cost_per_token": 6e-07, "output_cost_per_token": 2.5e-06,
                "supports_function_calling": true, "supports_reasoning": true
              },
              "dashscope/qwen-max": {
                "litellm_provider": "dashscope", "mode": "chat",
                "input_cost_per_token": 1.6e-06, "output_cost_per_token": 6.4e-06,
                "supports_function_calling": true
              },
              "dashscope/qwen3-vl-32b-instruct": {
                "litellm_provider": "dashscope", "mode": "chat",
                "input_cost_per_token": 1.6e-07, "output_cost_per_token": 6.4e-07,
                "supports_function_calling": true, "supports_vision": true
              },
              "zai/glm-5.1": {
                "litellm_provider": "zai", "mode": "chat",
                "input_cost_per_token": 1.4e-06, "output_cost_per_token": 4.4e-06,
                "supports_function_calling": true
              },
              "minimax/MiniMax-M3": {
                "litellm_provider": "minimax", "mode": "chat",
                "input_cost_per_token": 3e-07, "output_cost_per_token": 1.2e-06,
                "supports_function_calling": true
              }
            }
            """;

        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.models()).hasSize(5);
        // minimax has no provider entry / relay support yet, so it stays out.
        assertThat(result.rejectedProvider()).isEqualTo(1);

        // The "moonshot/" prefix is stripped, so the native id matches the
        // application.yml entry exactly and the merge updates rather than
        // duplicating it.
        Map<String, Object> kimi = findByModelId(result.models(), "kimi-k2.6");
        assertThat(kimi.get("provider")).isEqualTo("moonshot");
        assertThat((BigDecimal) kimi.get("priceInput"))
                .isEqualByComparingTo(new BigDecimal("0.95"));
        assertThat((BigDecimal) kimi.get("priceOutput"))
                .isEqualByComparingTo(new BigDecimal("4.0"));

        // dashscope is LiteLLM's key for Alibaba's API; our provider is "qwen".
        Map<String, Object> qwen = findByModelId(result.models(), "qwen-max");
        assertThat(qwen.get("provider")).isEqualTo("qwen");
        assertThat((BigDecimal) qwen.get("priceInput"))
                .isEqualByComparingTo(new BigDecimal("1.6"));

        Map<String, Object> qwenVl = findByModelId(result.models(), "qwen3-vl-32b-instruct");
        assertThat(qwenVl.get("provider")).isEqualTo("qwen");
        assertThat(qwenVl.get("supportsVision")).isEqualTo(true);

        Map<String, Object> glm = findByModelId(result.models(), "glm-5.1");
        assertThat(glm.get("provider")).isEqualTo("zai");
        assertThat((BigDecimal) glm.get("priceOutput"))
                .isEqualByComparingTo(new BigDecimal("4.4"));
    }

    @Test
    @DisplayName("stripProviderPrefix maps the new keys onto the ids application.yml already uses")
    void stripsPrefixForNewProviderKeys() {
        // The native id is the row identity: get this wrong and the merge
        // inserts a SECOND row instead of updating the seeded one, so the
        // picker shows "kimi-k2.6" twice with divergent prices.
        assertThat(LiteLlmFeedParser.stripProviderPrefix("moonshot/kimi-k2.6", "moonshot"))
                .isEqualTo("kimi-k2.6");
        // dashscope is LiteLLM's key, "qwen" is ours - the strip must use the
        // FEED key, not the mapped name, or the prefix survives.
        assertThat(LiteLlmFeedParser.stripProviderPrefix("dashscope/qwen-max", "dashscope"))
                .isEqualTo("qwen-max");
        assertThat(LiteLlmFeedParser.stripProviderPrefix("zai/glm-5.1", "zai"))
                .isEqualTo("glm-5.1");

        // Some feed rows carry no prefix at all - they must pass through
        // untouched rather than losing a leading segment.
        assertThat(LiteLlmFeedParser.stripProviderPrefix("kimi-latest-8k", "moonshot"))
                .isEqualTo("kimi-latest-8k");
        // A model id that merely CONTAINS the provider name is not a prefix.
        assertThat(LiteLlmFeedParser.stripProviderPrefix("glm-5-code", "zai"))
                .isEqualTo("glm-5-code");
    }

    @Test
    @DisplayName("Unpriced Qwen / GLM preview rows are still dropped by the zero-price gate")
    void dropsUnpricedChineseRows() {
        // A sizeable slice of the dashscope + zai lines ships with no pricing
        // at all (qwen3-max, qwen-flash) or an explicit 0/0 free tier
        // (glm-4.7-flash). Widening PROVIDER_MAP must not let those past the
        // price gate - a zero-priced row slips through the CreditService and
        // enables free LLM use.
        String fixture = """
            {
              "dashscope/qwen3-max": {
                "litellm_provider": "dashscope", "mode": "chat",
                "supports_function_calling": true
              },
              "zai/glm-4.7-flash": {
                "litellm_provider": "zai", "mode": "chat",
                "input_cost_per_token": 0, "output_cost_per_token": 0,
                "supports_function_calling": true
              },
              "zai/glm-5.1": {
                "litellm_provider": "zai", "mode": "chat",
                "input_cost_per_token": 1.4e-06, "output_cost_per_token": 4.4e-06,
                "supports_function_calling": true
              }
            }
            """;

        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");

        // zeroPrice is counted in the parse log but not carried on ParseResult,
        // so assert on what survived: the two unpriced rows are gone and it is
        // NOT the provider gate that dropped them.
        assertThat(result.models()).hasSize(1);
        assertThat(result.models().get(0).get("modelId")).isEqualTo("glm-5.1");
        assertThat(result.rejectedProvider()).isZero();
    }

    @Test
    @DisplayName("Drops mode=embedding / image / audio - only chat is accepted")
    void dropsNonChatMode() {
        String fixture = """
            {
              "text-embedding-3-large": {
                "litellm_provider": "openai", "mode": "embedding",
                "input_cost_per_token": 1.3e-07, "output_cost_per_token": 0,
                "supports_function_calling": false
              },
              "gpt-5-chat": {
                "litellm_provider": "openai", "mode": "chat",
                "input_cost_per_token": 1.25e-06, "output_cost_per_token": 1e-05,
                "supports_function_calling": false
              },
              "gpt-5.4": {
                "litellm_provider": "openai", "mode": "chat",
                "input_cost_per_token": 2.5e-06, "output_cost_per_token": 1.5e-05,
                "supports_function_calling": true
              }
            }
            """;

        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");

        assertThat(result.models()).hasSize(1);
        assertThat(result.rejectedMode()).isEqualTo(1);
        assertThat(result.rejectedNoTools()).isEqualTo(1);
        assertThat(result.models().get(0).get("modelId")).isEqualTo("gpt-5.4");
    }

    @Test
    @DisplayName("Drops ft: fine-tuning pricing templates - they're not callable until a tenant fine-tunes the base")
    void dropsFineTuneTemplates() {
        // LiteLLM publishes ft:-prefixed rows as per-token rate templates for
        // ANY fine-tune of the base model. A real fine-tune id is
        // "ft:<base>:<org>:<job>:<hash>" - the template collides with no real
        // OpenAI model. Surfacing them in the picker would give every tenant
        // a row that 404s at dispatch. Admins add real fine-tunes via is_custom=true.
        String fixture = """
            {
              "ft:gpt-4.1-mini-2025-04-14": {
                "litellm_provider": "openai", "mode": "chat",
                "input_cost_per_token": 8e-07, "output_cost_per_token": 3.2e-06,
                "supports_function_calling": true
              },
              "ft:gpt-4o-2024-08-06": {
                "litellm_provider": "openai", "mode": "chat",
                "input_cost_per_token": 3.75e-06, "output_cost_per_token": 1.5e-05,
                "supports_function_calling": true
              },
              "gpt-4o-2024-08-06": {
                "litellm_provider": "openai", "mode": "chat",
                "input_cost_per_token": 2.5e-06, "output_cost_per_token": 1e-05,
                "supports_function_calling": true
              }
            }
            """;

        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");

        assertThat(result.models()).hasSize(1);
        assertThat(result.models().get(0).get("modelId")).isEqualTo("gpt-4o-2024-08-06");
    }

    @Test
    @DisplayName("Accepted model produces canonical map with all V121 enrichment fields")
    void canonicalShape() {
        String fixture = """
            {
              "claude-opus-4-7-20260416": {
                "litellm_provider": "anthropic", "mode": "chat",
                "max_input_tokens": 1000000, "max_output_tokens": 128000,
                "input_cost_per_token": 5e-06, "output_cost_per_token": 2.5e-05,
                "input_cost_per_token_batches": 2.5e-06,
                "cache_read_input_token_cost": 5e-07,
                "cache_creation_input_token_cost": 6.25e-06,
                "supports_function_calling": true, "supports_vision": true,
                "supports_prompt_caching": true, "supports_reasoning": true,
                "supports_computer_use": true, "supports_response_schema": true,
                "deprecation_date": "2027-01-01"
              }
            }
            """;
        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha-xyz", "fetch-ts");

        assertThat(result.models()).hasSize(1);
        Map<String, Object> m = result.models().get(0);
        assertThat(m.get("provider")).isEqualTo("anthropic");
        assertThat(m.get("modelId")).isEqualTo("claude-opus-4-7-20260416");
        assertThat((BigDecimal) m.get("priceInput"))
                .isEqualByComparingTo(new BigDecimal("5.0000"));
        assertThat((BigDecimal) m.get("priceOutput"))
                .isEqualByComparingTo(new BigDecimal("25.0000"));
        assertThat(m.get("tier")).isEqualTo("top");
        assertThat(m.get("contextWindow")).isEqualTo(1000000);
        assertThat(m.get("maxOutputTokens")).isEqualTo(128000);
        assertThat(m.get("supportsTools")).isEqualTo(true);
        assertThat(m.get("supportsVision")).isEqualTo(true);
        assertThat(m.get("supportsPromptCaching")).isEqualTo(true);
        assertThat(m.get("supportsReasoning")).isEqualTo(true);
        assertThat(m.get("supportsComputerUse")).isEqualTo(true);
        assertThat(m.get("supportsResponseSchema")).isEqualTo(true);
        assertThat(m.get("mode")).isEqualTo("chat");
        assertThat(m.get("deprecationDate")).isEqualTo("2027-01-01");
        assertThat(m.get("releaseDate")).isEqualTo("2026-04-16"); // extracted from suffix

        // Price floor = min(input, inputBatch) = 2.5
        assertThat((BigDecimal) m.get("priceFloorInput"))
                .isEqualByComparingTo(new BigDecimal("2.5"));

        // Feed metadata - raw payload round-trips.
        @SuppressWarnings("unchecked")
        Map<String, Object> feedMeta = (Map<String, Object>) m.get("feedMetadata");
        assertThat(feedMeta).containsEntry("source", "litellm")
                .containsEntry("sourceSha", "sha-xyz")
                .containsEntry("fetchedAt", "fetch-ts");
        assertThat(feedMeta.get("raw")).isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("Drops rows where stripping provider prefix still leaves a slash")
    void rejectsSlashInNativeId() {
        // Contrived: the identity prefix doesn't match the litellm_provider.
        // Our stripper leaves the slash in, and the no-slash guard catches it.
        String fixture = """
            {
              "weird/nested/id": {
                "litellm_provider": "openai", "mode": "chat",
                "input_cost_per_token": 1e-06, "output_cost_per_token": 1e-05,
                "supports_function_calling": true
              }
            }
            """;
        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");
        assertThat(result.models()).isEmpty();
        assertThat(result.rejectedSlash()).isEqualTo(1);
    }

    @Test
    @DisplayName("Dated alias is dropped when canonical twin exists; dated-only rows are kept")
    void dedupsDatedAliases() {
        String fixture = """
            {
              "claude-opus-4-7": {
                "litellm_provider": "anthropic", "mode": "chat",
                "input_cost_per_token": 5e-06, "output_cost_per_token": 2.5e-05,
                "supports_function_calling": true
              },
              "claude-opus-4-7-20260416": {
                "litellm_provider": "anthropic", "mode": "chat",
                "input_cost_per_token": 5e-06, "output_cost_per_token": 2.5e-05,
                "supports_function_calling": true
              },
              "gpt-4-0613": {
                "litellm_provider": "openai", "mode": "chat",
                "input_cost_per_token": 3e-05, "output_cost_per_token": 6e-05,
                "supports_function_calling": true
              }
            }
            """;
        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");

        assertThat(result.models()).hasSize(2);
        assertThat(result.models()).extracting(m -> m.get("modelId"))
                .containsExactlyInAnyOrder("claude-opus-4-7", "gpt-4-0613");
        // gpt-4-0613 has a date-like suffix but NO canonical twin in the feed
        // (gpt-4 doesn't appear), so it stays.
    }

    @Test
    @DisplayName("Meta blocks without litellm_provider are rejected, not crashed on (fallback_generalizations regression)")
    void metaEntryWithoutProviderDoesNotCrash() {
        // Regression: LiteLLM's live feed carries non-model meta blocks with no
        // litellm_provider (e.g. "fallback_generalizations", a set of
        // adaptive-thinking inference rules). PROVIDER_MAP is an immutable
        // Map.ofEntries whose get(null) throws NPE, which previously aborted the
        // ENTIRE sync ("sync failed: Cannot invoke Object.hashCode() ...") on the
        // first refresh after that upstream entry appeared. The parser must route
        // such entries through the reject path and still parse real models.
        String fixture = """
            {
              "fallback_generalizations": {
                "rules": [
                  {"name": "anthropic-claude", "pattern": "^claude-.*$",
                   "model_info": {"supports_adaptive_thinking": true}}
                ]
              },
              "claude-opus-4-8": {
                "litellm_provider": "anthropic", "mode": "chat",
                "input_cost_per_token": 5e-06, "output_cost_per_token": 2.5e-05,
                "supports_function_calling": true
              }
            }
            """;

        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");

        assertThat(result.isSuccess()).isTrue();
        // The meta block is counted as a rejected-provider entry, not a crash.
        assertThat(result.rejectedProvider()).isEqualTo(1);
        // The real model still parses.
        assertThat(result.models()).hasSize(1);
        assertThat(result.models().get(0).get("modelId")).isEqualTo("claude-opus-4-8");
        assertThat(result.models().get(0).get("provider")).isEqualTo("anthropic");
    }

    @Test
    @DisplayName("Explicit null litellm_provider is rejected, not crashed on")
    void nullProviderValueDoesNotCrash() {
        // Same class of bug via an explicit JSON null rather than a missing key.
        String fixture = """
            {
              "some-weird-row": {
                "litellm_provider": null, "mode": "chat",
                "input_cost_per_token": 1e-06, "output_cost_per_token": 1e-05,
                "supports_function_calling": true
              }
            }
            """;
        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.models()).isEmpty();
        assertThat(result.rejectedProvider()).isEqualTo(1);
    }

    @Test
    @DisplayName("Invalid JSON → isSuccess=false, empty models, error message populated")
    void malformedJson() {
        LiteLlmFeedParser.ParseResult result = parser.parse(
                "{ not json".getBytes(StandardCharsets.UTF_8), "sha", "t");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorMessage()).isNotBlank();
        assertThat(result.models()).isEmpty();
    }

    private static Map<String, Object> findByModelId(List<Map<String, Object>> models, String id) {
        return models.stream()
                .filter(m -> id.equals(m.get("modelId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("not found: " + id));
    }
}
