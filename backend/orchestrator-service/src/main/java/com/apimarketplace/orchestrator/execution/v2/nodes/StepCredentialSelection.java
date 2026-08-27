package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Step;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WHICH credential a catalog step runs on, decided once, in one place.
 *
 * <p>A step answers that question in one of two modes, and only one of them can
 * be live at a time:
 *
 * <ul>
 *   <li><b>Static</b> - the answer was known when the workflow was written. This
 *       is every step that exists today: {@code selectedCredentialId}, or nothing
 *       at all and the account's default key for the integration.</li>
 *   <li><b>Dynamic</b> - the answer is only known at run time, so the step carries
 *       an expression ({@code credentialSelector}) that resolves to a credential
 *       id or to a credential NAME. This is how one workflow publishes to several
 *       accounts of the same provider without being duplicated.</li>
 * </ul>
 *
 * <h2>Why a dynamic selection must fail rather than fall back</h2>
 *
 * Everything downstream of this class is deliberately forgiving: an id that
 * cannot be verified is read as "no pin" and the call proceeds on the integration
 * default key ({@code HttpExecutionService.selectedUserCredentialId}, whose
 * javadoc says outright that the refusal it deserves belongs upstream, where the
 * choice is made and can be corrected). That is the right behaviour for a stale
 * id an author pinned months ago: the run keeps working.
 *
 * <p>It is the wrong behaviour for a selection made at run time. "Publish to the
 * account named in this row" resolving to nothing does not mean "publish to
 * whichever account is the default", it means the run is about to act on the
 * wrong account, with a 200 and a green step to show for it. This class is that
 * missing upstream: when the author asked for a dynamic selection and it does not
 * resolve, the step FAILS, naming the expression and what it resolved to.
 *
 * <p>Instances are produced by {@link #resolve} and are pure: no services, no
 * context, no I/O, so every branch below is unit-testable on its own.
 */
record StepCredentialSelection(
        String source,
        Long platformCredentialId,
        Long selectedCredentialId,
        String selectedCredentialName,
        boolean strict,
        String error) {

    static final String SOURCE_USER = "user";
    static final String SOURCE_PLATFORM = "platform";

    /** What a template leaves behind when it resolved to nothing. */
    private static final String UNRESOLVED_MARKER = "{{";

    /**
     * The credential decision for this step.
     *
     * @param step             the step as written by its author
     * @param resolvedSelector {@code step.credentialSelector()} after template
     *                         resolution, or null when the step has no selector
     */
    static StepCredentialSelection resolve(Step step, String resolvedSelector) {
        if (!step.hasCredentialSelector()) {
            return staticSelection(step);
        }
        // The two modes answer the same question, so they cannot both be live.
        // The builder cannot produce this pair; a hand-written or agent-built plan
        // can, and silently ignoring one half is what this class exists to prevent.
        if (step.usesPlatformCredential()) {
            return failure("Step " + quoted(step.label()) + " selects its credential dynamically ("
                    + step.credentialSelector() + ") but is also set to run on a platform credential. "
                    + "A dynamic selection chooses among the credentials the account owns; "
                    + "remove one of the two.");
        }
        if (step.credentialSelector().isEmpty()) {
            return failure("Step " + quoted(step.label()) + " is set to choose its account at run "
                    + "time but carries no expression to choose it with. The step was not run: "
                    + "either fill the expression in, or switch the step back to a fixed account.");
        }
        String value = resolvedSelector == null ? null : resolvedSelector.trim();
        if (value == null || value.isEmpty() || value.contains(UNRESOLVED_MARKER)) {
            return failure("Step " + quoted(step.label()) + " selects its credential dynamically from "
                    + quoted(step.credentialSelector()) + ", which resolved to "
                    + (value == null || value.isEmpty() ? "nothing" : quoted(value))
                    + ". The step was not run: continuing would have used the account default "
                    + "credential for this integration, which is a different account from the one "
                    + "the workflow asked for.");
        }
        Long id = positiveId(value);
        if (id != null) {
            return new StepCredentialSelection(SOURCE_USER, null, id, null, true, null);
        }
        return new StepCredentialSelection(SOURCE_USER, null, null, value, true, null);
    }

    /**
     * The pre-existing behaviour, unchanged and deliberately kept in one piece so a
     * reader can see that a step without a selector is decided exactly as it was
     * before this class existed.
     */
    private static StepCredentialSelection staticSelection(Step step) {
        if (step.usesPlatformCredential() && step.platformCredentialId() != null) {
            return new StepCredentialSelection(
                    SOURCE_PLATFORM, step.platformCredentialId(), null, null, false, null);
        }
        return new StepCredentialSelection(
                SOURCE_USER, null, step.selectedCredentialId(), null, false, null);
    }

    private static StepCredentialSelection failure(String message) {
        return new StepCredentialSelection(null, null, null, null, false, message);
    }

    private static String quoted(String value) {
        return "'" + value + "'";
    }

    /**
     * A credential id, or null when the value names a credential instead.
     *
     * <p>Same rule as {@code CatalogExecuteModule.normalizedCredentialId}: a
     * positive whole number is an id, anything else is a name. Keeping both
     * spellings on one field is what lets an author switch a step from the picker
     * (which writes ids) to an expression without rewriting anything.
     */
    private static Long positiveId(String value) {
        try {
            long id = Long.parseLong(value);
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    boolean isFailure() {
        return error != null;
    }

    /**
     * Writes the decision onto the markers the catalog gateway forwards.
     *
     * <p>Only ever adds keys the gateway already understands, plus the two the
     * dynamic mode needs. A static selection produces byte-identical markers to
     * the ones these nodes emitted before the selector existed.
     */
    void applyTo(Map<String, Object> billingIdentifiers) {
        if (isFailure()) {
            throw new IllegalStateException("applyTo called on a failed selection: " + error);
        }
        billingIdentifiers.put("__credentialSource__", source);
        if (platformCredentialId != null) {
            billingIdentifiers.put("__platformCredentialId__", platformCredentialId);
        }
        if (selectedCredentialId != null) {
            billingIdentifiers.put("__selectedCredentialId__", selectedCredentialId);
        }
        if (selectedCredentialName != null) {
            billingIdentifiers.put("__selectedCredentialName__", selectedCredentialName);
        }
        if (strict) {
            // Tells the catalog that this choice was made for THIS run and must not
            // be softened into the integration default.
            billingIdentifiers.put("__credentialSelectionStrict__", true);
        }
    }

    /**
     * What the run inspector shows for this step, so "which account did this
     * actually use" is answerable after the fact. Null in static mode, which keeps
     * the step output unchanged for every existing workflow.
     */
    Map<String, Object> describe(String rawSelector) {
        if (!strict) {
            return null;
        }
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("selector", rawSelector);
        if (selectedCredentialId != null) {
            described.put("resolved_credential_id", selectedCredentialId);
        }
        if (selectedCredentialName != null) {
            described.put("resolved_credential_name", selectedCredentialName);
        }
        return described;
    }
}
