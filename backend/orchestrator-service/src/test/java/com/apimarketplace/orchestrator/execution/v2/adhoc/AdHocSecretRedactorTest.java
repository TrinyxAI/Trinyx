package com.apimarketplace.orchestrator.execution.v2.adhoc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * run_node is the first surface where an agent passes a secret INLINE rather than by
 * credential reference, and every tool call is stored twice in full (observability arguments
 * as jsonb, result body in the conversation). These tests pin that nothing credential-shaped
 * survives into either.
 */
class AdHocSecretRedactorTest {

    @Nested
    @DisplayName("credential-shaped keys")
    class SecretKeys {

        @Test
        @DisplayName("Should redact the credential keys a node config actually carries")
        void shouldRedactCredentialKeys() {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("host", "db.internal");
            config.put("password", "hunter2");
            config.put("apiKey", "sk_live_abc");
            config.put("client_secret", "s3cr3t");
            config.put("privateKey", "-----BEGIN KEY-----");
            config.put("refresh_token", "rt_xyz");
            config.put("passphrase", "open sesame");

            Map<String, Object> redacted = AdHocSecretRedactor.redactMap(config);

            assertThat(redacted.get("host")).isEqualTo("db.internal");
            assertThat(redacted.get("password")).isEqualTo(AdHocSecretRedactor.REDACTED);
            assertThat(redacted.get("apiKey")).isEqualTo(AdHocSecretRedactor.REDACTED);
            assertThat(redacted.get("client_secret")).isEqualTo(AdHocSecretRedactor.REDACTED);
            assertThat(redacted.get("privateKey")).isEqualTo(AdHocSecretRedactor.REDACTED);
            assertThat(redacted.get("refresh_token")).isEqualTo(AdHocSecretRedactor.REDACTED);
            assertThat(redacted.get("passphrase")).isEqualTo(AdHocSecretRedactor.REDACTED);
        }

        @Test
        @DisplayName("Should leave innocent keys that merely contain 'key' untouched")
        void shouldNotOverReach() {
            Map<String, Object> config = Map.of(
                    "sortKey", "created_at",
                    "partitionKey", "tenant",
                    "keyword", "invoice",
                    "monkey", "business",
                    "keys", List.of("a", "b"));

            Map<String, Object> redacted = AdHocSecretRedactor.redactMap(config);

            assertThat(redacted.get("sortKey")).isEqualTo("created_at");
            assertThat(redacted.get("partitionKey")).isEqualTo("tenant");
            assertThat(redacted.get("keyword")).isEqualTo("invoice");
            assertThat(redacted.get("monkey")).isEqualTo("business");
            assertThat(redacted.get("keys")).isEqualTo(List.of("a", "b"));
        }

        @Test
        @DisplayName("Should match a credential key however it is spelled")
        void shouldNormalizeKeySpelling() {
            assertThat(AdHocSecretRedactor.isSecretKey("API_KEY")).isTrue();
            assertThat(AdHocSecretRedactor.isSecretKey("api-key")).isTrue();
            assertThat(AdHocSecretRedactor.isSecretKey("apiKey")).isTrue();
            assertThat(AdHocSecretRedactor.isSecretKey("AccessKeyId")).isTrue();
            assertThat(AdHocSecretRedactor.isSecretKey("url")).isFalse();
        }
    }

    @Nested
    @DisplayName("nested structures")
    class Nested_ {

        @Test
        @DisplayName("Should reach a secret buried in a nested object or a list")
        void shouldRecurse() {
            Map<String, Object> config = Map.of(
                    "connection", Map.of("host", "db", "password", "hunter2"),
                    "headers", List.of(Map.of("name", "Authorization", "authorization", "Bearer abc")));

            Map<String, Object> redacted = AdHocSecretRedactor.redactMap(config);

            @SuppressWarnings("unchecked")
            Map<String, Object> connection = (Map<String, Object>) redacted.get("connection");
            assertThat(connection.get("host")).isEqualTo("db");
            assertThat(connection.get("password")).isEqualTo(AdHocSecretRedactor.REDACTED);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> headers = (List<Map<String, Object>>) redacted.get("headers");
            assertThat(headers.get(0).get("name")).isEqualTo("Authorization");
            assertThat(headers.get(0).get("authorization")).isEqualTo(AdHocSecretRedactor.REDACTED);
        }

        @Test
        @DisplayName("Should not mutate the caller's configuration")
        void shouldNotMutateInput() {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("password", "hunter2");

            AdHocSecretRedactor.redactMap(config);

            assertThat(config.get("password"))
                    .as("the node still has to receive the real value")
                    .isEqualTo("hunter2");
        }
    }

    @Nested
    @DisplayName("credentials carried inside a URL")
    class UrlCredentials {

        @Test
        @DisplayName("Should redact the password of a connection URL and keep the host readable")
        void shouldRedactUrlPassword() {
            // A DSN hides the secret in the value, not the key: postgres://user:pw@host/db.
            String redacted = AdHocSecretRedactor.redactUrlCredentials(
                    "postgres://admin:sup3rs3cret@db.internal:5432/prod");

            assertThat(redacted).doesNotContain("sup3rs3cret");
            assertThat(redacted).contains("db.internal:5432/prod");
            assertThat(redacted).contains("admin");
        }

        @Test
        @DisplayName("Should reach a DSN even under a key that says nothing")
        void shouldRedactUrlUnderInnocentKey() {
            Map<String, Object> redacted = AdHocSecretRedactor.redactMap(
                    Map.of("target", "mysql://root:letmein@10.0.0.5/app"));

            assertThat(String.valueOf(redacted.get("target"))).doesNotContain("letmein");
        }

        @Test
        @DisplayName("Should leave a URL without credentials exactly as it is")
        void shouldLeavePlainUrls() {
            String url = "https://api.example.com/v1/things?page=2";
            assertThat(AdHocSecretRedactor.redactUrlCredentials(url)).isEqualTo(url);
        }
    }

    @Nested
    @DisplayName("the shapes this codebase actually uses")
    class RealConfigShapes {

        @Test
        @DisplayName("Should redact an HttpParam header, where the secret sits under 'value'")
        void shouldRedactHttpParamPair() {
            // Headers and query parameters are HttpParam{key,value}: the secret lives under a
            // field literally called 'value', which key-name matching alone cannot see.
            Map<String, Object> config = Map.of("headers", List.of(
                    Map.of("key", "Authorization", "value", "Bearer sk_live_leaked"),
                    Map.of("key", "Accept", "value", "application/json")));

            String rendered = String.valueOf(AdHocSecretRedactor.redactMap(config));

            assertThat(rendered).doesNotContain("sk_live_leaked");
            assertThat(rendered).as("a harmless header must survive").contains("application/json");
        }

        @Test
        @DisplayName("Should redact the crypto_jwt signing key, which hides under the name 'key'")
        void shouldRedactCryptoJwtSigningKey() {
            // CryptoJwtConfig carries the HMAC/RSA signing key in a field called 'key', a name
            // excluded from the generic fragments so it does not swallow sortKey/partitionKey.
            // Only the node type can tell them apart.
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("operation", "jwtCreate");
            config.put("algorithm", "HS256");
            config.put("key", "super-signing-key");
            config.put("value", "the payload");

            Map<String, Object> redacted = AdHocSecretRedactor.redactMap(config, "crypto_jwt");

            assertThat(String.valueOf(redacted)).doesNotContain("super-signing-key");
            assertThat(redacted.get("value"))
                    .as("the payload is not the secret; blanking it instead was the inverse mistake")
                    .isEqualTo("the payload");
        }

        @Test
        @DisplayName("Should leave a plain sortKey alone on any other node type")
        void shouldNotRedactInnocentKeyField() {
            Map<String, Object> redacted = AdHocSecretRedactor.redactMap(
                    Map.of("key", "created_at"), "transform");

            assertThat(redacted.get("key")).isEqualTo("created_at");
        }

        @Test
        @DisplayName("Should strip a secret carried in a URL query string")
        void shouldRedactQueryStringSecret() {
            Map<String, Object> redacted = AdHocSecretRedactor.redactMap(
                    Map.of("url", "https://api.example.com/v1/things?api_key=sk_live_leaked&page=2"));

            String url = String.valueOf(redacted.get("url"));
            assertThat(url).doesNotContain("sk_live_leaked");
            assertThat(url).as("the rest of the URL stays readable").contains("page=2");
        }

        @Test
        @DisplayName("Should redact each credential field name the node configs really carry")
        void shouldRedactEveryRealFragment() {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("bearerToken", "tok_leaked");
            config.put("headerValue", "Bearer hdr_leaked");
            config.put("dsn", "postgres://u:dsn_leaked@h/db");
            config.put("connectionString", "Server=h;Password=cs_leaked");

            String rendered = String.valueOf(AdHocSecretRedactor.redactMap(config));

            for (String secret : List.of("tok_leaked", "hdr_leaked", "dsn_leaked", "cs_leaked")) {
                assertThat(rendered).as("%s must not survive", secret).doesNotContain(secret);
            }
        }

        @Test
        @DisplayName("Should keep the credential REFERENCE so the echo is still pasteable")
        void shouldKeepCredentialReference() {
            // The echo exists to be pasted into add_node. Blanking the reference makes it
            // unusable for exactly the nodes that need one.
            Map<String, Object> redacted = AdHocSecretRedactor.redactMap(
                    Map.of("credentialId", 42, "apiKeyName", "X-Api-Key", "apiKeyLocation", "header"));

            assertThat(redacted.get("credentialId")).isEqualTo(42);
            assertThat(redacted.get("apiKeyName")).isEqualTo("X-Api-Key");
            assertThat(redacted.get("apiKeyLocation")).isEqualTo("header");
        }
    }
}