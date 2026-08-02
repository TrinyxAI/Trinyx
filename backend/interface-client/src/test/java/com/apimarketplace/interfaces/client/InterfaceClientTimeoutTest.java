package com.apimarketplace.interfaces.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards that no template this client builds is left with an infinite read timeout.
 *
 * <p>Why this is worth a dedicated test: {@code refreshSnapshotsFromLive} is invoked synchronously
 * on the workflow trigger fire path, after the epoch has been opened and before the workflow's
 * first node executes, wrapped in a {@code catch (Exception)} whose comment promises it can never
 * block the trigger pipeline. A hang is not an exception, so with an unbounded template that
 * promise was not kept: one unresponsive interface-service parks the fire forever, with the
 * workflow having run no node at all and nothing in the logs to say so.
 *
 * <p>The regression is invisible to every behavioural test, because a mocked {@code RestTemplate}
 * never exercises the factory. It is only observable by inspecting the constructed template, which
 * is what this does.
 */
@DisplayName("InterfaceClient timeouts")
class InterfaceClientTimeoutTest {

    private static final String BASE_URL = "http://localhost:8089";

    @Test
    @DisplayName("Default template is bounded on both connect and read")
    void defaultTemplateIsBounded() throws Exception {
        InterfaceClient client = new InterfaceClient(BASE_URL);

        SimpleClientHttpRequestFactory factory = factoryOf(client, "restTemplate");

        // Pre-fix the default template was a bare new RestTemplate(), whose factory carries no
        // explicit timeouts at all: connectTimeout and readTimeout both sit at 0, which the JDK
        // reads as "wait forever".
        assertThat(readTimeoutMillis(factory))
            .as("default template read timeout must be finite and positive")
            .isGreaterThan(0);
        assertThat(connectTimeoutMillis(factory))
            .as("default template connect timeout must be finite and positive")
            .isGreaterThan(0);
    }

    @Test
    @DisplayName("Recent-activity template stays tighter than the default one")
    void recentActivityTemplateStaysTighter() throws Exception {
        InterfaceClient client = new InterfaceClient(BASE_URL);

        // The recent-activity path runs inside a 3s CompletableFuture budget, so it must not
        // inherit the default read timeout. Asserting the ORDER rather than the literal values
        // keeps this test meaningful if either value is retuned.
        assertThat(readTimeoutMillis(factoryOf(client, "recentActivityRestTemplate")))
            .as("recent-activity read timeout must stay below the default one")
            .isLessThan(readTimeoutMillis(factoryOf(client, "restTemplate")));
    }

    @Test
    @DisplayName("An injected template is used as-is, so callers keep control of their own timeouts")
    void injectedTemplateIsNotOverridden() throws Exception {
        SimpleClientHttpRequestFactory injectedFactory = new SimpleClientHttpRequestFactory();
        injectedFactory.setConnectTimeout(Duration.ofSeconds(1));
        injectedFactory.setReadTimeout(Duration.ofSeconds(2));
        RestTemplate injected = new RestTemplate(injectedFactory);

        InterfaceClient client = new InterfaceClient(injected, BASE_URL);

        assertThat(field(client, "restTemplate")).isSameAs(injected);
        assertThat(readTimeoutMillis(factoryOf(client, "restTemplate"))).isEqualTo(2000);
    }

    private static SimpleClientHttpRequestFactory factoryOf(InterfaceClient client, String templateField)
            throws Exception {
        RestTemplate template = (RestTemplate) field(client, templateField);
        ClientHttpRequestFactory factory = template.getRequestFactory();
        assertThat(factory).isInstanceOf(SimpleClientHttpRequestFactory.class);
        return (SimpleClientHttpRequestFactory) factory;
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = InterfaceClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static int readTimeoutMillis(SimpleClientHttpRequestFactory factory) throws Exception {
        return intField(factory, "readTimeout");
    }

    private static int connectTimeoutMillis(SimpleClientHttpRequestFactory factory) throws Exception {
        return intField(factory, "connectTimeout");
    }

    private static int intField(SimpleClientHttpRequestFactory factory, String name) throws Exception {
        Field field = SimpleClientHttpRequestFactory.class.getDeclaredField(name);
        field.setAccessible(true);
        return (int) field.get(factory);
    }
}
