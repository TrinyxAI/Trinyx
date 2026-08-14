package com.apimarketplace.common.web;

import java.util.Set;

/**
 * Headers that decide how much a call costs, and which therefore may never
 * arrive from a client.
 *
 * <p>They are written by the platform's own in-process callers and are read by
 * the billing layer as fact. A caller able to set them could name a cheap
 * model, a quantity of one for a ten second video, a unit that makes its call
 * look small against the published rate, or an existing billing scope whose pin
 * bypasses the delinquent-account refusal.
 *
 * <p><b>Every edge that fronts {@code /catalog/v1/**} has to strip them, not
 * just the one that was thought of first.</b> The list lives here, in the
 * module both edges already depend on, because the cloud gateway and the CE
 * monolith are two doors into the same endpoint: a set restated in one of them
 * is a set that drifts, and the half that drifts is the half that stops
 * stripping. The DTO fields are additionally {@code @JsonIgnore} so the request
 * BODY cannot carry them either; a header strip alone would close one door of
 * two.
 */
public final class BillingContextHeaders {

    private BillingContextHeaders() {}

    /** The six headers, lowercase-insensitive at every comparison site. */
    public static final Set<String> ALL = Set.of(
            "X-Lc-Billing-Scope-Kind",
            "X-Lc-Billing-Scope-Id",
            "X-Lc-Billing-Step-Id",
            "X-Lc-Generation-Model",
            "X-Lc-Generation-Quantity",
            "X-Lc-Generation-Unit"
    );

    /** True when {@code name} is one of them, whatever its casing. */
    public static boolean contains(String name) {
        if (name == null) {
            return false;
        }
        return ALL.stream().anyMatch(header -> header.equalsIgnoreCase(name));
    }
}
