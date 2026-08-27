package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.PublicationReviewRepository;
import com.apimarketplace.publication.repository.PublicationSnapshotVersionRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the generalized inline-secret scrub on the workflow plan snapshot.
 *
 * <p>Before the fix, {@code scrubRecursivelyForCredentials} only redacted values
 * under credential-shaped KEY names (apiKey, password, …) plus two hand-picked
 * fields (a code node's {@code code} and an RSS {@code url}). A literal key pasted
 * into a benignly-named field - a custom HTTP header value, a transform note, a
 * free-form param - survived into the published snapshot and shipped to every
 * acquirer. The agent-snapshot path already redacted such leaves; this restores
 * symmetry so a secret cannot leak through the workflow path.
 *
 * <p>Each assertion targets the value the acquirer would receive after publish.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPublicationService.stripSensitiveCredentials - inline-secret scrub on every string leaf")
class WorkflowPublicationServiceCredentialScrubTest {

    @Mock private WorkflowPublicationRepository publicationRepository;
    @Mock private PublicationSnapshotVersionRepository snapshotVersionRepository;
    @Mock private PublicationReceiptRepository receiptRepository;
    @Mock private PublicationReviewRepository reviewRepository;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private AgentClient agentClient;
    @Mock private InterfaceClient interfaceClient;
    @Mock private DataSourceClient dataSourceClient;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private SnapshotCloneService snapshotCloneService;
    @Mock private EntitlementGuard entitlementGuard;
    @Mock private AuthClient authClient;

    private WorkflowPublicationService service;

    private static final String REDACTED = "[redacted]";

    @BeforeEach
    void setUp() {
        service = new WorkflowPublicationService(
                publicationRepository, snapshotVersionRepository, receiptRepository,
                reviewRepository, orchestratorClient, agentClient, interfaceClient,
                dataSourceClient, breakdownService, new ObjectMapper(),
                snapshotCloneService, entitlementGuard, authClient);
    }

    @Test
    @DisplayName("Inlined secret under a benign key (custom header value) is redacted")
    void redactsInlineSecretUnderBenignKey() {
        Map<String, Object> core = new HashMap<>();
        core.put("id", "c1");
        core.put("type", "transform");
        // Benign key name the key-detector cannot flag; value carries a Stripe-shaped key.
        core.put("comment", "Use sk-live-ABCDEFGHIJ1234567890 to call the API");

        Map<String, Object> plan = new HashMap<>();
        plan.put("cores", new ArrayList<>(List.of(core)));

        service.stripSensitiveCredentials(plan);

        String scrubbed = (String) core.get("comment");
        assertThat(scrubbed).doesNotContain("sk-live-ABCDEFGHIJ1234567890");
        assertThat(scrubbed).contains(REDACTED);
    }

    @Test
    @DisplayName("Inlined secret inside a list of strings is redacted element-wise")
    void redactsInlineSecretInsideStringList() {
        Map<String, Object> mcp = new HashMap<>();
        mcp.put("id", "m1");
        // Raw header list: first element holds a GitHub PAT, second is innocuous.
        List<Object> headers = new ArrayList<>(List.of(
                "Authorization: Bearer ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789",
                "Accept: application/json"));
        mcp.put("rawHeaders", headers);

        Map<String, Object> plan = new HashMap<>();
        plan.put("mcps", new ArrayList<>(List.of(mcp)));

        service.stripSensitiveCredentials(plan);

        assertThat(headers.get(0).toString()).doesNotContain("ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789");
        assertThat(headers.get(0).toString()).contains(REDACTED);
        assertThat(headers.get(1)).isEqualTo("Accept: application/json");
    }

    @Test
    @DisplayName("Credential-shaped key still redacts its whole value (unchanged behavior)")
    void redactsCredentialShapedKey() {
        Map<String, Object> node = new HashMap<>();
        node.put("apiKey", "literal-value-that-is-secret");

        Map<String, Object> plan = new HashMap<>();
        plan.put("cores", new ArrayList<>(List.of(node)));

        service.stripSensitiveCredentials(plan);

        assertThat(node.get("apiKey")).isEqualTo(REDACTED);
    }

    @Test
    @DisplayName("Benign text without a secret pattern is left untouched (no false positive)")
    void leavesCleanTextUntouched() {
        Map<String, Object> node = new HashMap<>();
        node.put("title", "A Friendly Marketplace Workflow");
        node.put("description", "Summarizes incoming emails every morning.");

        Map<String, Object> plan = new HashMap<>();
        plan.put("cores", new ArrayList<>(List.of(node)));

        service.stripSensitiveCredentials(plan);

        assertThat(node.get("title")).isEqualTo("A Friendly Marketplace Workflow");
        assertThat(node.get("description")).isEqualTo("Summarizes incoming emails every morning.");
    }

    @Test
    @DisplayName("Prose mentioning secret-words without a key:value shape is NOT over-redacted")
    void doesNotOverRedactNaturalLanguage() {
        Map<String, Object> node = new HashMap<>();
        // Mentions password/token/secret/api key as plain words - none in a
        // `key: value` assignment, so the labeled-secret pattern must not fire.
        String prose = "Reset your password in account settings, then rotate your api key and token regularly.";
        node.put("description", prose);

        Map<String, Object> plan = new HashMap<>();
        plan.put("cores", new ArrayList<>(List.of(node)));

        service.stripSensitiveCredentials(plan);

        assertThat(node.get("description")).isEqualTo(prose);
    }

    @Test
    @DisplayName("Credential-shaped key whose value is a Map collapses the whole value to the sentinel")
    void redactsCredentialShapedKeyWithMapValue() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("token", "sk-live-ABCDEFGHIJ1234567890");
        Map<String, Object> node = new HashMap<>();
        node.put("authorization", nested); // credential-shaped key holding a sub-object

        Map<String, Object> plan = new HashMap<>();
        plan.put("cores", new ArrayList<>(List.of(node)));

        service.stripSensitiveCredentials(plan);

        assertThat(node.get("authorization")).isEqualTo(REDACTED);
    }

    @Test
    @DisplayName("Inlined secret nested several benign maps deep is still redacted")
    void redactsDeeplyNestedInlineSecret() {
        Map<String, Object> options = new HashMap<>();
        options.put("note", "deploy key AKIAIOSFODNN7EXAMPLE rotated weekly");
        Map<String, Object> config = new HashMap<>();
        config.put("options", options);
        Map<String, Object> node = new HashMap<>();
        node.put("config", config);

        Map<String, Object> plan = new HashMap<>();
        plan.put("cores", new ArrayList<>(List.of(node)));

        service.stripSensitiveCredentials(plan);

        String scrubbed = (String) options.get("note");
        assertThat(scrubbed).doesNotContain("AKIAIOSFODNN7EXAMPLE");
        assertThat(scrubbed).contains(REDACTED);
    }

    @Test
    @DisplayName("Webhook trigger secrets and step platformCredentialId are dropped from the snapshot")
    void dropsTriggerSecretsAndStepCredentialId() {
        Map<String, Object> params = new HashMap<>();
        params.put("basicPassword", "hunter2hunter2");
        params.put("jwtSecretKey", "supersecretjwtkey");
        params.put("url", "https://hooks.example.com/in");
        Map<String, Object> trigger = new HashMap<>();
        trigger.put("id", "wh");
        trigger.put("type", "webhook");
        trigger.put("params", params);

        Map<String, Object> mcp = new HashMap<>();
        mcp.put("id", "m1");
        mcp.put("platformCredentialId", "cred-1");
        mcp.put("credentialSource", "platform");
        // A run-time account selector is a credential choice too, even though it is
        // written as a NAME rather than an id. Left in, a published workflow naming
        // "Acme IG" resolves against the ACQUIRER workspace and runs on whichever
        // credential they happen to have under that name - an account nobody chose,
        // handed over by a name collision.
        mcp.put("credentialSelector", "Acme IG");
        mcp.put("name", "Keep me");

        Map<String, Object> plan = new HashMap<>();
        plan.put("triggers", new ArrayList<>(List.of(trigger)));
        plan.put("mcps", new ArrayList<>(List.of(mcp)));

        service.stripSensitiveCredentials(plan);

        assertThat(params).doesNotContainKeys("basicPassword", "jwtSecretKey");
        assertThat(params).containsEntry("url", "https://hooks.example.com/in");
        assertThat(mcp).doesNotContainKeys("platformCredentialId", "credentialSource");
        // BLANK, exactly. Not removed, because removing it drops the step back to the
        // acquirer default account and every run is green: this feature own headline
        // failure, relocated to the publish boundary. And not the recursive scrub
        // redaction placeholder either: a run reads that as a credential NAME, so the
        // acquirer would be told no credential is called "[redacted]" rather than that
        // they have not filled the field in. Pinning the exact value is the point -
        // asserting merely "something not the original" is what let the blanking sit
        // dead behind the scrub without anyone noticing.
        assertThat(mcp).containsEntry("credentialSelector", "");
        assertThat(mcp).containsEntry("name", "Keep me");
    }

    @Test
    @DisplayName("an explicit null selector survives publish as null, not as a blank")
    void publishLeavesAnExplicitNullAlone() {
        // The guard for this lived AFTER the recursive scrub, which overwrites every
        // value it matches by key name - so it read the placeholder, never null, and
        // could not fire. The acquirer then got a step in the dynamic-but-unfilled
        // state, refusing on every run, for a feature its author never used.
        Map<String, Object> mcp = new HashMap<>();
        mcp.put("id", "m1");
        mcp.put("credentialSelector", null);
        Map<String, Object> plan = new HashMap<>();
        plan.put("mcps", new ArrayList<>(List.of(mcp)));

        service.stripSensitiveCredentials(plan);

        assertThat(mcp.get("credentialSelector")).isNull();
    }

    @Test
    @DisplayName("a snake_case selector is neutered too, because the parser reads that spelling")
    void publishNeutersTheSnakeSpelling() {
        // A stored plan spelled credential_selector is a LIVE selector at run time.
        // Left alone, the acquirer inherits the publisher's expression.
        Map<String, Object> mcp = new HashMap<>();
        mcp.put("id", "m1");
        mcp.put("credential_selector", "Acme IG");
        Map<String, Object> plan = new HashMap<>();
        plan.put("mcps", new ArrayList<>(List.of(mcp)));

        service.stripSensitiveCredentials(plan);

        assertThat(mcp).containsEntry("credential_selector", "");
    }
}
