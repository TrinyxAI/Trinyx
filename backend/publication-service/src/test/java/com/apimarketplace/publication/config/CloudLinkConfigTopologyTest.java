package com.apimarketplace.publication.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudLinkConfigTopologyTest {

    @Test
    void acceptsTheSupportedSingleReplicaInMemoryTopology() {
        assertThatCode(() -> CloudLinkConfig.requireSupportedTopology("in-memory", 1))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMultipleReplicasWhilePendingStateIsProcessLocal() {
        assertThatThrownBy(() -> CloudLinkConfig.requireSupportedTopology("in-memory", 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("replica-count must remain 1");
    }

    @Test
    void rejectsAnUnimplementedSharedStoreConfiguration() {
        assertThatThrownBy(() -> CloudLinkConfig.requireSupportedTopology("redis", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported cloud-link.pending-state-store");
    }
}
