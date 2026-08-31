package com.apimarketplace.orchestrator;

import com.apimarketplace.common.credit.CreditClientAutoConfig;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.credit.ExternalSettlementIntentStore;
import com.apimarketplace.common.credit.ExternalSettlementOutboxAutoConfiguration;
import com.apimarketplace.common.credit.RedisExternalSettlementIntentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OrchestratorSettlementOutboxBootstrapTest {

    private static final String TEST_SECRET =
            "test-only-internal-secret-with-at-least-thirty-two-characters";

    @Test
    void componentScannedAutoConfigurationEvaluatesBeforeRedisTemplate() {
        runner(UnsafeCreditPackageScan.class, false).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseMessage(
                            "external-paid-monolith requires a durable producer settlement outbox");
        });
    }

    @Test
    void productionScanExcludesBootAutoConfigurationFromComponentScanning() {
        ComponentScan scan =
                OrchestratorServiceApplication.class.getAnnotation(ComponentScan.class);

        boolean excludesSettlementAutoConfiguration = Arrays.stream(scan.excludeFilters())
                .filter(filter -> filter.type() == FilterType.ASSIGNABLE_TYPE)
                .flatMap(filter -> Arrays.stream(filter.classes()))
                .anyMatch(ExternalSettlementOutboxAutoConfiguration.class::equals);

        assertThat(excludesSettlementAutoConfiguration).isTrue();
    }

    @Test
    void bootAutoConfigurationCreatesDurableStoreBeforeCreditClientValidation() {
        runner(SafeCreditPackageScan.class, true).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("stringRedisTemplate");
            assertThat(context).hasSingleBean(ExternalSettlementIntentStore.class);
            assertThat(context).hasSingleBean(CreditConsumptionClient.class);

            ExternalSettlementIntentStore store =
                    context.getBean(ExternalSettlementIntentStore.class);
            CreditConsumptionClient client =
                    context.getBean(CreditConsumptionClient.class);

            assertThat(store)
                    .isInstanceOf(RedisExternalSettlementIntentStore.class);
            assertThat(store.durable()).isTrue();
            assertThat(ReflectionTestUtils.getField(client, "settlementIntentStore"))
                    .isSameAs(store);
        });
    }

    private ApplicationContextRunner runner(
            Class<?> scanConfiguration,
            boolean importSettlementAutoConfiguration) {
        AutoConfigurations autoConfigurations = importSettlementAutoConfiguration
                ? AutoConfigurations.of(
                        RedisAutoConfiguration.class,
                        ExternalSettlementOutboxAutoConfiguration.class)
                : AutoConfigurations.of(RedisAutoConfiguration.class);

        return new ApplicationContextRunner()
                .withConfiguration(autoConfigurations)
                .withUserConfiguration(scanConfiguration)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(RedisConnectionFactory.class,
                        () -> mock(RedisConnectionFactory.class))
                .withPropertyValues(
                        "spring.application.name=orchestrator-service",
                        "services.auth-service.url=http://auth-service:8083",
                        "credit.consumption.enabled=true",
                        "billing.authority.mode=external-paid-monolith",
                        "billing.external.require-producer-outbox=true",
                        "gateway.filter.secret-key=" + TEST_SECRET,
                        "internal.s2s.service-secret=" + TEST_SECRET);
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = CreditClientAutoConfig.class)
    static class UnsafeCreditPackageScan {
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackageClasses = CreditClientAutoConfig.class,
            excludeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = ExternalSettlementOutboxAutoConfiguration.class))
    static class SafeCreditPackageScan {
    }
}
