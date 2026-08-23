package com.apimarketplace.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Ed25519JwsTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void signsVerifiesAndSelectsRotatedKeyByKid() throws Exception {
        var generator = KeyPairGenerator.getInstance("Ed25519");
        var active = generator.generateKeyPair();
        var previous = generator.generateKeyPair();
        var claims = json.createObjectNode().put("sub", "principal-1").put("sequence", 7);

        String token = Ed25519Jws.sign(claims, "active-2026-08", active.getPrivate());
        Ed25519Jws.Verified verified = Ed25519Jws.verify(token, Map.of(
                "previous-2026-07", previous.getPublic(),
                "active-2026-08", active.getPublic()));

        assertThat(verified.kid()).isEqualTo("active-2026-08");
        assertThat(verified.claims().path("sub").asText()).isEqualTo("principal-1");
        assertThat(verified.claims().path("sequence").asLong()).isEqualTo(7);
    }

    @Test
    void rejectsTamperingUnknownKeyAndInvalidAlgorithm() throws Exception {
        var generator = KeyPairGenerator.getInstance("Ed25519");
        var pair = generator.generateKeyPair();
        String token = Ed25519Jws.sign(json.createObjectNode().put("sub", "p"), "kid-1",
                pair.getPrivate());

        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." +
                java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString("{\"sub\":\"attacker\"}".getBytes()) +
                "." + parts[2];

        assertThatThrownBy(() -> Ed25519Jws.verify(tampered, Map.of("kid-1", pair.getPublic())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ed25519Jws.verify(token, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown JWS key id");
    }

    @Test
    void externalBase64KeyMaterialRoundTrips() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String privateValue = java.util.Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        String publicValue = java.util.Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());

        String token = Ed25519Jws.sign(json.createObjectNode().put("aud", "trinyx-cloud"),
                "rotation-1", Ed25519Jws.privateKeyFromPkcs8Base64(privateValue));

        assertThat(Ed25519Jws.verify(token, Map.of("rotation-1",
                Ed25519Jws.publicKeyFromX509Base64(publicValue))).claims().path("aud").asText())
                .isEqualTo("trinyx-cloud");
    }
}
