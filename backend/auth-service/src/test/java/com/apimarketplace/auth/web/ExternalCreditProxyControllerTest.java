package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.CloudIdentityBindingService;
import com.apimarketplace.auth.service.ExternalCreditProxyService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ExternalCreditProxyControllerTest {

    @Test
    void dispatchingRouteDelegatesTheSynchronousSafetyGate() {
        ExternalCreditProxyService proxy = mock(ExternalCreditProxyService.class);
        ExternalCreditProxyController controller = new ExternalCreditProxyController(
                proxy, mock(CloudIdentityBindingService.class));
        UUID operationId = UUID.randomUUID();
        var command = new ExternalCreditProxyService.DispatchingCommand(
                "a".repeat(64), "openai", "gpt");

        controller.dispatching(operationId, command);

        verify(proxy).dispatching(operationId, command);
    }

    @Test
    void requestlessWorkerContextComesFromUniquePersistedBinding() {
        ExternalCreditProxyService proxy = mock(ExternalCreditProxyService.class);
        CloudIdentityBindingService identities = mock(CloudIdentityBindingService.class);
        UUID principal = UUID.randomUUID();
        UUID payer = UUID.randomUUID();
        UUID organization = UUID.randomUUID();
        UUID install = UUID.randomUUID();
        when(identities.context(42L, organization)).thenReturn(
                new CloudIdentityBindingService.BindingContext(
                        42L, "subject", principal, payer, organization,
                        "MEMBER", install, 3L, "ACTIVE"));
        ExternalCreditProxyController controller =
                new ExternalCreditProxyController(proxy, identities);
        var command = new ExternalCreditProxyService.LlmReserveCommand(
                UUID.randomUUID(), "cloudWebSearchRelay", "BROWSER_AGENT_EXECUTION",
                "openai", "gpt", 100, 50);

        controller.reserveLlm(42L, null, null, organization, null, command);

        ArgumentCaptor<ExternalCreditProxyService.Context> context =
                ArgumentCaptor.forClass(ExternalCreditProxyService.Context.class);
        verify(proxy).reserveLlm(context.capture(), same(command));
        assertThat(context.getValue()).isEqualTo(
                new ExternalCreditProxyService.Context(principal, payer, organization, install));
    }

    @Test
    void partialIdentityHeadersAreRejectedInsteadOfMixedWithStoredState() {
        ExternalCreditProxyController controller = new ExternalCreditProxyController(
                mock(ExternalCreditProxyService.class), mock(CloudIdentityBindingService.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.reserveLlm(
                42L, UUID.randomUUID(), null, UUID.randomUUID(), null,
                new ExternalCreditProxyService.LlmReserveCommand(
                        UUID.randomUUID(), "cloudWebSearchRelay", "BROWSER_AGENT_EXECUTION",
                        "openai", "gpt", 1, 1)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("PARTIAL_IDENTITY_CONTEXT");
    }
}
