package com.apimarketplace.agent.tools.credential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The offer both credential listings make to an agent.
 *
 * <p>Every entry named in that offer is one the agent may hand to a fail-closed step, so
 * an entry offered that the run-time matcher will refuse does not degrade to the default
 * account: it fails the run. These pin each refusal the matcher makes, and pin the two
 * places the mirror is deliberately imperfect, so a later reader can tell an intentional
 * under-offer from a bug.
 */
@DisplayName("SelectableAccounts: only the accounts a workflow step could actually run on")
class SelectableAccountsTest {

    @Nested
    @DisplayName("refusals the matcher makes")
    class Refusals {

        @Test
        @DisplayName("a non-active account is never offered, expiring included")
        void skipsNonActive() {
            // 'expiring' is the trap: the same listing describes it as "still works".
            List<Map<String, Object>> connected = List.of(
                    entry("Main", "instagram", "active", true),
                    entry("Usable", "instagram", "active", false),
                    entry("Expiring", "instagram", "expiring", false),
                    entry("Revoked", "instagram", "needs_reauth", false),
                    entry("Broken", "instagram", "error", false));

            assertThat(names(connected)).containsExactly("Usable");
        }

        @Test
        @DisplayName("a name two active accounts of one integration share is offered to neither")
        void skipsAmbiguousNames() {
            // Naming it selects neither, so offering it is offering a guaranteed failure.
            List<Map<String, Object>> connected = List.of(
                    entry("Twin", "instagram", "active", false),
                    entry(" twin ", "instagram", "active", false),
                    entry("Solo", "instagram", "active", false));

            assertThat(names(connected)).containsExactly("Solo");
        }

        @Test
        @DisplayName("the DEFAULT account counts toward a collision, because the matcher sees it too")
        void defaultCountsTowardCollision() {
            // The run-time candidate set is not filtered by is_default, so a non-default
            // sharing the default's name is ambiguous even though only one is offerable.
            List<Map<String, Object>> connected = List.of(
                    entry("Shared", "instagram", "active", true),
                    entry("Shared", "instagram", "active", false));

            assertThat(names(connected)).isEmpty();
        }

        @Test
        @DisplayName("a positive whole number is an id, so it can never be reached by name")
        void skipsPositiveNumericNames() {
            List<Map<String, Object>> connected = List.of(
                    entry("42", "instagram", "active", false),
                    entry("007", "instagram", "active", false),
                    entry(" 8 ", "instagram", "active", false));

            assertThat(names(connected)).isEmpty();
        }

        @Test
        @DisplayName("zero and a negative are NOT ids, so they stay reachable by name")
        void keepsNonPositiveNumericNames() {
            // This is why every text says POSITIVE whole number: positiveId refuses <= 0
            // and falls through to the name path.
            List<Map<String, Object>> connected = List.of(
                    entry("0", "instagram", "active", false),
                    entry("-1", "instagram", "active", false));

            assertThat(names(connected)).containsExactly("0", "-1");
        }

        @Test
        @DisplayName("a number too large to be an id is a name, not an overflow")
        void hugeNumberIsAName() {
            assertThat(names(List.of(entry("99999999999999999999", "instagram", "active", false))))
                    .containsExactly("99999999999999999999");
        }

        @Test
        @DisplayName("an entry with no name, or none the agent can attribute to a step, is dropped")
        void skipsUnattributableEntries() {
            List<Map<String, Object>> connected = new ArrayList<>();
            connected.add(entry(null, "instagram", "active", false));
            connected.add(entry("   ", "instagram", "active", false));
            connected.add(entry("Orphan", null, "active", false));
            connected.add(entry("Orphan2", "  ", "active", false));

            assertThat(names(connected)).isEmpty();
        }

        @Test
        @DisplayName("the default account itself is never in the offer")
        void skipsDefaults() {
            assertThat(names(List.of(entry("Main", "instagram", "active", true)))).isEmpty();
        }
    }

    @Nested
    @DisplayName("integration comparison, where the mirror is approximate")
    class IntegrationComparison {

        @Test
        @DisplayName("case and punctuation do not split one integration into two namespaces")
        void foldsCaseAndPunctuation() {
            // The column is not guaranteed lower-cased. Splitting the buckets would make
            // an ambiguous pair look unambiguous and offer BOTH, which is the over-offer
            // this filter exists to prevent.
            assertThat(names(List.of(
                    entry("Shared", "Instagram", "active", false),
                    entry("Shared", "insta_gram", "active", false)))).isEmpty();
        }

        @Test
        @DisplayName("a trailing -api does not split one integration into two namespaces")
        void foldsApiSuffix() {
            // The run-time slug normaliser strips it, so "Acme API" and "Acme" are one
            // namespace there and must be one here.
            assertThat(names(List.of(
                    entry("Shared", "acme-api", "active", false),
                    entry("Shared", "acme", "active", false)))).isEmpty();
        }

        @Test
        @DisplayName("an accent does not split one integration into two namespaces")
        void foldsAccents() {
            assertThat(names(List.of(
                    entry("Shared", "café", "active", false),
                    entry("Shared", "cafe", "active", false)))).isEmpty();
        }

        @Test
        @DisplayName("genuinely different integrations keep the same name selectable in both")
        void doesNotMergeDistinctIntegrations() {
            assertThat(names(List.of(
                    entry("Client", "instagram", "active", false),
                    entry("Client", "slack", "active", false))))
                    .containsExactly("Client", "Client");
        }

        @Test
        @DisplayName("a slash in a NAME cannot forge another integration's bucket")
        void slashInNameDoesNotCollide() {
            // The key is "<normalised identity>/<name>". These two differ ONLY by where
            // the boundary falls: without the separator both concatenate to "abx" and
            // the pair reads as a collision, so both would vanish from the offer. The
            // earlier fixture used "b/x" and "x", which differ under every key scheme
            // including no separator at all, so it passed either way.
            assertThat(names(List.of(
                    entry("x", "ab", "active", false),
                    entry("bx", "a", "active", false))))
                    .containsExactly("x", "bx");
        }

        @Test
        @DisplayName("a blank-integration namesake still makes its twin ambiguous, so neither is offered")
        void blankIntegrationCountsTowardAmbiguity() {
            // The matcher admits a blank-integration credential by NAME against the
            // requirement, so for an smtp step BOTH of these are candidates and neither
            // resolves. Bucketing on the integration alone split them, found the second
            // unique, and offered a name that fails 100% of the time.
            assertThat(names(List.of(
                    entry("smtp", "", "active", false),
                    entry("smtp", "smtp", "active", false))))
                    .isEmpty();
        }

        @Test
        @DisplayName("a -credential suffix is the same identity, since that is how a requirement names it")
        void credentialSuffixIsTheSameIdentity() {
            assertThat(names(List.of(
                    entry("Shared", "acme", "active", false),
                    entry("Shared", "acme-credential", "active", false))))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the offered sentence")
    class Offer {

        @Test
        @DisplayName("says nothing at all when no account qualifies")
        void silentWhenNothingQualifies() {
            // Naming the field with nothing to apply it to sends the agent looking for an
            // account that does not exist.
            assertThat(SelectableAccounts.offer(List.of(entry("Main", "instagram", "active", true)))).isEmpty();
            assertThat(SelectableAccounts.offer(List.of())).isEmpty();
        }

        @Test
        @DisplayName("carries each name with its integration, so the agent knows which step it fits")
        void qualifiesNames() {
            assertThat(SelectableAccounts.offer(List.of(entry("Client B", "instagram", "active", false))))
                    .contains("\"Client B\" (instagram)")
                    .contains("credential_selector");
        }

        @Test
        @DisplayName("quotes names, so one containing a comma does not read as two")
        void quotesNames() {
            // Credential names are free text and this list is comma-separated. Unquoted,
            // "Acme, Inc" reads as two names and both fail fail-closed.
            String offer = SelectableAccounts.offer(List.of(
                    entry("Acme, Inc", "slack", "active", false),
                    entry("Other", "slack", "active", false)));

            assertThat(offer).contains("\"Acme, Inc\" (slack)").contains("\"Other\" (slack)");
        }

        @Test
        @DisplayName("prints the trimmed name, the same value the matcher compares")
        void printsTrimmedName() {
            assertThat(SelectableAccounts.offer(List.of(entry("  Padded  ", "slack", "active", false))))
                    .contains("\"Padded\" (slack)");
        }

        @Test
        @DisplayName("a quote inside a name is escaped, so it cannot close the quoting early")
        void escapesQuotesInNames() {
            // Quoting exists so a comma in a name does not read as two names. A name
            // that contains a quote would end the quoting early and put the ambiguity
            // straight back.
            assertThat(SelectableAccounts.offer(List.of(entry("Ac\"me", "slack", "active", false))))
                    .contains("\"Ac\\\"me\" (slack)");
        }

        @Test
        @DisplayName("an integration that normalises to nothing is not offered")
        void skipsIntegrationThatNormalisesEmpty() {
            // Non-blank but slug-empty: sameCredentialIdentity refuses a blank side, so
            // this can never be a run-time candidate under any name.
            assertThat(names(List.of(entry("Usable", "!!!", "active", false)))).isEmpty();
        }

        @Test
        @DisplayName("caps a long list and says it was cut, rather than growing without bound")
        void capsAndSaysSo() {
            List<Map<String, Object>> connected = new ArrayList<>();
            for (int i = 1; i <= 20; i++) {
                connected.add(entry("Acct " + i, "instagram", "active", false));
            }

            String offer = SelectableAccounts.offer(connected);

            assertThat(offer).contains("\"Acct 12\" (instagram)").doesNotContain("\"Acct 13\"");
            // A silent truncation would read as "these are all of them".
            assertThat(offer).contains("and 8 more");
        }

        @Test
        @DisplayName("exactly at the cap there is nothing to say was cut")
        void noTruncationNoticeAtTheCap() {
            List<Map<String, Object>> connected = new ArrayList<>();
            for (int i = 1; i <= SelectableAccounts.MAX_NAMED; i++) {
                connected.add(entry("Acct " + i, "instagram", "active", false));
            }

            assertThat(SelectableAccounts.offer(connected)).doesNotContain("more in the connected list");
        }
    }

    @Test
    @DisplayName("a null or empty list is an empty offer, not a crash")
    void toleratesNoInput() {
        // A public entry point in a shared module: the two callers pass a real list
        // today, and neither this class nor a third caller should have to know that.
        assertThat(SelectableAccounts.selectable(null)).isEmpty();
        assertThat(SelectableAccounts.selectable(List.of())).isEmpty();
        assertThat(SelectableAccounts.offer(null)).isEmpty();
    }

    @Test
    @DisplayName("a status that is not a String is not active, rather than a ClassCastException")
    void toleratesNonStringStatus() {
        Map<String, Object> odd = new LinkedHashMap<>();
        odd.put("name", "Usable");
        odd.put("integration", "instagram");
        odd.put("status", 42);
        odd.put("isDefault", false);

        assertThat(names(List.of(odd))).isEmpty();
    }

    @Test
    @DisplayName("case folding is locale-independent, so a Turkish default locale cannot change the answer")
    void foldingDoesNotFollowTheDefaultLocale() {
        // Under tr-TR a bare toLowerCase() turns "ACTIVE" into "actıve", which would
        // silently empty the offer on a JVM whose default locale nobody chose.
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(names(List.of(
                    entry("Usable", "INSTAGRAM", "ACTIVE", false)))).containsExactly("Usable");
            // The fixture above proves nothing about nameKey: none of its characters folds
            // differently under tr-TR, and isActive uses equalsIgnoreCase, which is
            // locale-independent by definition. "I" is the character that does: a bare
            // toLowerCase() maps it to a dotless i there, so these two names would stop
            // colliding and BOTH would be offered, which is the over-offer the collision
            // filter exists to prevent.
            assertThat(names(List.of(
                    entry("CLIENT I", "instagram", "active", false),
                    entry("client i", "instagram", "active", false)))).isEmpty();
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    @DisplayName("a status in another case is still active, since the matcher compares loosely")
    void statusIsCaseInsensitive() {
        assertThat(names(List.of(entry("Usable", "instagram", "ACTIVE", false)))).containsExactly("Usable");
    }

    @Test
    @DisplayName("an entry missing isDefault entirely is treated as not default, never as absent")
    void missingIsDefaultIsNotDefault() {
        Map<String, Object> partial = new LinkedHashMap<>();
        partial.put("name", "Usable");
        partial.put("integration", "instagram");
        partial.put("status", "active");

        assertThat(names(List.of(partial))).containsExactly("Usable");
    }

    private static List<String> names(List<Map<String, Object>> connected) {
        return SelectableAccounts.selectable(connected).stream()
                .map(c -> (String) c.get("name"))
                .toList();
    }

    private static Map<String, Object> entry(String name, String integration, String status, boolean isDefault) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("integration", integration);
        entry.put("status", status);
        entry.put("isDefault", isDefault);
        return entry;
    }
}
