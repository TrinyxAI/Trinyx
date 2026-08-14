package com.apimarketplace.orchestrator.services.credit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CreditExhaustion#isCreditExhausted} is what lets a synchronous trigger
 * fire answer 402 (keeping the frontend's "Insufficient credits" modal) from a
 * failure that is now produced three layers down, inside node execution.
 */
@DisplayName("CreditExhaustion")
class CreditExhaustionTest {

    @Test
    @DisplayName("Recognises the gate's own message")
    void recognisesTheMessage() {
        assertThat(CreditExhaustion.isCreditExhausted(CreditExhaustion.MESSAGE)).isTrue();
    }

    @Test
    @DisplayName("Recognises a wrapped message - callers prefix the node error")
    void recognisesAWrappedMessage() {
        // ReusableTriggerService reports engine failures as "V2 execution failed: <error>".
        assertThat(CreditExhaustion.isCreditExhausted("V2 execution failed: " + CreditExhaustion.MESSAGE))
                .isTrue();
        // A caller that forwards the error_code instead of the sentence still matches.
        assertThat(CreditExhaustion.isCreditExhausted("node failed [" + CreditExhaustion.ERROR_CODE + "]"))
                .isTrue();
    }

    @Test
    @DisplayName("Any other failure is NOT a credit exhaustion - it must stay a 400")
    void otherFailuresAreNotCreditExhaustion() {
        assertThat(CreditExhaustion.isCreditExhausted("Run has no plan")).isFalse();
        assertThat(CreditExhaustion.isCreditExhausted("Trigger 'trigger:x' no longer exists in the workflow plan"))
                .isFalse();
        assertThat(CreditExhaustion.isCreditExhausted(null)).isFalse();
        assertThat(CreditExhaustion.isCreditExhausted("  ")).isFalse();
    }
}
