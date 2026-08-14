package com.apimarketplace.catalog.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WHICH of the caller's own keys runs a call.
 *
 * <p>An account can hold several keys for the same provider, and until this
 * existed the choice could not travel: every surface resolved the integration's
 * DEFAULT key, so a picker that offered the other ones was choosing something
 * the run then ignored, silently and with no way to tell from the result.
 *
 * <p>The rule has two halves and both are load-bearing. What counts as an id
 * (nothing else may be mistaken for one), and WHEN it is sent at all: the
 * catalog reads a pinned id only on the {@code 'user'} branch, so emitting it
 * anywhere else states a choice the run cannot honour.
 */
@DisplayName("CatalogExecuteModule - the caller's pinned own key")
class CatalogExecuteModulePinnedCredentialTest {

    /** A caller's tool arguments: the source is stated here, the pin never is. */
    private static Map<String, Object> request(Object source) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (source != null) parameters.put("credential_source", source);
        return parameters;
    }

    /**
     * The trusted channel. Only the app dialog and the workflow node put a pin
     * here, through GenerationController; an agent's arguments cannot reach it.
     */
    private static ToolExecutionContext context(Object credentialId) {
        Map<String, Object> credentials = new LinkedHashMap<>();
        if (credentialId != null) credentials.put("__credentialId__", credentialId);
        return new ToolExecutionContext("tenant-1", credentials, Map.of(), java.util.Set.of(),
                null, null, null, null);
    }

    @Nested
    @DisplayName("what counts as an id")
    class WhatCountsAsAnId {

        @Test
        @DisplayName("a positive whole number, whether it arrives as a number or as text")
        void positiveWholeNumbers() {
            // The node's params carry it as a number; a hand-written plan and a
            // resolved template both carry it as text. Both are the same choice.
            assertThat(CatalogExecuteModule.normalizedCredentialId(42L))
                    .isEqualTo(42L);
            assertThat(CatalogExecuteModule.normalizedCredentialId(42))
                    .isEqualTo(42L);
            assertThat(CatalogExecuteModule.normalizedCredentialId(" 42 "))
                    .isEqualTo(42L);
        }

        @Test
        @DisplayName("nothing at all means the account's default key, not an id of zero")
        void absenceMeansTheDefault() {
            assertThat(CatalogExecuteModule.normalizedCredentialId(null)).isNull();
        }

        @Test
        @DisplayName("an unusable value falls back to the default rather than travelling as a credential nobody owns")
        void unusableValuesFallBack() {
            // A template that resolved to nothing, a placeholder, a negative id:
            // each would reach the catalog as a credential the account does not
            // have, and the call would be refused for a reason that has nothing
            // to do with what the caller got wrong.
            assertThat(CatalogExecuteModule.normalizedCredentialId("")).isNull();
            assertThat(CatalogExecuteModule.normalizedCredentialId("   ")).isNull();
            assertThat(CatalogExecuteModule.normalizedCredentialId("abc")).isNull();
            assertThat(CatalogExecuteModule.normalizedCredentialId(0)).isNull();
            assertThat(CatalogExecuteModule.normalizedCredentialId(-7)).isNull();
        }
    }

    @Nested
    @DisplayName("when it is sent")
    class WhenItIsSent {

        @Test
        @DisplayName("beside credential_source='user', which is the only branch that reads it")
        void sentOnTheUserBranch() {
            assertThat(CatalogExecuteModule.pinnedUserCredentialId(request("user"), context(42L))).isEqualTo(42L);
            // Same casing and padding tolerance as the source itself, or the two
            // would disagree about which branch this call is on.
            assertThat(CatalogExecuteModule.pinnedUserCredentialId(request("  USER "), context(42L))).isEqualTo(42L);
        }

        @Test
        @DisplayName("never beside 'platform': the platform's own key runs, so pinning one of yours says nothing")
        void neverOnThePlatformBranch() {
            assertThat(CatalogExecuteModule.pinnedUserCredentialId(request("platform"), context(42L))).isNull();
        }

        @Test
        @DisplayName("never when no source was stated, where the catalog is free to answer from either pool")
        void neverWhenTheSourceIsUnstated() {
            // An unstated source means "try the caller's own key, fall back to
            // the platform's". Sending a pinned id there would pin the first
            // half of an arrangement whose second half is a different pool
            // entirely, and the catalog would ignore it anyway.
            assertThat(CatalogExecuteModule.pinnedUserCredentialId(request(null), context(42L))).isNull();
            // A typo in the source is read as "no choice", so the id it carries
            // must not survive either.
            assertThat(CatalogExecuteModule.pinnedUserCredentialId(request("borrowed"), context(42L))).isNull();
        }
    }

    @Nested
    @DisplayName("what goes on the wire")
    class WhatGoesOnTheWire {

        private Map<String, Object> wire(Object source, Object credentialId) {
            Map<String, Object> body = new LinkedHashMap<>();
            CatalogExecuteModule.applyCredentialChoice(body, request(source), context(credentialId));
            return body;
        }

        @Test
        @DisplayName("under the names the catalog route declares, which are NOT the names used on this side")
        void usesTheReceivingEndsNames() {
            // This is the joint between two services, and getting it wrong
            // fails silently: an unknown field is dropped, the catalog applies
            // its default, and the picker goes back to deciding nothing while
            // still being on screen. Every other test here would still pass.
            Map<String, Object> body = wire("user", 42L);

            assertThat(body).containsEntry("credentialSource", "user");
            assertThat(body).containsEntry("selectedCredentialId", 42L);
            // The names this side speaks must not leak through as well: the
            // route reads neither, so they would be dead weight that looks like
            // the real thing to the next reader.
            assertThat(body).doesNotContainKeys("credential_source", "credential_id");
        }

        @Test
        @DisplayName("a platform call carries the source and no key of the caller's")
        void platformCarriesNoPinnedKey() {
            Map<String, Object> body = wire("platform", 42L);

            assertThat(body).containsEntry("credentialSource", "platform");
            assertThat(body).doesNotContainKey("selectedCredentialId");
        }

        @Test
        @DisplayName("an agentic call with no stated source carries neither field")
        void anUnstatedSourceCarriesNothing() {
            Map<String, Object> body = wire(null, 42L);

            assertThat(body).isEmpty();
        }
    }

    @Test
    @DisplayName("a caller's own credential_id argument is IGNORED: only the trusted channel can pin a key")
    void aCallerSuppliedCredentialIdIsIgnored() {
        // An agent has no way to learn a credential id, and the help says so.
        // Reading one out of its arguments would make that sentence false and
        // let a guessed number decide which of the account's keys runs.
        Map<String, Object> parameters = request("user");
        parameters.put("credential_id", 42L);

        assertThat(CatalogExecuteModule.pinnedUserCredentialId(parameters, context(null))).isNull();

        Map<String, Object> body = new LinkedHashMap<>();
        CatalogExecuteModule.applyCredentialChoice(body, parameters, context(null));
        assertThat(body).doesNotContainKey("selectedCredentialId");
    }

    @Test
    @DisplayName("credential_id is a control key: it names an account object and never reaches the provider")
    void credentialIdIsNeverAToolInput() {
        // Left out of the reserved set it would be merged into the upstream
        // request as an unknown parameter, and a correctly configured call would
        // be refused by the provider for a field the caller never wrote.
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("tool_id", "seedance/create-video");
        parameters.put("credential_source", "user");
        parameters.put("credential_id", 42L);
        parameters.put("prompt", "a boat");

        Map<String, Object> input = CatalogExecuteModule.toolInputsFor(parameters);

        assertThat(input).doesNotContainKey("credential_id");
        assertThat(input).doesNotContainKey("credential_source");
        assertThat(input).containsEntry("prompt", "a boat");
    }
}
