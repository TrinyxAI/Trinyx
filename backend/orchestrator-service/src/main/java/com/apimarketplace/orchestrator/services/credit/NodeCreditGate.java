package com.apimarketplace.orchestrator.services.credit;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Pre-execution credit gate applied to EVERY node, trigger nodes included.
 *
 * <p>A tenant whose balance is exhausted must see WHERE the workflow stopped, not
 * silence. Denying here (instead of before the epoch opens) means the node is
 * persisted FAILED with {@link CreditExhaustion#MESSAGE} and the engine's ordinary
 * failure cascade marks every downstream node SKIPPED. For a trigger node that
 * renders as "trigger failed, rest of the workflow skipped" in the run view, for
 * every trigger type, including the unattended ones (schedule, webhook, datasource)
 * where nobody is watching an HTTP response.
 *
 * <p>The run itself stays reusable: a failed epoch on a reusable trigger resets the
 * run to {@code WAITING_TRIGGER}, so topping up credits revives the workflow with no
 * further action.
 *
 * <p>Cost: {@link CreditConsumptionClient#checkCredits(String)} caches per tenant for
 * 30 s, so a whole epoch normally costs at most one auth-service round-trip.
 * Fail-closed semantics are inherited from that client (auth-service unreachable with
 * no cached answer denies), and {@code enabled=false} (CE without billing) always
 * allows.
 */
@Service
public class NodeCreditGate {

    private static final Logger logger = LoggerFactory.getLogger(NodeCreditGate.class);

    private final CreditConsumptionClient creditClient;

    public NodeCreditGate(CreditConsumptionClient creditClient) {
        this.creditClient = creditClient;
    }

    /**
     * @param tenantId the run owner - a blank tenant (internal/system execution) is
     *                 never gated, matching {@code CreditConsumptionClient}'s own
     *                 contract for a missing user id.
     * @return a FAILED {@link NodeExecutionResult} carrying the credit-exhaustion
     *         message and {@code output.error_code=CREDIT_EXHAUSTED}, or {@code null}
     *         when the node may execute.
     */
    public NodeExecutionResult denyOrNull(String nodeId, String tenantId) {
        if (creditClient == null || nodeId == null || tenantId == null || tenantId.isBlank()) {
            return null;
        }
        if (creditClient.checkCredits(tenantId)) {
            return null;
        }
        logger.warn("[CreditGate] Out of credits for tenant {} - failing node {}", tenantId, nodeId);
        return exhaustedResult(nodeId);
    }

    /**
     * The canonical out-of-credit node result. Exposed so the engine's local
     * budget-mirror denial reports the SAME message and {@code error_code} as this
     * gate - one wording for the user, one token for the 402 mapping.
     */
    public static NodeExecutionResult exhaustedResult(String nodeId) {
        return NodeExecutionResult.failureWithOutput(
            nodeId,
            CreditExhaustion.MESSAGE,
            Map.of("error_code", CreditExhaustion.ERROR_CODE),
            0);
    }
}
