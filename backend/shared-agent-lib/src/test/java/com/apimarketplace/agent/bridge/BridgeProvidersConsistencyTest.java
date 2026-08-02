package com.apimarketplace.agent.bridge;

import com.apimarketplace.agent.domain.BridgeProviders;
import com.apimarketplace.agent.factory.BridgeAvailabilityFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BridgeProviders#NAMES} is the single source of the local-CLI provider
 * list, and it now has a consumer well outside the agent stack: the marketplace
 * decides whether a publication is Community-Edition exclusive from it.
 *
 * <p>A fifth bridge added to one list but not the others would silently stop
 * flagging publications that use it, so they would be offered for install on
 * managed cloud and fail at run time. These assertions make the lists diverge
 * loudly instead.
 */
@DisplayName("BridgeProviders - single source of the CLI provider list")
class BridgeProvidersConsistencyTest {

    @Test
    @DisplayName("BridgeAllowlist re-exports the shared set, it does not keep its own copy")
    void allowlistReExportsTheSharedSet() {
        assertThat(BridgeAllowlist.BRIDGE_PROVIDERS).isSameAs(BridgeProviders.NAMES);
    }

    @Test
    @DisplayName("every allow-listed bridge has a MODELS entry, and vice versa")
    void everyBridgeHasModels() {
        assertThat(BridgeAllowlist.MODELS.keySet())
                .as("a bridge with no routable model would appear in the picker and fail at dispatch")
                .isEqualTo(BridgeProviders.NAMES);
    }

    @Test
    @DisplayName("the CLI-id map covers exactly the shared set (availability probing must not skip a bridge)")
    void cliIdMapCoversTheSharedSet() {
        assertThat(BridgeAvailabilityFilter.BRIDGE_PROVIDER_TO_CLI_ID.keySet())
                .as("a bridge missing here is never probed for availability, so it is offered "
                        + "even when its CLI is absent from the bridge host")
                .isEqualTo(BridgeProviders.NAMES);
    }

    @Test
    @DisplayName("isBridgeProvider is null-safe and case/whitespace tolerant (values come from snapshots)")
    void isBridgeProviderIsLenientOnInput() {
        assertThat(BridgeProviders.isBridgeProvider("claude-code")).isTrue();
        assertThat(BridgeProviders.isBridgeProvider("  Claude-Code  ")).isTrue();
        assertThat(BridgeProviders.isBridgeProvider("anthropic")).isFalse();
        assertThat(BridgeProviders.isBridgeProvider("")).isFalse();
        assertThat(BridgeProviders.isBridgeProvider(null)).isFalse();
    }
}
