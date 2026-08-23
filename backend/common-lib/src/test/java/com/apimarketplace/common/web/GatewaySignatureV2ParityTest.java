package com.apimarketplace.common.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class GatewaySignatureV2ParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void javaCanonicalizationMatchesSharedV2Fixtures() throws Exception {
        JsonNode root = MAPPER.readTree(Files.newBufferedReader(locateFixture()));
        for (JsonNode item : root.path("cases")) {
            GatewaySignatureV2.Context context = new GatewaySignatureV2.Context(
                    text(item, "timestamp"), text(item, "nonce"), text(item, "method"),
                    text(item, "requestTarget"), text(item, "bodySha256"), text(item, "providerId"),
                    text(item, "userId"), text(item, "principalId"), text(item, "billingSubjectId"),
                    text(item, "organizationId"), text(item, "organizationRole"),
                    text(item, "userRoles"), text(item, "installId"));
            assertThat(GatewaySignatureV2.canonicalPayload(context))
                    .as(item.path("name").asText())
                    .isEqualTo(text(item, "expectedCanonicalPayload"));
            assertThat(GatewaySignatureV2.sign(root.path("secretKey").asText(), context))
                    .startsWith("gw_");
        }
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("");
    }

    private static Path locateFixture() {
        Path here = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 7; i++) {
            Path candidate = here.resolve("shared/contracts/gateway-signature-v2-fixtures.json");
            if (Files.exists(candidate)) {
                return candidate;
            }
            if (here.getParent() == null) {
                break;
            }
            here = here.getParent();
        }
        throw new IllegalStateException("gateway-signature-v2-fixtures.json not found");
    }
}
