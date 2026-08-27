package com.apimarketplace.catalog.service.http;

/**
 * Thread-bound holder for per-call credential resolution hints. Set by
 * {@link com.apimarketplace.catalog.web.CatalogV1Controller} from the request
 * DTO and read by
 * {@link HttpExecutionService#tryGetCredentialResolution(String, String,
 * com.apimarketplace.catalog.domain.ApiEntity)} and
 * {@link com.apimarketplace.catalog.service.ApiService#executeApiTool}.
 *
 * <p>Three hints flow through here:
 * <ul>
 *   <li><b>{@code explicitSource}</b> - workflow direct calls supply
 *       {@code "user"} or {@code "platform"} from the workflow node's UI
 *       toggle. Strictly honored: no fallback to the other pool.</li>
 *   <li><b>{@code selectedCredentialId}</b> - workflow direct calls with
 *       {@code explicitSource="user"} can pin a concrete user credential id.
 *       Catalog execute and agentic paths leave this null and still use
 *       default-by-integration resolution.</li>
 *   <li><b>{@code agenticOverride}</b> - agentic call paths (chat agents,
 *       image-gen) historically supplied {@code "both"} to enable
 *       user-then-platform fallback. Now an implementation detail; agentic
 *       paths can leave this null and the resolver applies the default
 *       fallback-if-priced behavior.</li>
 * </ul>
 *
 * <p>Why a thread-local: the hints are cross-cutting per-request state.
 * Threading them through {@code ApiService.executeApiTool} → {@code
 * HttpExecutionService.execute*} would touch ~6 method signatures for what
 * is conceptually request-scoped data. Catalog tool execution is fully
 * synchronous in one HTTP thread (no async hops), so the thread-local is
 * cleaned up by the controller's {@code finally} block before the response
 * leaves the service.
 *
 * <p>Mirrors the existing pattern in {@code mapping.adapter.JsonAdapter#DOC_ROOT}.
 */
public final class CredentialModeContext {

    private static final ThreadLocal<String> AGENTIC_OVERRIDE = new ThreadLocal<>();
    private static final ThreadLocal<String> EXPLICIT_SOURCE = new ThreadLocal<>();
    private static final ThreadLocal<Long> SELECTED_CREDENTIAL_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> SELECTED_CREDENTIAL_NAME = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SELECTION_STRICT = new ThreadLocal<>();

    /** @see #rememberPinVerdict(String, boolean) */
    private static final ThreadLocal<String> PIN_VERDICT = new ThreadLocal<>();

    /** @see #rememberNamedCredentialVerdict(String, Long, boolean) */
    private static final ThreadLocal<String> NAME_VERDICT = new ThreadLocal<>();

    /** Stands for "this name matched nothing", so a refusal is cached like a match. */
    private static final String NO_MATCH = "none";
    /**
     * A refusal caused by TWO matches rather than none.
     *
     * <p>Both refuse the call, so both resolve to a null id, but they need different
     * words: "no credential is named that" sends an agent hunting a spelling mistake
     * when the name was spelled correctly and the workspace holds two of them. The
     * distinction is remembered here rather than recomputed, because the memo means
     * the losing branch has no candidate list left to count.
     */
    private static final String AMBIGUOUS = "ambiguous";

    /**
     * Whitelist of agentic-override values an external caller may supply.
     * Limited to the fallback-enabling value only - accepting
     * {@code "platform_key"} or {@code "user_key"} here would let any
     * authenticated caller force a platform-credential lookup against an API
     * whose stored mode is {@code "user_key"}, bypassing the workflow-author's
     * deliberate choice and consuming platform-funded API access without
     * paying. {@code "both"} is benign: it is a strict superset of
     * {@code "user_key"} (still tries the user's credential first) and
     * degrades to {@code "user_key"} behavior when no platform credential is
     * configured.
     *
     * <p>The explicit source path ({@link #setExplicitSource}) is a different
     * security model: it is authenticated as a workflow direct call (the
     * workflow node's UI toggle is itself gated on platform-credential
     * pricing publication), so accepting {@code "user"}/{@code "platform"}
     * from that path is safe.
     */
    private static final java.util.Set<String> AGENTIC_ALLOWED = java.util.Set.of("both");

    private static final java.util.Set<String> EXPLICIT_ALLOWED = java.util.Set.of("user", "platform");

    private CredentialModeContext() {}

    /**
     * Sets the agentic-override for the current thread, silently ignoring
     * values outside {@link #AGENTIC_ALLOWED}. Null and blank inputs clear
     * any prior value.
     */
    public static void setOverride(String mode) {
        if (mode == null || mode.isBlank()) {
            AGENTIC_OVERRIDE.remove();
            return;
        }
        if (!AGENTIC_ALLOWED.contains(mode)) {
            // Reject silently - do not echo the rejected value into logs at
            // INFO level (could be a probe). Trace at DEBUG for diagnostics.
            org.slf4j.LoggerFactory.getLogger(CredentialModeContext.class)
                    .debug("Rejected unsupported credentialModeOverride='{}'; allowed={}", mode, AGENTIC_ALLOWED);
            AGENTIC_OVERRIDE.remove();
            return;
        }
        AGENTIC_OVERRIDE.set(mode);
    }

    public static String getOverride() {
        return AGENTIC_OVERRIDE.get();
    }

    /**
     * Sets the workflow node's explicit credential source for the current
     * thread. {@code "user"} or {@code "platform"} only. Other values are
     * silently dropped. Strictly honored by the resolver: no fallback to the
     * other pool.
     */
    public static void setExplicitSource(String source) {
        if (source == null || source.isBlank()) {
            EXPLICIT_SOURCE.remove();
            return;
        }
        String normalized = source.toLowerCase(java.util.Locale.ROOT);
        if (!EXPLICIT_ALLOWED.contains(normalized)) {
            org.slf4j.LoggerFactory.getLogger(CredentialModeContext.class)
                    .debug("Rejected unsupported credentialSource='{}'; allowed={}", source, EXPLICIT_ALLOWED);
            EXPLICIT_SOURCE.remove();
            return;
        }
        EXPLICIT_SOURCE.set(normalized);
    }

    public static String getExplicitSource() {
        return EXPLICIT_SOURCE.get();
    }

    /**
     * Pins workflow user-credential resolution to a concrete credential row.
     * Only honored when {@link #getExplicitSource()} is {@code "user"}; catalog
     * execute and agentic paths continue to resolve by integration/default.
     */
    public static void setSelectedCredentialId(Long credentialId) {
        if (credentialId == null || credentialId <= 0L) {
            SELECTED_CREDENTIAL_ID.remove();
            return;
        }
        SELECTED_CREDENTIAL_ID.set(credentialId);
    }

    public static Long getSelectedCredentialId() {
        return SELECTED_CREDENTIAL_ID.get();
    }

    /**
     * Pins user-credential resolution to a credential the caller named rather
     * than numbered.
     *
     * <p>A name is what a workflow author actually has to hand when the choice is
     * made at run time: it comes out of a table row, a trigger field or a split
     * item, none of which know database ids. Resolving it is this service's job
     * and not the caller's, because deciding whether a name belongs to the
     * endpoint's integration needs the requirement, which only the catalog knows.
     *
     * <p>Only honored when {@link #getExplicitSource()} is {@code "user"}, same
     * as {@link #setSelectedCredentialId}.
     */
    public static void setSelectedCredentialName(String credentialName) {
        if (credentialName == null || credentialName.isBlank()) {
            SELECTED_CREDENTIAL_NAME.remove();
            return;
        }
        SELECTED_CREDENTIAL_NAME.set(credentialName.trim());
    }

    public static String getSelectedCredentialName() {
        return SELECTED_CREDENTIAL_NAME.get();
    }

    /**
     * Whether the caller's credential choice was made FOR THIS RUN and must
     * therefore refuse rather than degrade.
     *
     * <p>The default, and the behaviour of every author-time pin, is forgiving: a
     * pin that cannot be verified is read as "no pin" and the call proceeds on the
     * integration's default key, so a credential deleted months after a workflow
     * was written does not break it. That is right for a choice made once, at
     * design time, that someone can go and correct.
     *
     * <p>It is wrong for a choice made per run. "Use the account named in this
     * row" cannot degrade into "use whichever account is the default": the call
     * would succeed, against the wrong account, and report success. When this flag
     * is set, an unresolvable choice refuses the call instead.
     */
    public static void setSelectionStrict(Boolean strict) {
        if (Boolean.TRUE.equals(strict)) {
            SELECTION_STRICT.set(Boolean.TRUE);
        } else {
            SELECTION_STRICT.remove();
        }
    }

    public static boolean isSelectionStrict() {
        return Boolean.TRUE.equals(SELECTION_STRICT.get());
    }

    /**
     * Whether the pinned credential was found to belong to the integration
     * being called, remembered for the rest of THIS request.
     *
     * <p>Five helpers resolve a credential during one execution (the scope
     * preflight, the token, the token info, the data map behind URL template
     * substitution, and the refresh after a 401), and each one asks the same
     * question. Without this they each make their own call to auth-service, so
     * one execution pays four extra round trips AND can get four different
     * answers: a blip mid-request would have some helpers honour the pin and
     * others fall back to the default key, inside a single call. One lookup,
     * one verdict.
     */
    public static void rememberPinVerdict(String credentialName, boolean belongsToIntegration) {
        PIN_VERDICT.set(credentialName + "=" + belongsToIntegration);
    }

    /**
     * The verdict already reached this request FOR THIS REQUIREMENT, or null.
     *
     * <p>Keyed on the requirement, not just on the thread. One execution
     * resolves a single credential name today, so an unkeyed cache would be
     * correct by accident; the day a tool needs two, the first answer would
     * stand in for the second and a pin validated against one provider would
     * be honoured for another. That is the failure this cache exists to
     * prevent, so it must not be the failure it introduces.
     */
    public static Boolean getPinVerdict(String credentialName) {
        String verdict = verdictFor(PIN_VERDICT.get(), credentialName);
        return verdict == null ? null : Boolean.valueOf(verdict);
    }

    /**
     * Which credential a NAME resolved to, remembered for the rest of THIS request.
     *
     * <p>Exactly the reason {@link #rememberPinVerdict} exists, and the reason it is
     * needed even more here. Five helpers resolve a credential during one execution
     * and every one of them asks this question; the id path answers it from one
     * cached verdict, while the name path would otherwise fetch the caller entire
     * credential list each time - several round trips per step, on the hot path of
     * every catalog call. Worse, an auth-service blip between two of them could have
     * one helper resolve the name and another fall through, inside a single call.
     *
     * <p>A refusal is cached too: "this name matched nothing" must be as stable
     * within one request as a match, or the same call could refuse once and proceed
     * once. {@code ambiguous} distinguishes the two refusals: it means the name matched
     * more than one active credential of the integration, so none was used. Both read
     * as a refusal ({@link #namedCredentialId} answers null for either) and they differ
     * only in what the refusal is allowed to tell the author.
     */
    public static void rememberNamedCredentialVerdict(String credentialName, Long resolvedId,
                                                      boolean ambiguous) {
        String verdict = resolvedId != null
                ? String.valueOf(resolvedId)
                : (ambiguous ? AMBIGUOUS : NO_MATCH);
        NAME_VERDICT.set(credentialName + "=" + verdict);
    }

    /**
     * Whether this request's refusal was "two credentials carry that name", not "none does".
     *
     * <p>False when the name resolved, when it matched nothing, and when nothing has been
     * asked yet: only a remembered ambiguous verdict answers true.
     */
    public static boolean namedCredentialWasAmbiguous(String credentialName) {
        return AMBIGUOUS.equals(rememberedVerdictFor(credentialName));
    }

    /**
     * Whether this request already resolved THIS REQUIREMENT by name.
     *
     * <p>Keyed on the endpoint REQUIREMENT (the {@code credentialName} argument, e.g.
     * {@code instagram-credential}), not on the name the caller chose, for the same
     * reason {@link #getPinVerdict} is: one execution resolves a single requirement
     * today, and an unkeyed memo would be correct by accident until the day a tool
     * needs two. It holds one entry: a second requirement replaces the first rather
     * than accumulating, which is correct but is why this is a memo and not a cache.
     */
    public static boolean hasNamedCredentialVerdict(String credentialName) {
        return rememberedVerdictFor(credentialName) != null;
    }

    /**
     * The id this request resolved FOR THIS REQUIREMENT, or null when it resolved to
     * no match. Only meaningful once {@link #hasNamedCredentialVerdict} is true:
     * asking before that also answers null, which is why the two are separate.
     *
     * <p>Two methods rather than a nullable Optional. The tri-state (not asked / no
     * match / matched) does need three answers, but a method that can return null OR
     * an empty Optional is the one shape a caller writes .orElse() against without
     * thinking, and the null then throws where the code reads as total.
     */
    public static Long namedCredentialId(String credentialName) {
        String value = rememberedVerdictFor(credentialName);
        if (value == null || NO_MATCH.equals(value) || AMBIGUOUS.equals(value)) {
            return null;
        }
        return Long.valueOf(value);
    }

    private static String rememberedVerdictFor(String credentialName) {
        return verdictFor(NAME_VERDICT.get(), credentialName);
    }

    /**
     * The verdict half of a {@code name=verdict} entry, or null when it is another name's.
     *
     * <p>Split at the LAST {@code '='}, not by prefix. Both verdict halves are drawn from
     * a fixed vocabulary that contains no {@code '='} (true/false, none/ambiguous, digits)
     * while a requirement may contain one, and a prefix test then lets the entry for
     * {@code a=b} answer for {@code a} and hand {@code b=none} to a parser. Shared by both
     * memos so the two cannot drift: the name memo was hardened first, and this one sat
     * twenty lines above with the same defect.
     */
    private static String verdictFor(String remembered, String credentialName) {
        if (remembered == null || credentialName == null) {
            return null;
        }
        int boundary = remembered.lastIndexOf('=');
        if (boundary < 0 || !remembered.substring(0, boundary).equals(credentialName)) {
            return null;
        }
        return remembered.substring(boundary + 1);
    }

    public static void clear() {
        AGENTIC_OVERRIDE.remove();
        EXPLICIT_SOURCE.remove();
        SELECTED_CREDENTIAL_ID.remove();
        SELECTED_CREDENTIAL_NAME.remove();
        SELECTION_STRICT.remove();
        PIN_VERDICT.remove();
        NAME_VERDICT.remove();
    }
}
