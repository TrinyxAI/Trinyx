package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.service.execution.AsyncPollExecutor;
import com.apimarketplace.catalog.tools.generation.GenerationToolsProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The budgets a generation passes through have to be ordered, and the ordering
 * is the whole point: a caller that gives up first does NOT cancel the
 * generation. Catalog finishes it, stores the asset and commits the
 * reservation, while the caller reports that the generation service could not
 * be reached. The customer is charged for something they were told they did not
 * get, and nothing releases it, because from catalog's side nothing failed.
 *
 * <p>Lives in {@code com.apimarketplace.catalog.service} so it can read
 * {@link ToolExecutionManager#RESERVE_TTL_MINUTES} directly. Every number here
 * is read from where it is defined, never re-declared: a budget test written
 * against copies of the values it guards agrees with itself while the system
 * drifts, which is the failure it exists to prevent.
 */
class GenerationTimeoutBudgetTest {

    @Test
    @DisplayName("a caller waits at least as long as catalog can legitimately take, or it abandons a call it will be billed for")
    void theCallerBudgetCoversTheServerWorstCase() {
        assertThat(ToolExecutionManager.GENERATION_CALLER_BUDGET_MS)
                .isGreaterThanOrEqualTo(ToolExecutionManager.GENERATION_WORST_CASE_MS);
    }

    @Test
    @DisplayName("the worst case is submit PLUS poll, not whichever of the two is larger")
    void theWorstCaseAddsBothCeilings() {
        // They happen in sequence on one call: a synchronous submit that can
        // burn the upstream read timeout, and then the poll loop. Sizing on the
        // larger of the two silently halves the budget.
        assertThat(ToolExecutionManager.GENERATION_WORST_CASE_MS)
                .isEqualTo(ToolExecutionManager.UPSTREAM_READ_CEILING_MS
                        + AsyncPollExecutor.ABSOLUTE_MAX_WAIT_MS);
    }

    @Test
    @DisplayName("the reservation outlives the longest a caller can wait, so the commit still finds something to commit")
    void theReservationOutlivesTheCallerBudget() {
        long reserveTtlMs = ToolExecutionManager.RESERVE_TTL_MINUTES * 60L * 1000L;
        assertThat(reserveTtlMs).isGreaterThan(ToolExecutionManager.GENERATION_CALLER_BUDGET_MS);
    }

    @Test
    @DisplayName("the generation tool's own budget is the caller budget, not a round number beside it")
    void theToolAdvertisesTheCallerBudget() {
        // Read off the built tool definition rather than the constant, because
        // the defect was precisely that the definition carried its own literal.
        var tool = new GenerationToolsProvider(null).getTools().get(0);
        assertThat(tool.timeoutMs())
                .isEqualTo(ToolExecutionManager.GENERATION_CALLER_BUDGET_MS);
    }

    @Test
    @DisplayName("the upstream read ceiling matches what this service is actually configured to wait")
    void theCeilingMatchesTheConfiguredReadTimeout() {
        // UPSTREAM_READ_CEILING_MS is half of the worst case, and it is a copy
        // of a YAML value: the code default in DataConfig is a much smaller
        // 30000, so nothing but this assertion connects the constant to the
        // number the service really runs with. Raise http.client.read-timeout
        // without raising the constant and every budget derived from it becomes
        // too short, silently.
        assertThat(configuredReadTimeoutMs())
                .as("http.client.read-timeout in application.yml")
                .isEqualTo(ToolExecutionManager.UPSTREAM_READ_CEILING_MS);
    }

    @SuppressWarnings("unchecked")
    private static long configuredReadTimeoutMs() {
        try (InputStream in = GenerationTimeoutBudgetTest.class
                .getClassLoader().getResourceAsStream("application.yml")) {
            assertThat(in).as("application.yml on the classpath").isNotNull();
            // The file is a multi-document YAML (Spring profiles); the base
            // document is the one that carries http.client.
            for (Object document : new Yaml().loadAll(in)) {
                Object http = ((Map<String, Object>) document).get("http");
                if (http == null) continue;
                Object client = ((Map<String, Object>) http).get("client");
                if (client == null) continue;
                Object readTimeout = ((Map<String, Object>) client).get("read-timeout");
                if (readTimeout != null) {
                    return ((Number) readTimeout).longValue();
                }
            }
        } catch (Exception e) {
            throw new AssertionError("could not read http.client.read-timeout", e);
        }
        throw new AssertionError("http.client.read-timeout is not set in application.yml");
    }
}
