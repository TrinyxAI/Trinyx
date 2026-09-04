package com.apimarketplace.catalog;

import com.apimarketplace.common.credit.ExternalSettlementOutboxAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Catalog service.
 */
@SpringBootApplication
@EnableScheduling
@ComponentScan(
    basePackages = {
        "com.apimarketplace.catalog",
        "com.apimarketplace.common.mapping",
        "com.apimarketplace.common.security",
        "com.apimarketplace.common.credit",   // CreditClientAutoConfig → CreditConsumptionClient bean
        "com.apimarketplace.auth.client",     // AuthClientConfig -> AuthClient bean (CE catalog relay link/entitlements gates)
        "com.apimarketplace.sse"
    },
    excludeFilters = @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = ExternalSettlementOutboxAutoConfiguration.class)
)
public class CatalogApplication {
    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }
}
