package com.apimarketplace.catalog.service.exception;

/**
 * The caller chose a credential FOR THIS RUN and it could not be matched, so the
 * call was refused instead of being run on a different account.
 *
 * <p>This is not an authentication failure: nothing was sent, and the external
 * API never saw the request. It is a refusal to substitute. Everywhere else in
 * credential resolution a choice that cannot be honoured is softened into the
 * integration's default key, deliberately, so that a credential deleted long
 * after a workflow was written does not break it. That trade is only sound for a
 * choice made once at design time, which someone can go and correct.
 *
 * <p>A choice made per run cannot be softened the same way. "Publish to the
 * account named in this row" degraded into "publish to whichever account is the
 * default" is a call that succeeds, against the wrong account, and reports
 * success - the failure mode that is impossible to notice and expensive to
 * discover. Hence a distinct exception, and a distinct status: the request is
 * well-formed, the platform simply will not guess.
 */
public class CredentialSelectionException extends CatalogServiceException {

    public static final String ERROR_CODE = "CREDENTIAL_SELECTION_UNRESOLVED";

    public CredentialSelectionException(String message) {
        super(message, ERROR_CODE);
    }
}
