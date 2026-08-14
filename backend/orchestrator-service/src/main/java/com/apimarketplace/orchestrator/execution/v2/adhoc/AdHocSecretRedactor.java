package com.apimarketplace.orchestrator.execution.v2.adhoc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Strips credential material out of an ad-hoc node configuration before anything persists it.
 *
 * <p><b>Why this is a prerequisite and not a refinement.</b> Everywhere else on the platform a
 * secret reaches a node by REFERENCE: the config carries a {@code credentialId} and the node
 * resolves it at run time. {@code run_node} is the first surface where an agent can pass the
 * secret itself inline (an ssh password, a Postgres DSN, an SMTP password) because it is
 * building the config in the call.
 *
 * <p><b>Scope, precisely.</b> This covers the ECHO: the config handed back in the tool result and
 * stored in the conversation. It does NOT cover the tool ARGUMENTS, which observability persists
 * verbatim as jsonb on its own row - that sink is generic and shared by every tool. So an inline
 * secret is still written there in clear, and the agent-facing help says to reference a stored
 * credential by id instead of pasting one. Redacting the echo is worth doing on its own (the
 * conversation is what a human reads back), but it is not the whole exposure and must not be
 * described as if it were.
 *
 * <p>Matching is on the KEY, not the value: a value-based heuristic on something like a DSN is
 * a guessing game, while the keys are a short, stable, known list. Unknown key, untouched
 * value: this must never quietly mangle a legitimate payload.
 */
public final class AdHocSecretRedactor {

    /** What replaces a redacted value. Recognisable, and obviously not the real thing. */
    public static final String REDACTED = "[redacted]";

    /**
     * Key fragments that mark credential material. Substring match, case-insensitive.
     *
     * <p>{@code key} is deliberately absent as a bare fragment: it would swallow innocent keys
     * such as {@code sortKey} or {@code partitionKey}. The specific key-bearing names are listed
     * instead, the same trade-off the shell tool's env scrub makes.
     */
    private static final List<String> SECRET_KEY_FRAGMENTS = List.of(
            "password", "passwd", "secret", "token", "credential",
            "apikey", "api_key", "accesskey", "access_key", "privatekey", "private_key",
            "passphrase", "dsn", "connectionstring", "connection_string",
            "authorization", "auth_header", "client_secret", "refresh_token", "access_token",
            "sessionkey", "session_key", "signingkey", "signing_key",
            // HttpAuthConfig carries the whole header line here, e.g. "Bearer sk_live_…".
            "headervalue", "header_value",
            // Session material travelling as a header value under an innocent name.
            "cookie", "signature", "xauth", "x_auth"
    );

    /**
     * Keys that NAME a secret without being one.
     *
     * <p>{@code credentialId} is the platform's normal way to reference a stored credential and
     * appears on ssh / sftp / database / send_email / email_inbox. Redacting it would be worse
     * than useless: the echoed config is meant to be pasted straight into {@code add_node}, and
     * with the reference blanked out it is no longer pasteable for exactly the nodes that need
     * one. Same for a header NAME.
     */
    private static final List<String> NEVER_SECRET_KEYS = List.of(
            "credentialid", "credential_id", "credentialname", "credential_name",
            "apikeyname", "api_key_name", "apikeyheader", "api_key_header", "apikeylocation", "api_key_location",
            "tokenurl", "token_url", "tokenendpoint", "token_endpoint");

    /**
     * Key names that make the SIBLING {@code value} of a {@code {key,value}} pair a secret.
     *
     * <p>Http headers and query parameters are modelled as {@code HttpParam{key,value}} lists, so
     * the secret sits under a key literally called {@code value} and key-name matching cannot see
     * it. This is what lets an {@code Authorization: Bearer …} header through.
     */
    private static final List<String> PAIR_KEY_NAMES = List.of("key", "name", "header");

    /**
     * The shape of an {@code HttpParam}: a header or query parameter. The pair rule applies ONLY
     * to a map that looks like one.
     *
     * <p>Without this bound the rule misfires badly: {@code CryptoJwtConfig} also carries a
     * {@code key} and a {@code value} at the same level, so a signing key whose text happened to
     * contain "key" made the redactor blank the PAYLOAD and keep the KEY, which is the exact
     * inverse of what is wanted.
     */
    private static final java.util.Set<String> HTTP_PARAM_SHAPE =
            java.util.Set.of("key", "value", "enabled", "description", "name", "header");

    /**
     * Fields that hold credential material under a name the generic fragments cannot catch,
     * per node type.
     *
     * <p>{@code crypto_jwt.key} is the case that forced this: it is the HMAC/RSA signing key,
     * under a name ({@code key}) deliberately excluded from the fragment list because bare "key"
     * would swallow {@code sortKey} and {@code partitionKey}. A generic rule cannot tell those
     * apart; the node type can.
     */
    private static final Map<String, java.util.Set<String>> SECRET_FIELDS_BY_TYPE = Map.of(
            "crypto_jwt", java.util.Set.of("key", "secret", "token"));

    /**
     * Connection URLs carry the password inline ({@code postgres://user:pw@host}). The key alone
     * does not say so, hence a value-shaped rule for this one case: userinfo in a URL is
     * unambiguous, unlike a free-form string.
     */
    private static final Pattern URL_WITH_CREDENTIALS =
            Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]*://)([^/\\s:@]+):([^/\\s@]+)@");

    /**
     * A secret carried in a query string ({@code ?api_key=…}). The key name is right there in the
     * URL, so it is matchable, unlike free-form text.
     */
    private static final Pattern URL_QUERY_SECRET = Pattern.compile(
            "(?i)([?&](?:[a-z0-9_-]*(?:token|secret|password|passwd|api_?key|access_?key|signature)[a-z0-9_-]*))=[^&\\s]+");

    private AdHocSecretRedactor() {
    }

    /** Deep copy with every credential-looking value replaced. Never mutates the input. */
    @SuppressWarnings("unchecked")
    public static Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            boolean secretPair = isSecretNamedPair(map);
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                boolean redactThis = isSecretKey(key)
                        || (secretPair && "value".equalsIgnoreCase(key));
                out.put(key, redactThis ? REDACTED : redact(e.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(redact(item));
            }
            return out;
        }
        if (value instanceof String s) {
            return redactUrlCredentials(s);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> redactMap(Map<String, Object> map) {
        if (map == null) return null;
        return (Map<String, Object>) redact(map);
    }

    /**
     * Type-aware redaction. Some credential fields are only recognisable from the node type:
     * {@code crypto_jwt.key} is a signing key, while {@code sortKey} on any other node is not a
     * secret at all, and the field name alone cannot tell them apart.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> redactMap(Map<String, Object> map, String nodeType) {
        if (map == null) return null;
        Map<String, Object> out = (Map<String, Object>) redact(map);
        java.util.Set<String> extra = SECRET_FIELDS_BY_TYPE.get(nodeType);
        if (extra != null) {
            for (String field : extra) {
                if (out.containsKey(field)) out.put(field, REDACTED);
            }
        }
        return out;
    }

    static boolean isSecretKey(String key) {
        if (key == null) return false;
        String normalized = key.toLowerCase(Locale.ROOT).replace("-", "_");
        String collapsed = normalized.replace("_", "");
        for (String allowed : NEVER_SECRET_KEYS) {
            if (collapsed.equals(allowed.replace("_", ""))) {
                return false;
            }
        }
        for (String fragment : SECRET_KEY_FRAGMENTS) {
            if (normalized.contains(fragment) || collapsed.contains(fragment.replace("_", ""))) {
                return true;
            }
        }
        return false;
    }

    /**
     * True for a {@code {key,value}} pair whose key NAMES a secret, so the value must go.
     *
     * <p>This is the header/query-parameter shape: the secret lives under a field literally
     * called {@code value}, which no amount of key-name matching can catch.
     */
    static boolean isSecretNamedPair(Map<?, ?> map) {
        if (!map.containsKey("value")) return false;
        // Only a header/query-parameter-shaped map. Anything richer is a node config that
        // happens to own a `key` and a `value`, where this rule redacts the wrong field.
        for (Object k : map.keySet()) {
            if (!HTTP_PARAM_SHAPE.contains(String.valueOf(k).toLowerCase(Locale.ROOT))) return false;
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey()).toLowerCase(Locale.ROOT);
            if (PAIR_KEY_NAMES.contains(key) && e.getValue() != null && isSecretKey(String.valueOf(e.getValue()))) {
                return true;
            }
        }
        return false;
    }

    /** Replace only the password segment, so the host stays readable for diagnosis. */
    static String redactUrlCredentials(String value) {
        if (value == null || value.isEmpty()) return value;
        String out = URL_WITH_CREDENTIALS.matcher(value).replaceAll("$1$2:" + REDACTED + "@");
        return URL_QUERY_SECRET.matcher(out).replaceAll("$1=" + REDACTED);
    }
}
