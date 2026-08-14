package com.apimarketplace.orchestrator.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generation client must not be able to submit the same generation twice.
 *
 * <p>{@code SimpleClientHttpRequestFactory} wraps {@code HttpURLConnection},
 * which SILENTLY RE-SENDS a POST once when a pooled connection the peer closed
 * while idle throws on write, because {@code sun.net.http.retryPost} defaults
 * to true. A rolling deploy or an idle reaper on the catalog side is enough.
 *
 * <p>For most calls a duplicate is noise. Here it is money: the billing layer
 * mints a fresh call reference per dispatch so a second dispatch is
 * deliberately a second charge, on the stated reasoning that one HTTP execute
 * is one dispatch to the provider. A transport that re-sends by itself makes
 * that premise false, and the customer pays for two generations of which the
 * run keeps one. The browser leg of this same feature already refuses to retry
 * for exactly this reason, with a comment saying so.
 *
 * <p>Asserted on the factory TYPE rather than by driving a socket: the retry
 * lives inside the JDK and cannot be observed without a server that accepts a
 * connection, closes it, and counts what arrives next. The type is what decides
 * it, so the type is the rule.
 */
class GenerationRestTemplateNoRetryTest {

    private RestTemplate generationTemplate() {
        RestTemplateConfig config = new RestTemplateConfig();
        ReflectionTestUtils.setField(config, "connectTimeout", 5000);
        ReflectionTestUtils.setField(config, "generationReadTimeout", 1_500_000);
        return config.generationRestTemplate();
    }

    @Test
    @DisplayName("does not use the HttpURLConnection client, which re-sends a POST by itself")
    void doesNotUseTheRetryingTransport() {
        ClientHttpRequestFactory factory = generationTemplate().getRequestFactory();

        assertThat(factory)
                .as("HttpURLConnection retries a POST once on a stale pooled connection, "
                        + "and each retry is a second paid generation")
                .isNotInstanceOf(SimpleClientHttpRequestFactory.class);
    }

    @Test
    @DisplayName("uses the JDK HTTP client, which never retries a non-idempotent method")
    void usesATransportThatDoesNotRetryAPost() {
        // Stated positively as well, so deleting the bean or handing back a
        // template with no factory at all cannot pass the negative above.
        assertThat(generationTemplate().getRequestFactory())
                .isInstanceOf(JdkClientHttpRequestFactory.class);
    }
}
