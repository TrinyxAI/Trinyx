package com.apimarketplace.publication.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Makes the CloudLink capability follow the remote Marketplace by default, as
 * LiveContext CE does, while retaining Trinyx's independent paid-monolith switch.
 *
 * <p>Remote Marketplace mode always enables CloudLink, matching upstream.
 * {@code cloud-link.enabled=true} additionally enables it when Marketplace is
 * local, which is the Trinyx paid-monolith extension.
 */
public final class CloudLinkAvailableCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        boolean remoteMarketplace = "remote".equalsIgnoreCase(
                context.getEnvironment().getProperty("marketplace.mode", "local"));
        boolean explicitlyEnabled = Boolean.parseBoolean(
                context.getEnvironment().getProperty("cloud-link.enabled", "false").trim());
        return remoteMarketplace || explicitlyEnabled;
    }
}
