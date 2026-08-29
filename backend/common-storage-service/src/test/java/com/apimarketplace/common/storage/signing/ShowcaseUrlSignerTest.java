package com.apimarketplace.common.storage.signing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ShowcaseUrlSigner")
class ShowcaseUrlSignerTest {

    private static final String SECRET = "test-secret-32-bytes-long-enough-for-hmac";

    @Test
    @DisplayName("Sign + verify round-trip succeeds for the same canonicalised payload")
    void signVerifyRoundTrip() {
        ShowcaseUrlSigner signer = new ShowcaseUrlSigner(SECRET);
        long exp = Instant.now().getEpochSecond() + 900;
        String sig = signer.sign("1/general/file.png", exp, "inline");
        assertThat(sig).isNotNull();
        assertThat(signer.verify("1/general/file.png", exp, "inline", sig,
                Instant.now().getEpochSecond())).isTrue();
    }

    @Test
    @DisplayName("Verify rejects a tampered signature (constant-time comparison)")
    void rejectsTamperedSignature() {
        ShowcaseUrlSigner signer = new ShowcaseUrlSigner(SECRET);
        long exp = Instant.now().getEpochSecond() + 900;
        String sig = signer.sign("1/file.png", exp, "inline");
        // Flip a MIDDLE character (not last) to avoid the base64 last-char alias:
        // the last base64 char of a 32-byte HMAC encodes 4 useful bits + 2 unused;
        // flipping the unused 2 bits decodes to the SAME byte array, which would
        // make `MessageDigest.isEqual(decodedExpected, decodedProvided)` return true.
        int mid = sig.length() / 2;
        String tampered = sig.substring(0, mid)
                + (sig.charAt(mid) == 'A' ? 'B' : 'A')
                + sig.substring(mid + 1);
        assertThat(signer.verify("1/file.png", exp, "inline", tampered,
                Instant.now().getEpochSecond())).isFalse();
    }

    @Test
    @DisplayName("Verify rejects an expired URL")
    void rejectsExpiredUrl() {
        ShowcaseUrlSigner signer = new ShowcaseUrlSigner(SECRET);
        long exp = Instant.now().getEpochSecond() - 1; // already expired
        String sig = signer.sign("1/file.png", exp, "inline");
        assertThat(signer.verify("1/file.png", exp, "inline", sig,
                Instant.now().getEpochSecond())).isFalse();
    }

    @Test
    @DisplayName("Verify rejects a key tampered after signing - defense against URL rewrite")
    void rejectsTamperedKey() {
        ShowcaseUrlSigner signer = new ShowcaseUrlSigner(SECRET);
        long exp = Instant.now().getEpochSecond() + 900;
        String sig = signer.sign("1/legitimate.png", exp, "inline");
        // Same signature replayed against a different key - must reject.
        assertThat(signer.verify("99/foreign.png", exp, "inline", sig,
                Instant.now().getEpochSecond())).isFalse();
    }

    @Test
    @DisplayName("Verify rejects a disposition tampered after signing - anti-flip from inline to attachment")
    void rejectsTamperedDisposition() {
        ShowcaseUrlSigner signer = new ShowcaseUrlSigner(SECRET);
        long exp = Instant.now().getEpochSecond() + 900;
        String sig = signer.sign("1/file.png", exp, "inline");
        assertThat(signer.verify("1/file.png", exp, "attachment", sig,
                Instant.now().getEpochSecond())).isFalse();
    }

    @Test
    @DisplayName("Disabled signer (blank secret) - sign returns null, verify returns false; service still starts in dev")
    void disabledWhenSecretBlank() {
        ShowcaseUrlSigner signer = new ShowcaseUrlSigner("");
        assertThat(signer.isEnabled()).isFalse();
        assertThat(signer.sign("1/x.png", 999, "inline")).isNull();
        assertThat(signer.verify("1/x.png", 999, "inline", "any-sig", 0)).isFalse();
    }

    @Test
    @DisplayName("Two signers with the same secret produce identical signatures - sign-side (publication-service) and verify-side (storage-service) agree on canonicalisation")
    void deterministicAcrossInstances() {
        ShowcaseUrlSigner a = new ShowcaseUrlSigner(SECRET);
        ShowcaseUrlSigner b = new ShowcaseUrlSigner(SECRET);
        long exp = 1234567890L;
        assertThat(a.sign("1/file.png", exp, "inline"))
                .isEqualTo(b.sign("1/file.png", exp, "inline"));
    }

    @Test
    @DisplayName("Different secrets produce different signatures - wrong-secret instance cannot forge")
    void differentSecretsDoNotMatch() {
        ShowcaseUrlSigner a = new ShowcaseUrlSigner(SECRET);
        ShowcaseUrlSigner b = new ShowcaseUrlSigner("different-secret-of-similar-length-32b");
        long exp = Instant.now().getEpochSecond() + 900;
        String sigA = a.sign("1/file.png", exp, "inline");
        // b cannot verify what a signed.
        assertThat(b.verify("1/file.png", exp, "inline", sigA,
                Instant.now().getEpochSecond())).isFalse();
    }

    @Test
    @DisplayName("Verify rejects null/empty signature")
    void rejectsNullOrEmptySignature() {
        ShowcaseUrlSigner signer = new ShowcaseUrlSigner(SECRET);
        long now = Instant.now().getEpochSecond();
        assertThat(signer.verify("1/x.png", now + 900, "inline", null, now)).isFalse();
        assertThat(signer.verify("1/x.png", now + 900, "inline", "", now)).isFalse();
    }

    @Test
    @DisplayName("isAuthentic accepts an EXPIRED signature that we produced - provenance and permission-to-serve are different questions, and re-signing a dead marketplace link depends on the first one")
    void isAuthenticIgnoresExpiry() {
        ShowcaseUrlSigner signer = new ShowcaseUrlSigner("test-secret-32-bytes-long-enough-for-hmac");
        long longGone = 1L;
        String sig = signer.sign("1/run/clip.mp4", longGone, "inline");

        assertThat(signer.isAuthentic("1/run/clip.mp4", longGone, "inline", sig)).isTrue();
        // ...while verify, which decides whether bytes may be served NOW, still refuses it.
        assertThat(signer.verify("1/run/clip.mp4", longGone, "inline", sig,
                java.time.Instant.now().getEpochSecond())).isFalse();
    }

    @Test
    @DisplayName("isAuthentic refuses a signature from another install, a tampered key, a tampered expiry and a tampered disposition - the payload covers all three")
    void isAuthenticRefusesAnythingNotOurs() {
        ShowcaseUrlSigner signer = new ShowcaseUrlSigner("test-secret-32-bytes-long-enough-for-hmac");
        ShowcaseUrlSigner other = new ShowcaseUrlSigner("a-completely-different-secret-value-32b");
        String sig = signer.sign("1/run/clip.mp4", 42L, "inline");

        assertThat(signer.isAuthentic("1/run/clip.mp4", 42L, "inline",
                other.sign("1/run/clip.mp4", 42L, "inline"))).isFalse();
        assertThat(signer.isAuthentic("3/private/contract.pdf", 42L, "inline", sig)).isFalse();
        assertThat(signer.isAuthentic("1/run/clip.mp4", 99L, "inline", sig)).isFalse();
        assertThat(signer.isAuthentic("1/run/clip.mp4", 42L, "attachment", sig)).isFalse();
    }

    @Test
    @DisplayName("isAuthentic fails closed with no signing key and on a blank signature")
    void isAuthenticFailsClosed() {
        assertThat(new ShowcaseUrlSigner("").isAuthentic("1/a.png", 1L, "inline", "anything")).isFalse();
        assertThat(new ShowcaseUrlSigner("test-secret-32-bytes-long-enough-for-hmac")
                .isAuthentic("1/a.png", 1L, "inline", "")).isFalse();
    }
}
