package com.apimarketplace.auth.credential.domain;

/**
 * A rename the platform refuses to perform because it would change more than a label.
 *
 * <p>Carries a {@link Reason} rather than only a message so the HTTP layer can map it to a
 * status and a stable {@code code} the frontend switches on, instead of matching on prose.
 */
public class CredentialRenameRefusedException extends RuntimeException {

    /**
     * Why a rename was refused.
     */
    public enum Reason {
        /**
         * Another credential of the same owner could already be selected by the new name, so
         * after the rename two rows would answer to it.
         *
         * <p><b>Two readers select a credential by name, and either one is enough to refuse.</b>
         * <ul>
         *   <li>Auth's resolver ({@code CredentialService.findByNameIdentifyingIntegration})
         *       accepts a name as an identity when the row declares no {@code integration} or
         *       its integration IS that slug. Two rows for which that holds under one name make
         *       it pick between two different keys on sort order alone.</li>
         *   <li>Catalog's run-time selector ({@code HttpExecutionService.resolveCredentialIdNamed})
         *       matches the LABEL a person typed, trimmed and case-insensitively, among the
         *       credentials the endpoint's integration would offer. No slug is involved: two
         *       credentials of ONE provider sharing any label at all make it report the choice
         *       ambiguous, resolve nothing, and either fail the step or fall back to the account
         *       default, i.e. run on a key nobody chose.</li>
         * </ul>
         * So a label that identifies neither row is still refused when both rows belong to the
         * same provider. Saying only "a homonym is never refused" would be wrong, and would read
         * as an invitation to delete that half of the guard.
         *
         * <p><b>What is NOT refused:</b> a name held by a credential of a DIFFERENT provider that
         * neither row answers to. No reader can confuse those, and refusing them meant refusing a
         * rename over a row of an unrelated API, frequently invisible to the user.
         *
         * <p>The owner's rows, not the caller's: an org member can rename a credential someone
         * else shared, and the row that would be ambiguous at execution time belongs to the
         * owner. So the contending credential can sit in a workspace the caller cannot see, which
         * is why the copy says "of the same owner" and mentions another workspace, and why the
         * contending row's {@code integration} is logged rather than returned.
         */
        DUPLICATE_NAME("duplicate_name", 409),

        /**
         * The credential carries no {@code integration}, so its NAME is the only thing that
         * identifies it.
         *
         * <p>Two places match such a credential by name and nothing else: catalog-service
         * admits a PINNED credential whose integration is blank only when the requirement
         * matches its name ({@code HttpExecutionService.resolvePinnedCredentialOwnership}),
         * and the builder's picker applies the same rule
         * ({@code frontend/lib/credentials/credentialMatching.ts}). Renaming would therefore
         * detach every node that pinned it, silently, with the run falling back to the
         * account's default key. Refusing is the only outcome that keeps "a rename is a
         * relabel" true.
         */
        NAME_IS_IDENTITY("name_is_identity", 422);

        private final String code;
        private final int httpStatus;

        Reason(String code, int httpStatus) {
            this.code = code;
            this.httpStatus = httpStatus;
        }

        /** Stable machine-readable code sent to API callers. */
        public String code() {
            return code;
        }

        /** HTTP status this refusal maps to. */
        public int httpStatus() {
            return httpStatus;
        }
    }

    private final Reason reason;

    public CredentialRenameRefusedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
