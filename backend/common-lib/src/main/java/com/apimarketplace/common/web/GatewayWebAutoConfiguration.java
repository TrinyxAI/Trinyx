package com.apimarketplace.common.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(GatewayFilterProperties.class)
public class GatewayWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TenantResolver tenantResolver() {
        return new TenantResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public MdcContextFilter mdcContextFilter() {
        return new MdcContextFilter();
    }

    @Bean
    @ConditionalOnMissingBean(GatewayNonceStore.class)
    @ConditionalOnProperty(name = "deployment.mode", havingValue = "microservice", matchIfMissing = true)
    public GatewayNonceStore gatewayNonceStore() {
        return new InMemoryGatewayNonceStore();
    }

    @Bean
    @ConditionalOnMissingBean(GatewayAuthenticationFilter.class)
    @ConditionalOnProperty(name = "deployment.mode", havingValue = "microservice", matchIfMissing = true)
    public GatewayAuthenticationFilter gatewayAuthenticationFilter(
            GatewayFilterProperties properties,
            GatewayNonceStore nonceStore) {
        return new GatewayAuthenticationFilter(properties, nonceStore);
    }

    @Bean
    @ConditionalOnMissingBean(MonolithSecurityFilter.class)
    @ConditionalOnProperty(name = "deployment.mode", havingValue = "monolith")
    public MonolithSecurityFilter monolithSecurityFilter(GatewayFilterProperties properties) {
        return new MonolithSecurityFilter(() -> null, properties.getPublicPaths());
    }

    @Bean
    @ConditionalOnMissingBean(ServicePrefixRewriteFilter.class)
    @ConditionalOnProperty(name = "deployment.mode", havingValue = "monolith")
    public ServicePrefixRewriteFilter servicePrefixRewriteFilter() {
        return new ServicePrefixRewriteFilter();
    }
}
