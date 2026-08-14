package com.apimarketplace.orchestrator.services.credit;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The out-of-credit gate that runs before every node body.
 *
 * <p>Regression cover for the bug this class was written for: a zero-balance tenant
 * was refused BEFORE the epoch opened, so an unattended workflow (schedule, webhook,
 * datasource) left no epoch, no step row and no error - it just silently stopped
 * running. Denying at node level turns that into a FAILED node the user can see.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NodeCreditGate")
class NodeCreditGateTest {

    private static final String NODE_ID = "trigger:daily";
    private static final String TENANT = "tenant-broke";

    @Mock private CreditConsumptionClient creditClient;

    @Test
    @DisplayName("Out of credits: denies with the shared message and the CREDIT_EXHAUSTED error code")
    void deniesWhenOutOfCredits() {
        when(creditClient.checkCredits(TENANT)).thenReturn(false);

        NodeExecutionResult denial = new NodeCreditGate(creditClient).denyOrNull(NODE_ID, TENANT);

        assertThat(denial).isNotNull();
        assertThat(denial.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(denial.nodeId()).isEqualTo(NODE_ID);
        assertThat(denial.errorMessage()).contains(CreditExhaustion.MESSAGE);
        // The error_code travels in the output so the frontend can recognise the
        // failure without string-matching a human sentence.
        assertThat(denial.output()).containsEntry("error_code", CreditExhaustion.ERROR_CODE);
    }

    @Test
    @DisplayName("Credits available: returns null so the node executes normally")
    void allowsWhenCreditsAvailable() {
        when(creditClient.checkCredits(TENANT)).thenReturn(true);

        assertThat(new NodeCreditGate(creditClient).denyOrNull(NODE_ID, TENANT)).isNull();
    }

    @Test
    @DisplayName("Blank tenant is never gated and never asks auth-service")
    void blankTenantIsNotGated() {
        NodeCreditGate gate = new NodeCreditGate(creditClient);

        assertThat(gate.denyOrNull(NODE_ID, null)).isNull();
        assertThat(gate.denyOrNull(NODE_ID, "  ")).isNull();
        verify(creditClient, never()).checkCredits(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("No credit client (CE without billing): never gated")
    void noClientIsNotGated() {
        assertThat(new NodeCreditGate(null).denyOrNull(NODE_ID, TENANT)).isNull();
    }

    @Test
    @DisplayName("Null nodeId is not gated - nothing to report the failure on")
    void nullNodeIdIsNotGated() {
        assertThat(new NodeCreditGate(creditClient).denyOrNull(null, TENANT)).isNull();
        verify(creditClient, never()).checkCredits(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("exhaustedResult() is the same shape as a gate denial (used by the local budget mirror)")
    void exhaustedResultMatchesTheGateDenial() {
        NodeExecutionResult result = NodeCreditGate.exhaustedResult(NODE_ID);

        assertThat(result.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(result.errorMessage()).contains(CreditExhaustion.MESSAGE);
        assertThat(result.output()).containsEntry("error_code", CreditExhaustion.ERROR_CODE);
        assertThat(CreditExhaustion.isCreditExhausted(result.errorMessage().orElse(null))).isTrue();
    }
}
