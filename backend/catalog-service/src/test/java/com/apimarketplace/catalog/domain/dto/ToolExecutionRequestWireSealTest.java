package com.apimarketplace.catalog.domain.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three fields that decide how much a generation costs must not be
 * settable by whoever posts the body.
 *
 * <p>They are populated by the controller from {@code X-Lc-Generation-*}
 * headers, which the gateway strips off every inbound request, so the only
 * writer is the platform itself. The seal that enforces this is a
 * {@code @JsonIgnore} on each field, and it is one annotation away from being
 * gone: the field's own javadoc calls it load-bearing, the controller's
 * {@code applyBillingHeaders} says outright that a body value wins over a
 * header, and nothing anywhere read these getters back. Removing all three
 * annotations changed no test.
 *
 * <p>What that would cost: a caller who can reach the execute endpoint posts a
 * body naming a cheap model, or a quantity of zero, and generates at a price it
 * chose. Nothing in the response would look wrong, because nothing IS wrong
 * from the endpoint's point of view; the amount is simply not the one the
 * platform published.
 *
 * <p>Asserted by actually deserializing, never by reflecting over annotations:
 * a test that checks "the annotation is present" passes on a build where a
 * mix-in or a visibility setting has already overridden it.
 */
class ToolExecutionRequestWireSealTest {

    /**
     * Deliberately LENIENT about unknown fields, because that is what the
     * application is: Spring Boot disables {@code FAIL_ON_UNKNOWN_PROPERTIES}.
     *
     * <p>A strict mapper would reject this body outright and every assertion
     * below would pass without the seal existing at all, which is the wrong
     * reason to be green. Read leniently, the fields are offered and must be
     * refused on their own merits.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature
                    .FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** A body that names everything a caller would need to buy a generation cheaply. */
    private static final String HOSTILE_BODY = """
            {
              "parameters": {"prompt": "a cat"},
              "generationModelId": "some-cheap-model",
              "generationQuantity": 0,
              "generationQuantityUnit": "call",
              "generation_model_id": "some-cheap-model",
              "generation_quantity": 0,
              "generation_quantity_unit": "call",
              "billingOwnedByCaller": true,
              "billingScopeKind": "RUN",
              "billingScopeId": "a-run-that-already-has-a-cheaper-pin",
              "billingStepId": "step-1"
            }
            """;

    @Test
    @DisplayName("a posted body cannot name the model its generation is priced against")
    void bodyCannotChooseThePricedModel() throws Exception {
        ToolExecutionRequest request = MAPPER.readValue(HOSTILE_BODY, ToolExecutionRequest.class);

        assertThat(request.getGenerationModelId())
                .as("the priced model comes from the X-Lc-Generation-Model header, never the body")
                .isNull();
    }

    @Test
    @DisplayName("a posted body cannot state how big its own generation is")
    void bodyCannotStateItsOwnSize() throws Exception {
        ToolExecutionRequest request = MAPPER.readValue(HOSTILE_BODY, ToolExecutionRequest.class);

        // Zero is the value that matters most here: it is what a caller would
        // send to be charged nothing for a ten second clip.
        assertThat(request.getGenerationQuantity())
                .as("the billable size is measured by the platform, never declared by the caller")
                .isNull();
        assertThat(request.getGenerationQuantityUnit())
                .as("a size without its unit cannot be checked, so the unit is sealed with it")
                .isNull();
    }

    @Test
    @DisplayName("a posted body cannot claim the call was already billed elsewhere")
    void bodyCannotClaimItSettlesItsOwnBilling() throws Exception {
        ToolExecutionRequest request = MAPPER.readValue(HOSTILE_BODY, ToolExecutionRequest.class);

        // This flag short-circuits the reserve/commit path entirely, on the
        // grounds that the CE relay already took the money. A caller able to
        // set it would simply not be charged.
        assertThat(request.getBillingOwnedByCaller())
                .as("only the relay may declare it owns the billing for a call")
                .isNotEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("a posted body cannot name the billing scope the charge lands on")
    void bodyCannotNameItsOwnBillingScope() throws Exception {
        ToolExecutionRequest request = MAPPER.readValue(HOSTILE_BODY, ToolExecutionRequest.class);

        // Naming a scope is not cosmetic. An existing pin on that scope is what
        // BYPASSES the delinquent-account refusal, and the pin is keyed on
        // (scopeKind, scopeId, credentialId) with no user in it, so naming an
        // older run also buys that run's older, cheaper pricing version and
        // writes another scope's pin onto this ledger row. The gateway strips
        // the matching headers for this reason; the body was the other half of
        // the same door.
        assertThat(request.getBillingScopeKind()).isNull();
        assertThat(request.getBillingScopeId()).isNull();
        assertThat(request.getBillingStepId()).isNull();
    }

    @Test
    @DisplayName("the platform CAN still set all three, so the seal blocks the wire and not the feature")
    void theServerSideRemainsWritable() {
        // Without this, the three tests above would also pass on a build where
        // the fields had been deleted outright, which would break pricing
        // rather than protect it.
        ToolExecutionRequest request = new ToolExecutionRequest();
        request.setGenerationModelId("seedance-2.0");
        request.setGenerationQuantity(new BigDecimal("10"));
        request.setGenerationQuantityUnit("second");

        assertThat(request.getGenerationModelId()).isEqualTo("seedance-2.0");
        assertThat(request.getGenerationQuantity()).isEqualByComparingTo("10");
        assertThat(request.getGenerationQuantityUnit()).isEqualTo("second");
    }
}
