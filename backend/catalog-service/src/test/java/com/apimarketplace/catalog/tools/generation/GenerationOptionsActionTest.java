package com.apimarketplace.catalog.tools.generation;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.catalog.service.generation.DynamicOptionsResolver;
import com.apimarketplace.catalog.service.generation.GenerationAssetResolver;
import com.apimarketplace.catalog.service.generation.GenerationInputResolver;
import com.apimarketplace.catalog.service.generation.GenerationRegistry;
import com.apimarketplace.catalog.service.generation.GenerationSpec;
import com.apimarketplace.catalog.tools.CatalogExecuteModule;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.storage.client.StorageClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code generation(action='options')}: what an AGENT is told about the values
 * only a provider can name.
 *
 * <p>The thing under test is not the fetching (that is the resolver's) but the
 * CONVERSATION: an agent cannot see a settings page, so every refusal has to say
 * what it can do next, the unified name it knows ('voice') has to be translated
 * to the provider's own ('voice_id') on its behalf, and the answer has to name
 * the key it came from, because a voice read on one key and a run dispatched on
 * another spends money on an id the provider never had.
 */
class GenerationOptionsActionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID TOOL_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID SOURCE_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private GenerationRegistry registry;
    private CatalogExecuteModule executeModule;
    private DynamicOptionsResolver optionsResolver;
    private GenerationToolsProvider provider;

    /** A TTS model whose unified 'voice' writes to the provider's 'voice_id'. */
    private static final GenerationSpec VOICE_SPEC = spec("""
            {
              "kind": "voice", "assetPath": "$binary",
              "paramMap": { "prompt": "text", "voice": "voice_id" },
              "models": [{
                "id": "tts-fast", "label": "TTS Fast",
                "capabilities": ["prompt", "voice"],
                "price": { "unit": "character", "unitCredits": 0.2 }
              }]
            }
            """);

    private static final GenerationRegistry.GenerationModel VOICE = new GenerationRegistry.GenerationModel(
            "tts-fast", "voice", VOICE_SPEC.model("tts-fast").orElseThrow(), VOICE_SPEC,
            TOOL_ID, "vendor/tts", "vendor", "Vendor Audio", "vendor", "vendor_key", "sync");

    private static GenerationSpec spec(String json) {
        try {
            return GenerationSpec.parse(MAPPER.readTree(json), "test").orElseThrow();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void setUp() {
        registry = mock(GenerationRegistry.class);
        executeModule = mock(CatalogExecuteModule.class);
        optionsResolver = mock(DynamicOptionsResolver.class);
        GenerationAssetResolver assetResolver = mock(GenerationAssetResolver.class);
        provider = new GenerationToolsProvider(new GenerationModule(
                registry, executeModule, assetResolver,
                new com.apimarketplace.catalog.service.ResponseShaper(),
                new GenerationInputResolver(mock(StorageClient.class), 20_971_520L),
                optionsResolver,
                mock(com.apimarketplace.catalog.service.generation.PlatformSalesResolver.class),
                mock(InterfaceClient.class),
                new com.apimarketplace.catalog.service.generation.GenerationProvenanceRecorder()));
    }

    private ToolExecutionResult options(Map<String, Object> params) {
        return provider.execute("generation",
                merge(Map.of("action", "options"), params), context());
    }

    private static ToolExecutionContext context() {
        return new ToolExecutionContext(
                "tenant-1", Map.of(), Map.of(), Set.of(), null, null, null, null);
    }

    private static Map<String, Object> merge(Map<String, Object> a, Map<String, Object> b) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>(a);
        out.putAll(b);
        return out;
    }

    private static DynamicOptionsResolver.Resolution available(String source,
                                                               DynamicOptionsResolver.Option... options) {
        return new DynamicOptionsResolver.Resolution(List.of(options), false, options.length, source, null);
    }

    // ── the happy path ──────────────────────────────────────────────────────

    @Test
    @DisplayName("answers with value/label pairs for the parameter the agent named")
    void listsValuesWithLabels() {
        when(registry.resolve("tts-fast")).thenReturn(Optional.of(VOICE));
        when(optionsResolver.resolve(any(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(available("user",
                        new DynamicOptionsResolver.Option("21m00", "Rachel"),
                        new DynamicOptionsResolver.Option("AZnzlk", "Domi")));

        ToolExecutionResult result = options(Map.of("model", "tts-fast", "parameter", "voice"));

        assertThat(result.success()).isTrue();
        Map<String, Object> data = asMap(result.data());
        assertThat(data.get("model")).isEqualTo("tts-fast");
        assertThat(data.get("parameter")).isEqualTo("voice");
        assertThat(data.get("count")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("options");
        // A voice id is twenty opaque characters. Without the label an agent has
        // nothing to match a person's "use Rachel" against.
        assertThat(rows.get(0)).containsEntry("value", "21m00").containsEntry("label", "Rachel");
    }

    @Test
    @DisplayName("the UNIFIED name is translated to the provider's own, so the agent never learns both")
    void translatesTheUnifiedName() {
        when(registry.resolve("tts-fast")).thenReturn(Optional.of(VOICE));
        when(optionsResolver.resolve(any(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(available("user", new DynamicOptionsResolver.Option("21m00", "Rachel")));

        options(Map.of("model", "tts-fast", "parameter", "voice"));

        ArgumentCaptor<String> parameter = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(optionsResolver).resolve(
                eq(TOOL_ID), parameter.capture(), eq("tenant-1"), any(), any(), any(), any());
        // The agent said 'voice' (what action='create' takes); the catalogue
        // knows 'voice_id'.
        assertThat(parameter.getValue()).isEqualTo("voice_id");
    }

    @Test
    @DisplayName("the answer names WHICH key replied, so the list and a later run cannot diverge")
    void reportsTheCredentialSource() {
        when(registry.resolve("tts-fast")).thenReturn(Optional.of(VOICE));
        when(optionsResolver.resolve(any(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(available("platform", new DynamicOptionsResolver.Option("21m00", "Rachel")));

        Map<String, Object> data = asMap(options(
                Map.of("model", "tts-fast", "parameter", "voice")).data());

        assertThat(data.get("credential_source")).isEqualTo("platform");
        // And the note has to tell the agent to pass the SAME source to create,
        // which is the only thing that keeps the two in step.
        assertThat(String.valueOf(data.get("note"))).contains("credential_source");
    }

    @Test
    @DisplayName("the caller's chosen payer reaches the resolver, because the answer depends on it")
    void forwardsTheRequestedCredentialSource() {
        when(registry.resolve("tts-fast")).thenReturn(Optional.of(VOICE));
        when(optionsResolver.resolve(any(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(available("platform", new DynamicOptionsResolver.Option("v", "V")));

        options(Map.of("model", "tts-fast", "parameter", "voice", "credential_source", "platform"));

        org.mockito.Mockito.verify(optionsResolver).resolve(
                eq(TOOL_ID), eq("voice_id"), eq("tenant-1"), eq("platform"), any(), any(), any());
    }

    @Test
    @DisplayName("a truncated list says so and gives the real total, so nothing reads as exhaustive")
    void truncationIsStated() {
        when(registry.resolve("tts-fast")).thenReturn(Optional.of(VOICE));
        when(optionsResolver.resolve(any(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(new DynamicOptionsResolver.Resolution(
                        List.of(new DynamicOptionsResolver.Option("21m00", "Rachel")),
                        true, 812, "user", null));

        Map<String, Object> data = asMap(options(
                Map.of("model", "tts-fast", "parameter", "voice")).data());

        assertThat(data.get("truncated")).isEqualTo(true);
        assertThat(data.get("total_count")).isEqualTo(812);
    }

    @Test
    @DisplayName("a whole list does NOT claim truncation, which would send an agent hunting for more")
    void completeListSaysNothingAboutTruncation() {
        when(registry.resolve("tts-fast")).thenReturn(Optional.of(VOICE));
        when(optionsResolver.resolve(any(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(available("user", new DynamicOptionsResolver.Option("21m00", "Rachel")));

        Map<String, Object> data = asMap(options(
                Map.of("model", "tts-fast", "parameter", "voice")).data());

        assertThat(data).doesNotContainKeys("truncated", "total_count");
    }

    // ── the refusals, which are the point ───────────────────────────────────

    @Test
    @DisplayName("a missing parameter is refused, and the message names the action that lists them")
    void missingParameterIsRefused() {
        ToolExecutionResult result = options(Map.of("model", "tts-fast"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        assertThat(result.error()).contains("optionsAvailable");
        // Nothing may be called to find out an argument is missing.
        verifyNoInteractions(optionsResolver, executeModule);
    }

    @Test
    @DisplayName("an unknown model is refused without asking anyone")
    void unknownModelIsRefused() {
        when(registry.resolve("nope")).thenReturn(Optional.empty());

        ToolExecutionResult result = options(Map.of("model", "nope", "parameter", "voice"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("nope").contains("models");
        verifyNoInteractions(optionsResolver, executeModule);
    }

    @Test
    @DisplayName("a parameter the model does not accept is refused before any provider call")
    void unknownParameterIsRefused() {
        when(registry.resolve("tts-fast")).thenReturn(Optional.of(VOICE));

        ToolExecutionResult result = options(Map.of("model", "tts-fast", "parameter", "seed"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("does not accept").contains("seed");
        verifyNoInteractions(optionsResolver, executeModule);
    }

    @Test
    @DisplayName("no key connected says WHOSE act connecting one is, and not to retry")
    void noCredentialExplainsTheNextMove() {
        when(registry.resolve("tts-fast")).thenReturn(Optional.of(VOICE));
        when(optionsResolver.resolve(any(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(unavailable(DynamicOptionsResolver.Unavailable.NO_CREDENTIAL));

        ToolExecutionResult result = options(Map.of("model", "tts-fast", "parameter", "voice"));

        assertThat(result.success()).isFalse();
        // An agent that reads this as a transient fault retries in a loop; it
        // has to be told the resolution belongs to the account owner.
        assertThat(result.error()).contains("Vendor Audio").contains("owner");
    }

    @Test
    @DisplayName("a failed fetch says nothing was charged, and to ask rather than try identifiers")
    void fetchFailureSteersAwayFromGuessing() {
        when(registry.resolve("tts-fast")).thenReturn(Optional.of(VOICE));
        when(optionsResolver.resolve(any(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(unavailable(DynamicOptionsResolver.Unavailable.FETCH_FAILED));

        ToolExecutionResult result = options(Map.of("model", "tts-fast", "parameter", "voice"));

        assertThat(result.success()).isFalse();
        // Guessing is the expensive failure: an invented id is refused by the
        // provider AFTER the generation is paid for.
        assertThat(result.error()).contains("Nothing was charged").contains("Ask the person");
    }

    @Test
    @DisplayName("a parameter with no list to fetch says so rather than implying a lookup failed")
    void notDynamicIsNotAFailure() {
        when(registry.resolve("tts-fast")).thenReturn(Optional.of(VOICE));
        when(optionsResolver.resolve(any(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(unavailable(DynamicOptionsResolver.Unavailable.NOT_DYNAMIC));

        ToolExecutionResult result = options(Map.of("model", "tts-fast", "parameter", "voice"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("no list to fetch");
    }

    // ── what action='models' advertises ─────────────────────────────────────

    @Test
    @DisplayName("models marks the parameters worth asking about, without calling any provider")
    void modelsAdvertisesWhichParametersCanBeAsked() {
        when(registry.list(null)).thenReturn(List.of(VOICE));
        when(optionsResolver.unifiedDynamicParameters(VOICE)).thenReturn(Set.of("voice"));

        ToolExecutionResult result = provider.execute("generation", Map.of("action", "models"), context());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) asMap(result.data()).get("models");
        Map<String, Object> limits = asMap(rows.get(0).get("limits"));
        // Without this an agent has no way to know that asking would answer, and
        // invents an opaque identifier instead.
        assertThat(asMap(limits.get("voice"))).containsEntry("optionsAvailable", true);
        // And it is read from catalogue rows: listing models must not cost one
        // provider call per model.
        verifyNoInteractions(executeModule);
    }

    private static DynamicOptionsResolver.Resolution unavailable(DynamicOptionsResolver.Unavailable reason) {
        return new DynamicOptionsResolver.Resolution(List.of(), false, 0, null, reason);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    // ── how the source endpoint is actually run ─────────────────────────────

    /**
     * The glue between the action and the catalog's execution path.
     *
     * <p>Untested, this is the riskiest part of the feature: it decides WHICH
     * key answers, whether a missing key reads as a refusal or as an empty
     * account, and whether opening a dropdown can be charged to the turn the
     * caller is inside. The resolver is stubbed to invoke the fetcher it is
     * handed, so these exercise the real lambda rather than a mock of it.
     */
    @Nested
    @DisplayName("fetching the source endpoint")
    class Fetching {

        /** Runs the fetcher the module passes in, and reports what it answered. */
        private DynamicOptionsResolver.SourceFetcher.Answer fetchWith(
                ToolExecutionResult executeAnswer, ToolExecutionContext callerContext) {
            when(registry.resolve("tts-fast")).thenReturn(Optional.of(VOICE));
            when(executeModule.execute(eq("execute"), any(), anyString(), any()))
                    .thenReturn(Optional.ofNullable(executeAnswer));

            java.util.concurrent.atomic.AtomicReference<DynamicOptionsResolver.SourceFetcher.Answer> seen =
                    new java.util.concurrent.atomic.AtomicReference<>();
            when(optionsResolver.resolve(any(), anyString(), anyString(), any(), any(), any(), any()))
                    .thenAnswer(invocation -> {
                        DynamicOptionsResolver.SourceFetcher fetcher = invocation.getArgument(6);
                        seen.set(fetcher.fetch(SOURCE_ID, "user", null));
                        return available("user", new DynamicOptionsResolver.Option("v", "V"));
                    });

            provider.execute("generation",
                    merge(Map.of("action", "options"),
                            Map.of("model", "tts-fast", "parameter", "voice")),
                    callerContext);
            return seen.get();
        }

        @Test
        @DisplayName("hands back the endpoint's answer and the pool that actually replied")
        void reportsTheAnswerAndThePool() {
            ToolExecutionResult ok = ToolExecutionResult.success(Map.of(
                    "result", Map.of("voices", List.of(Map.of("voice_id", "21m00"))),
                    "metadata", Map.of("credentialSource", "platform")));

            DynamicOptionsResolver.SourceFetcher.Answer answer = fetchWith(ok, context());

            assertThat(answer.success()).isTrue();
            // The pool is read from the ANSWER, not from the request: the
            // execution path can fall back from the caller's key to the
            // platform's, and only the answer knows which one ran.
            assertThat(answer.credentialSource()).isEqualTo("platform");
            assertThat(answer.body()).isInstanceOf(Map.class);
        }

        @Test
        @DisplayName("a refusal naming credentials_required is a MISSING KEY, not a fault")
        void credentialsRequiredIsReportedAsMissing() {
            ToolExecutionResult refused = ToolExecutionResult.failure(
                    ToolErrorCode.EXECUTION_FAILED,
                    "something " + CatalogExecuteModule.CREDENTIALS_REQUIRED_CODE + " here");

            DynamicOptionsResolver.SourceFetcher.Answer answer = fetchWith(refused, context());

            assertThat(answer.success()).isFalse();
            // The two get opposite messages: one says connect a key, the other
            // says the provider could not be reached.
            assertThat(answer.credentialsMissing()).isTrue();
        }

        @Test
        @DisplayName("any other refusal is a fetch failure, so nobody is told to connect a key they have")
        void otherRefusalsAreNotMissingKeys() {
            DynamicOptionsResolver.SourceFetcher.Answer answer = fetchWith(
                    ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "upstream 503"),
                    context());

            assertThat(answer.success()).isFalse();
            assertThat(answer.credentialsMissing()).isFalse();
        }

        @Test
        @DisplayName("no module answer at all is a failure rather than an empty account")
        void anAbsentAnswerIsAFailure() {
            DynamicOptionsResolver.SourceFetcher.Answer answer = fetchWith(null, context());

            assertThat(answer.success()).isFalse();
        }

        @Test
        @DisplayName("regression: a SUCCESS that is really an approval prompt is a missing key")
        void anApprovalPromptIsNotAnAnswer() {
            // The subtlest of the four. The credential pre-flight answers with a
            // success whose payload is a prompt rather than the endpoint's
            // reply: nothing ran. Read as a success, the walk finds no values
            // and the reader is told their account holds none, when in truth
            // nobody was ever asked.
            ToolExecutionResult prompt = ToolExecutionResult.success(Map.of(
                    "status", "approval_needed",
                    "approval_needed", true,
                    "integration_name", "vendor"));

            DynamicOptionsResolver.SourceFetcher.Answer answer = fetchWith(prompt, context());

            assertThat(answer.success()).isFalse();
            assertThat(answer.credentialsMissing()).isTrue();
        }

        @Test
        @DisplayName("regression: a dropdown is never charged to the turn or run the caller is inside")
        void theFetchCarriesNoBillingScope() {
            // The context of an agent working inside a chat or a workflow. Passed
            // through whole, the delegated execute turns either id into a billing
            // scope and reserves against it: opening a field would bill the turn,
            // and an agent asking in a loop would bill a loop.
            ToolExecutionContext inAChat = new ToolExecutionContext(
                    "tenant-1",
                    Map.of("__streamId__", "stream-1",
                            "__workflowRunId__", "run-1",
                            "__nodeId__", "node-1",
                            CatalogExecuteModule.CREDENTIAL_ID_CONTEXT_KEY, "42"),
                    Map.of(), Set.of(), null, null, null, null);

            fetchWith(ToolExecutionResult.success(Map.of("result", Map.of())), inAChat);

            ArgumentCaptor<ToolExecutionContext> passed =
                    ArgumentCaptor.forClass(ToolExecutionContext.class);
            org.mockito.Mockito.verify(executeModule)
                    .execute(eq("execute"), any(), anyString(), passed.capture());
            assertThat(passed.getValue().credentials())
                    .doesNotContainKeys("__streamId__", "__workflowRunId__", "__nodeId__")
                    // The KEY stays: the answer depends on which one is asked.
                    .containsEntry(CatalogExecuteModule.CREDENTIAL_ID_CONTEXT_KEY, "42");
        }

        @Test
        @DisplayName("regression: the pinned key reaches the cache key, so two keys are two answers")
        void thePinnedKeyIsPartOfTheQuestion() {
            when(registry.resolve("tts-fast")).thenReturn(Optional.of(VOICE));
            when(optionsResolver.resolve(any(), anyString(), anyString(), any(), any(), any(), any()))
                    .thenReturn(available("user", new DynamicOptionsResolver.Option("v", "V")));
            ToolExecutionContext pinned = new ToolExecutionContext(
                    "tenant-1", Map.of(CatalogExecuteModule.CREDENTIAL_ID_CONTEXT_KEY, "42"),
                    Map.of(), Set.of(), null, null, null, null);

            provider.execute("generation",
                    merge(Map.of("action", "options"),
                            Map.of("model", "tts-fast", "parameter", "voice")),
                    pinned);

            // Left out, one key's voices are served under another's for five
            // minutes: the reader picks a voice key B has never had, and the
            // run fails after it is billed.
            org.mockito.Mockito.verify(optionsResolver).resolve(
                    eq(TOOL_ID), eq("voice_id"), eq("tenant-1"), any(), eq("42"), any(), any());
        }
    }
}
