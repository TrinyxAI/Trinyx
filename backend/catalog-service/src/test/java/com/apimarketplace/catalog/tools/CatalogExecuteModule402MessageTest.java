package com.apimarketplace.catalog.tools;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.credential.client.CredentialClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * What an agent is told when a call is refused for payment.
 *
 * <p>The lead sentence is what an agent acts on. Three different refusals share
 * the 402 status and they have three different remedies: top up, state the size
 * of the call, or use your own provider key. Leading all three with "tell the
 * user to top up" sends the agent to a remedy that cannot work for two of them,
 * and it will loop or give up rather than fix the thing it could fix.
 */
class CatalogExecuteModule402MessageTest {

    private CatalogExecuteModule module;
    private Method handler;

    @BeforeEach
    void setUp() throws Exception {
        module = new CatalogExecuteModule(new ObjectMapper(), mock(CredentialClient.class));
        handler = CatalogExecuteModule.class.getDeclaredMethod(
                "handleHttpClientError", HttpClientErrorException.class, String.class);
        handler.setAccessible(true);
    }

    private ToolExecutionResult refuse(String body) throws Exception {
        HttpClientErrorException e = HttpClientErrorException.create(
                HttpStatus.PAYMENT_REQUIRED, "Payment Required",
                org.springframework.http.HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        return (ToolExecutionResult) handler.invoke(module, e, "seedance/create-video-task");
    }

    @Test
    @DisplayName("an unstated call size leads with the size, not with a top-up the user cannot act on")
    void sizeUnknownLeadsWithTheRealCause() throws Exception {
        ToolExecutionResult r = refuse("""
                {"success":false,"error":"INSUFFICIENT_CREDITS",
                 "message":"GENERATION_SIZE_UNKNOWN: this model is priced per second, so state duration_seconds.",
                 "delinquent":false}
                """);

        assertThat(r.success()).isFalse();
        assertThat(r.error())
                .startsWith("GENERATION_SIZE_UNKNOWN")
                .contains("duration_seconds")
                .contains("nothing was charged")
                .doesNotContain("top up");
    }

    @Test
    @DisplayName("a generation the platform does not sell leads with that, not with a balance problem")
    void platformNotAvailableLeadsWithTheRealCause() throws Exception {
        ToolExecutionResult r = refuse("""
                {"success":false,"error":"INSUFFICIENT_CREDITS",
                 "message":"PLATFORM_NOT_AVAILABLE: no price is published. Use your own provider key.",
                 "delinquent":false}
                """);

        assertThat(r.error())
                .startsWith("PLATFORM_NOT_AVAILABLE")
                .contains("your own provider key")
                .doesNotContain("top up");
    }

    @Test
    @DisplayName("a plan that excludes generation leads with THAT, never with a balance the user can see is fine")
    void planExclusionLeadsWithTheRealCause() throws Exception {
        // This refusal exists specifically to stop the balance sentence from
        // being said. The account HAS credits; they are the monthly grant,
        // which funds workflow runs and never a generation. Leading with "not
        // enough credits" to a user whose screen shows several hundred is how a
        // correct rule reads as a bug, and it points the agent at topping up,
        // which does not fix it.
        ToolExecutionResult r = refuse("""
                {"success":false,"error":"INSUFFICIENT_CREDITS",
                 "message":"PLAN_EXCLUDES_THIS: the account's credits are its monthly free grant, which funds workflow runs and never a generation. Subscribe or top up to pay per use.",
                 "delinquent":false}
                """);

        assertThat(r.error())
                .startsWith("PLAN_EXCLUDES_THIS")
                .contains("monthly free grant")
                .doesNotContain("does not have enough credits");
    }

    @Test
    @DisplayName("an actual empty balance still says top up, because that IS the remedy")
    void emptyBalanceStillSaysTopUp() throws Exception {
        ToolExecutionResult r = refuse("""
                {"success":false,"error":"INSUFFICIENT_CREDITS",
                 "message":"Insufficient credits","delinquent":false}
                """);

        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.QUOTA_EXCEEDED);
        assertThat(r.error())
                .contains("does not have enough credits")
                .contains("top up")
                .contains("retrying now will be refused again");
    }

    @Test
    @DisplayName("a delinquent account is told to settle, which is not the same act as topping up")
    void delinquentSaysSettle() throws Exception {
        ToolExecutionResult r = refuse("""
                {"success":false,"error":"INSUFFICIENT_CREDITS",
                 "message":"account delinquent","delinquent":true}
                """);

        assertThat(r.error()).contains("delinquent").contains("settle the balance");
    }

    @Test
    @DisplayName("every refusal states the call did not run, so an agent never double-charges by retrying blind")
    void everyRefusalSaysNothingRan() throws Exception {
        for (String body : new String[] {
                "{\"message\":\"GENERATION_SIZE_UNKNOWN: state duration_seconds.\"}",
                "{\"message\":\"PLATFORM_NOT_AVAILABLE: not sold here.\"}",
                "{\"message\":\"Insufficient credits\"}",
                "{\"message\":\"account delinquent\",\"delinquent\":true}" }) {
            assertThat(refuse(body).error())
                    .as("refusal body %s", body)
                    .contains("NOT executed")
                    .contains("nothing was charged");
        }
    }
}
