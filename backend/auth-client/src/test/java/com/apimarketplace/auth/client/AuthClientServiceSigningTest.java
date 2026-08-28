package com.apimarketplace.auth.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AuthClientServiceSigningTest {

    @Test
    void internalAuthCallsCarryServiceBoundHmacV2() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        AuthClient client = new AuthClient(
                http, "http://auth-service:8083", "datasource-service", "s".repeat(32));

        server.expect(requestTo(
                        "http://auth-service:8083/api/internal/auth/users/42/roles"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Provider-ID", "datasource-service"))
                .andExpect(header("X-Gateway-Signature-Version", "2"))
                .andExpect(header("X-Gateway-Secret",
                        org.hamcrest.Matchers.startsWith("v2=")))
                .andRespond(withSuccess("{\"roles\":[\"USER\"]}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.getUserRoles("42")).isEqualTo("USER");
        server.verify();
    }

    @Test
    void partialSigningConfigurationFailsClosed() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new AuthClient(new RestTemplate(), "http://auth", "agent-service", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configured together");
    }
}
