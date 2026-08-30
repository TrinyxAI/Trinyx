package com.apimarketplace.auth.web.version;

import com.apimarketplace.auth.repository.CeReleaseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the two things about these endpoints that every other test in this feature assumes and none
 * of them check: the URLs they are actually mapped at, and the property that keeps the write
 * endpoint off customer-owned machines.
 *
 * <p>Both gaps have already cost a shipped bug. The announce endpoint was first mapped under
 * {@code /api/internal/ce/releases}, which the gateway neither allowlists nor routes, so the
 * release job would have taken a 401 on every run; every test still passed, because they all call
 * the controller method directly. Renaming the mapping today would be just as invisible.
 */
class CeReleaseEndpointWiringTest {

    static final String FEED_PATH = "/api/ce/releases/latest";
    static final String ANNOUNCE_PATH = "/api/ce/releases/announce";
    static final String STATS_PATH = "/api/ce/installs/stats";

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class))
            .withUserConfiguration(StubbedStore.class)
            .withBean(CeReleaseController.class, () ->
                    new CeReleaseController("", "", false, "", null, null));

    @Configuration
    static class StubbedStore {
        @Bean
        CeReleaseStore ceReleaseStore() {
            return new CeReleaseStore(mock(CeReleaseRepository.class));
        }
    }

    private static Set<String> mappedPatterns(RequestMappingHandlerMapping mapping) {
        return mapping.getHandlerMethods().entrySet().stream()
                .flatMap(e -> patternsOf(e))
                .collect(Collectors.toSet());
    }

    private static java.util.stream.Stream<String> patternsOf(Map.Entry<RequestMappingInfo, HandlerMethod> e) {
        return patternsOf(e.getKey());
    }

    private static java.util.stream.Stream<String> patternsOf(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            return info.getPathPatternsCondition().getPatterns().stream().map(Object::toString);
        }
        return info.getPatternValues().stream();
    }

    @Configuration
    static class BootstrapConfig {
        @Bean
        CeReleaseConfigBootstrap bootstrap(org.springframework.beans.factory.ObjectProvider<CeReleaseStore> store) {
            return new CeReleaseConfigBootstrap(store, "0.2.7", "https://example.test/v0.2.7", false, null);
        }
    }

    @Test
    @DisplayName("publishing ApplicationReadyEvent actually triggers the config seeding")
    void bootstrapIsWiredToApplicationReady() {
        // Removing @EventListener left every test green, because all the bootstrap tests call the
        // method directly - the same invisibility that let an unrouted @PostMapping ship. If the
        // listener silently stops firing, the row is never seeded, and blanking the pin in the
        // second deploy takes the whole fleet to "no release".
        CeReleaseStore store = mock(CeReleaseStore.class);
        when(store.current()).thenReturn(null);

        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withBean(CeReleaseStore.class, () -> store)
                .withUserConfiguration(BootstrapConfig.class)
                .run(context -> {
                    context.publishEvent(new org.springframework.boot.context.event.ApplicationReadyEvent(
                            new org.springframework.boot.SpringApplication(),
                            new String[0],
                            (org.springframework.context.ConfigurableApplicationContext) context.getSourceApplicationContext(),
                            java.time.Duration.ZERO));
                    verify(store).announceIfAbsent("0.2.7", "https://example.test/v0.2.7", false, null);
                });
    }

    @Test
    @DisplayName("the public feed is mapped at the expected path, by GET")
    void feedIsMappedAtTheAllowlistedPath() {
        runner.run(context -> {
            RequestMappingHandlerMapping mapping = context.getBean(RequestMappingHandlerMapping.class);
            assertThat(mappedPatterns(mapping)).contains(FEED_PATH);
        });
    }

    @Test
    @DisplayName("the public feed is GET-only, so every install's update check cannot 405")
    void feedIsGetOnly() {
        // Flipping @GetMapping to @PostMapping left the whole suite green while every CE install's
        // daily GET would 405 and the fleet's update check would die silently. The write endpoint
        // had this pinned; the read one did not.
        runner.run(context -> {
            RequestMappingHandlerMapping mapping = context.getBean(RequestMappingHandlerMapping.class);
            Set<org.springframework.web.bind.annotation.RequestMethod> methods =
                    mapping.getHandlerMethods().keySet().stream()
                            .filter(info -> patternsOf(info).anyMatch(FEED_PATH::equals))
                            .flatMap(info -> info.getMethodsCondition().getMethods().stream())
                            .collect(Collectors.toSet());
            assertThat(methods)
                    .as("the feed must answer GET and only GET")
                    .containsExactly(org.springframework.web.bind.annotation.RequestMethod.GET);
        });
    }

    @Test
    @DisplayName("the announced-row write keeps its transaction and its row lock")
    void writePathKeepsItsTransactionAndLock() throws Exception {
        // Both are load-bearing and both could be deleted with the suite green. Without
        // @Transactional the FOR UPDATE lock is released when Spring Data's own read-only
        // transaction around the query ends, i.e. BEFORE the write, so the guard-then-write stops
        // being atomic - which is precisely the cross-replica race the lock was added to close.
        // Complements CeReleaseRepositoryPersistenceTest, which exercises the real behaviour
        // against H2. An earlier version of this comment claimed no integration test could catch
        // it here - that was false, and it is what kept the one test that would have found the
        // merge-instead-of-insert defect from being written for ten rounds.
        java.lang.reflect.Method announce = CeReleaseStore.class.getMethod(
                "announce", String.class, String.class, boolean.class, String.class, boolean.class);
        assertThat(announce.getAnnotation(org.springframework.transaction.annotation.Transactional.class))
                .as("announce() must run in one transaction, or the row lock does not span the write")
                .isNotNull();

        java.lang.reflect.Method locked = com.apimarketplace.auth.repository.CeReleaseRepository.class
                .getMethod("findByIdForUpdate", Short.class);
        org.springframework.data.jpa.repository.Lock lock =
                locked.getAnnotation(org.springframework.data.jpa.repository.Lock.class);
        assertThat(lock).as("the write path must read the row under a lock").isNotNull();
        assertThat(lock.value()).isEqualTo(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    @DisplayName("the announce mapping is POST-only, so the release job's POST cannot 405")
    void announceIsPostOnly() {
        // Switching @PostMapping to @GetMapping left every wiring and controller test green while
        // the release job's POST would have 405'd - the same invisibility that let the unroutable
        // path ship.
        runner.withBean(CeReleaseAnnounceController.class, () ->
                        new CeReleaseAnnounceController(mock(CeReleaseStore.class), "secret", null))
                .withPropertyValues("ce.release.announce.enabled=true")
                .run(context -> {
                    RequestMappingHandlerMapping mapping = context.getBean(RequestMappingHandlerMapping.class);
                    Set<org.springframework.web.bind.annotation.RequestMethod> methods =
                            mapping.getHandlerMethods().keySet().stream()
                                    .filter(info -> patternsOf(info).anyMatch(ANNOUNCE_PATH::equals))
                                    .flatMap(info -> info.getMethodsCondition().getMethods().stream())
                                    .collect(Collectors.toSet());
                    assertThat(methods)
                            .as("the announce endpoint must be reachable by POST and only POST")
                            .containsExactly(org.springframework.web.bind.annotation.RequestMethod.POST);
                });
    }

    @Test
    @DisplayName("the announce controller is not registered when ce.release.announce.enabled is unset")
    void announceIsAbsentByDefault() {
        // The single control keeping an anonymously-reachable write endpoint off every self-hosted
        // install. Flipping matchIfMissing to true would arm it on customer infrastructure and,
        // before this test, would have turned nothing red.
        // UserConfigurations, not withBean: this actually EVALUATES the condition instead of
        // force-registering the bean and then inspecting the annotation, which is what the first
        // version did - it would not have noticed a second, unconditional registration.
        runner.withConfiguration(UserConfigurations.of(CeReleaseAnnounceController.class))
                .run(context -> assertThat(context)
                        .as("this write endpoint is anonymously reachable; it must not exist on a "
                                + "self-hosted install, which never announces anything")
                        .doesNotHaveBean(CeReleaseAnnounceController.class));

        runner.withConfiguration(UserConfigurations.of(CeReleaseAnnounceController.class))
                .withPropertyValues("ce.release.announce.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(CeReleaseAnnounceController.class));
    }

    @Test
    @DisplayName("the announce endpoint is mapped at the expected path")
    void announceIsMappedAtTheAllowlistedPath() {
        runner.withBean(CeReleaseAnnounceController.class, () ->
                        new CeReleaseAnnounceController(mock(CeReleaseStore.class), "secret", null))
                .withPropertyValues("ce.release.announce.enabled=true")
                .run(context -> {
                    RequestMappingHandlerMapping mapping = context.getBean(RequestMappingHandlerMapping.class);
                    assertThat(mappedPatterns(mapping))
                            .as("moving this mapping without updating GatewayConstants.PUBLIC_ENDPOINTS "
                                    + "makes every release announcement fail with a 401")
                            .contains(ANNOUNCE_PATH);
                });
    }

    @Test
    @DisplayName("the fleet read is mapped at the allowlisted path, by GET")
    void statsIsMappedAtTheAllowlistedPath() {
        // Same invisibility this class exists for: CeInstallStatsControllerTest calls stats()
        // directly, so a typo in the mapping, or a GET flipped to POST, leaves it green while the
        // gateway allowlists a 404 and the documented curl 405s.
        new org.springframework.boot.test.context.runner.WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class))
                .withPropertyValues("ce.installs.telemetry.enabled=true")
                .withBean(CeInstallStatsController.class, () ->
                        new CeInstallStatsController(
                                mock(CeFleetReader.class),
                                "secret-for-wiring-only"))
                .run(context -> {
                    RequestMappingHandlerMapping mapping = context.getBean(RequestMappingHandlerMapping.class);
                    assertThat(mappedPatterns(mapping)).contains(STATS_PATH);

                    Set<org.springframework.web.bind.annotation.RequestMethod> methods =
                            mapping.getHandlerMethods().keySet().stream()
                                    .filter(info -> patternsOf(info).anyMatch(STATS_PATH::equals))
                                    .flatMap(info -> info.getMethodsCondition().getMethods().stream())
                                    .collect(Collectors.toSet());
                    assertThat(methods)
                            .as("the fleet read must answer GET and only GET")
                            .containsExactly(org.springframework.web.bind.annotation.RequestMethod.GET);
                });
    }

    @Test
    @DisplayName("the fleet read takes its secret from the X-Internal-Auth header, not a parameter")
    void statsReadsTheInternalAuthHeader() throws Exception {
        // A typo in the @RequestHeader name makes the argument always null, so EVERY read is 401
        // for ever, and the direct-call test still passes because it hands the secret in as a
        // method argument. Only a real request through the mapping can see it.
        java.lang.reflect.Method method = CeInstallStatsController.class
                .getMethod("stats", String.class);
        org.springframework.web.bind.annotation.RequestHeader header =
                method.getParameters()[0].getAnnotation(
                        org.springframework.web.bind.annotation.RequestHeader.class);

        assertThat(header).as("the secret must arrive as a request header").isNotNull();
        assertThat(header.value()).isEqualTo("X-Internal-Auth");
        assertThat(header.required())
                .as("a missing header must reach the handler as null and be rejected there, "
                        + "not 400 before the constant-time comparison runs")
                .isFalse();
    }
}
