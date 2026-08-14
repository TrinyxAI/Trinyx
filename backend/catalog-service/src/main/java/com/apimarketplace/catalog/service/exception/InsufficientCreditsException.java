package com.apimarketplace.catalog.service.exception;

/**
 * Thrown when the pre-flight markup reservation is refused, i.e. the account
 * cannot pay for the call the caller just asked for.
 *
 * <p>Raised BEFORE the upstream request is dispatched, which is the whole point:
 * a resold call that cannot be billed must not be made at all. Recording the
 * refusal after the provider already produced (and charged the platform owner
 * for) a video is a revenue loss, not a guard.
 *
 * <p>Carries {@code delinquent} so the surface can tell "top up to resume"
 * (an unpaid balance) apart from "not enough credits for this call". The
 * controller maps it to HTTP 402, matching the shape the CE relay already
 * returns for the same condition ({@code error} + {@code delinquent}).
 */
public class InsufficientCreditsException extends CatalogServiceException {

    /** Machine-readable code, identical to the CE relay's 402 body. */
    public static final String ERROR_CODE = "INSUFFICIENT_CREDITS";

    private final boolean delinquent;

    public InsufficientCreditsException(String message, boolean delinquent) {
        super(message != null ? message : "Insufficient credits", ERROR_CODE);
        this.delinquent = delinquent;
    }

    public boolean isDelinquent() {
        return delinquent;
    }
}
