package com.apimarketplace.publication.service;

import com.apimarketplace.common.storage.signing.ShowcaseUrlSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate between "this string looks like one of our file URLs" and "copy that file into a
 * publication and serve it to the public". The strings are whatever the publisher's own
 * workflow wrote into its interface data, so shape alone must never be enough - and neither
 * is a valid signature on its own, because a public link is shareable and its signature
 * attests to whoever minted it, not to whoever pasted it.
 */
@DisplayName("PublicationFileUrlResolver.trustedStorageKeyOf")
class PublicationFileUrlResolverTest {

    private static final String SECRET = "test-secret-32-bytes-long-enough-for-hmac";
    private static final String PUBLISHER = "1";
    private static final UUID PUB_ID = UUID.fromString("7941574e-8d76-4615-8bfe-d6db91cfd173");
    private static final String NAMESPACE = "_publications/" + PUB_ID + "/";
    /** A key the publisher owns. */
    private static final String OWN_KEY = "1/run_1/core:watermark/clip.mp4";
    /** A key belonging to somebody else. */
    private static final String FOREIGN_KEY = "3/private/contract.pdf";

    private ShowcaseUrlSigner signer;
    private PublicationFileUrlResolver resolver;

    @BeforeEach
    void setUp() {
        signer = new ShowcaseUrlSigner(SECRET);
        resolver = new PublicationFileUrlResolver(signer);
    }

    private String resolve(String value) {
        return resolver.trustedStorageKeyOf(value, PUBLISHER, NAMESPACE);
    }

    private String signedUrl(String host, String key, long exp, String disposition) {
        return host + "/api/files/proxy-signed?key="
                + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&exp=" + exp + "&disposition=" + disposition
                + "&sig=" + signer.sign(key, exp, disposition);
    }

    @Nested
    @DisplayName("ownership")
    class Ownership {

        @Test
        @DisplayName("refuses an unsigned relative URL naming ANOTHER tenant's file - the copy pass has no ownership test of its own, and after the copy the key sits in the publication namespace, which the render-time guard then happily signs")
        void refusesForeignKeyInRelativeUnsignedUrl() {
            // The whole attack in one line: a publisher writes this into an interface data
            // field, publishes, and the marketplace serves someone else's file for good.
            assertThat(resolve("/api/files/proxy?key="
                    + URLEncoder.encode(FOREIGN_KEY, StandardCharsets.UTF_8))).isNull();
        }

        @Test
        @DisplayName("refuses a VALIDLY SIGNED URL naming another tenant's file - a public link is shareable, so its signature says who minted it, not who is entitled to re-host it")
        void refusesForeignKeyEvenWhenValidlySigned() {
            // Tenant 3 mints a public link for their own file and posts it somewhere. This
            // publisher pastes the string into their interface data. The HMAC is genuinely
            // ours, so provenance alone would say yes - and tenant 3's 7-day link would
            // become this publication's permanent, unrevocable public re-host.
            assertThat(resolve(signedUrl("https://livecontext.ai", FOREIGN_KEY, 1L, "inline"))).isNull();
        }

        @Test
        @DisplayName("accepts the DECLARED source owner's file - a run or a landing interface in the same organization belongs to another member, and refusing it would leave every cross-org publication reading live files with the expiry it was born with")
        void acceptsTheDeclaredSourceOwner() {
            String runOwnerKey = "3/run_9/core:watermark/clip.mp4";

            // Without the declaration it is a stranger's file.
            assertThat(resolver.trustedStorageKeyOf(
                    signedUrl("https://livecontext.ai", runOwnerKey, 1L, "inline"),
                    PUBLISHER, null, NAMESPACE)).isNull();
            // With it, it is the run owner's.
            assertThat(resolver.trustedStorageKeyOf(
                    signedUrl("https://livecontext.ai", runOwnerKey, 1L, "inline"),
                    PUBLISHER, "3", NAMESPACE)).isEqualTo(runOwnerKey);
        }

        @Test
        @DisplayName("the declared source owner does not open up a THIRD tenant")
        void declaredOwnerDoesNotWidenFurther() {
            assertThat(resolver.trustedStorageKeyOf(
                    "/api/files/proxy?key=99%2Fsomebody%2Felse.pdf", PUBLISHER, "3", NAMESPACE)).isNull();
        }

        @Test
        @DisplayName("accepts a key already inside THIS publication's namespace - a second repair pass must recognise its own earlier work")
        void acceptsOwnPublicationNamespace() {
            String key = NAMESPACE + "snapshot/x/clip.mp4";
            assertThat(resolve(signedUrl("https://livecontext.ai", key, 1L, "inline"))).isEqualTo(key);
        }

        @Test
        @DisplayName("refuses ANOTHER publication's namespace")
        void refusesAnotherPublicationNamespace() {
            String other = "_publications/00000000-0000-0000-0000-000000000000/snapshot/x/clip.mp4";
            assertThat(resolve(signedUrl("https://livecontext.ai", other, 1L, "inline"))).isNull();
        }

        @Test
        @DisplayName("refuses a key that starts inside the publisher's prefix and then walks back out - a prefix test is only a boundary if the suffix cannot escape it")
        void refusesPathTraversalOutOfThePublisherPrefix() {
            assertThat(resolve("/api/files/proxy?key="
                    + URLEncoder.encode("1/../3/private/contract.pdf", StandardCharsets.UTF_8))).isNull();
        }

        @Test
        @DisplayName("refuses traversal out of this publication's own namespace")
        void refusesPathTraversalOutOfTheNamespace() {
            assertThat(resolve("/api/files/proxy?key="
                    + URLEncoder.encode(NAMESPACE + "../../3/private/contract.pdf",
                            StandardCharsets.UTF_8))).isNull();
        }

        @Test
        @DisplayName("refuses a single-dot segment and an empty segment too - both are ways to write a path that is not what it reads as")
        void refusesDegenerateSegments() {
            assertThat(resolve("/api/files/proxy?key=" + URLEncoder.encode("1/./a.png", StandardCharsets.UTF_8))).isNull();
            assertThat(resolve("/api/files/proxy?key=" + URLEncoder.encode("1//a.png", StandardCharsets.UTF_8))).isNull();
            assertThat(resolve("/api/files/proxy?key=" + URLEncoder.encode("/1/a.png", StandardCharsets.UTF_8))).isNull();
        }

        @Test
        @DisplayName("a tenant id that is a PREFIX of the publisher's is not the publisher - tenant 1 must not answer for tenant 12")
        void refusesTenantIdPrefixCollision() {
            assertThat(resolve("/api/files/proxy?key=12%2Frun%2Fclip.mp4")).isNull();
        }
    }

    @Nested
    @DisplayName("provenance")
    class Provenance {

        @Test
        @DisplayName("accepts a signed URL this install minted, EXPIRED - a dead link is the situation being repaired, so the expiry must not be the gate")
        void acceptsOurOwnExpiredSignedUrl() {
            assertThat(resolve(signedUrl("https://livecontext.ai", OWN_KEY, 1L, "inline"))).isEqualTo(OWN_KEY);
        }

        @Test
        @DisplayName("accepts a signed URL minted with disposition=attachment - the signature covers the disposition, so it must be read from the URL and not assumed")
        void acceptsAttachmentDisposition() {
            assertThat(resolve(signedUrl("https://livecontext.ai", OWN_KEY, 99L, "attachment"))).isEqualTo(OWN_KEY);
        }

        @Test
        @DisplayName("refuses a signature we did not produce, even on the publisher's OWN key - otherwise the signed shape is just a longer way of writing an unverified string")
        void refusesForgedSignature() {
            String forged = "https://livecontext.ai/api/files/proxy-signed?key="
                    + URLEncoder.encode(OWN_KEY, StandardCharsets.UTF_8)
                    + "&exp=99999999999&disposition=inline&sig=bm90LW91cnMtYXQtYWxs";

            assertThat(resolve(forged)).isNull();
        }

        @Test
        @DisplayName("refuses a URL signed by a DIFFERENT install")
        void refusesAnotherInstallsSignature() {
            ShowcaseUrlSigner otherInstall = new ShowcaseUrlSigner("a-completely-different-secret-value-32b");
            String elsewhere = "https://other.example/api/files/proxy-signed?key="
                    + URLEncoder.encode(OWN_KEY, StandardCharsets.UTF_8)
                    + "&exp=5&disposition=inline&sig=" + otherInstall.sign(OWN_KEY, 5L, "inline");

            assertThat(resolve(elsewhere)).isNull();
        }

        @Test
        @DisplayName("refuses a signed URL whose expiry was tampered with after signing")
        void refusesTamperedExpiry() {
            String legit = signedUrl("https://livecontext.ai", OWN_KEY, 42L, "inline");

            assertThat(resolve(legit.replace("exp=42", "exp=99999999999"))).isNull();
        }

        @Test
        @DisplayName("with no signing key configured nothing signed can be vouched for, while the unsigned relative form never depended on it")
        void signerDisabled() {
            PublicationFileUrlResolver disabled = new PublicationFileUrlResolver(new ShowcaseUrlSigner(""));

            assertThat(disabled.trustedStorageKeyOf(
                    signedUrl("https://livecontext.ai", OWN_KEY, 1L, "inline"), PUBLISHER, NAMESPACE)).isNull();
            assertThat(disabled.trustedStorageKeyOf(
                    "/api/files/proxy?key=1%2Fa.png", PUBLISHER, NAMESPACE)).isEqualTo("1/a.png");
        }
    }

    @Nested
    @DisplayName("dry run")
    class DryRun {

        @Test
        @DisplayName("a URL string the repair would move counts - it is the population the repair exists for, which a FileRef-map-only count reports as nothing to do")
        void countsWhatWouldMove() {
            assertThat(resolver.wouldRehome("/api/files/proxy?key=1%2Frun%2Fclip.mp4",
                    PUBLISHER, null, NAMESPACE)).isTrue();
            assertThat(resolver.wouldRehome(signedUrl("https://livecontext.ai", OWN_KEY, 1L, "inline"),
                    PUBLISHER, null, NAMESPACE)).isTrue();
        }

        @Test
        @DisplayName("a file already in the namespace does NOT count - a second sweep must report nothing to do")
        void alreadyRehomedDoesNotCount() {
            String key = NAMESPACE + "snapshot/x/clip.mp4";
            assertThat(resolver.wouldRehome(signedUrl("https://livecontext.ai", key, 1L, "inline"),
                    PUBLISHER, null, NAMESPACE)).isFalse();
        }

        @Test
        @DisplayName("a file the repair would refuse does NOT count - promising work the real run then refuses is the phantom the dry run exists to avoid")
        void refusedDoesNotCount() {
            assertThat(resolver.wouldRehome("/api/files/proxy?key=3%2Fprivate%2Fcontract.pdf",
                    PUBLISHER, null, NAMESPACE)).isFalse();
            assertThat(resolver.wouldRehome("A sunset over the sea", PUBLISHER, null, NAMESPACE)).isFalse();
        }

        @Test
        @DisplayName("the run owner's file counts when the snapshot declares them - the cross-org case the repair must not skip")
        void countsTheDeclaredSourceOwner() {
            assertThat(resolver.wouldRehome("/api/files/proxy?key=3%2Frun%2Fclip.mp4",
                    PUBLISHER, "3", NAMESPACE)).isTrue();
        }
    }

    @Nested
    @DisplayName("shape")
    class Shape {

        @Test
        @DisplayName("accepts the RELATIVE authenticated proxy URL for an owned key - the shape the interface renderer bakes in")
        void acceptsRelativeUnsignedProxyUrl() {
            assertThat(resolve("/api/files/proxy?key=1%2Frun%2Fphoto.jpg&disposition=inline"))
                    .isEqualTo("1/run/photo.jpg");
        }

        @Test
        @DisplayName("refuses an ABSOLUTE unsigned proxy URL even for an owned key - it carries no proof and names a host, which a value in publisher data is not entitled to choose")
        void refusesAbsoluteUnsignedProxyUrl() {
            assertThat(resolve("https://attacker.example/api/files/proxy?key=1%2Frun%2Fphoto.jpg")).isNull();
            assertThat(resolve("//attacker.example/api/files/proxy?key=1%2Frun%2Fphoto.jpg")).isNull();
        }

        @Test
        @DisplayName("a value that is not one of our URLs at all is simply not one")
        void refusesNonUrls() {
            assertThat(resolve(null)).isNull();
            assertThat(resolve("A sunset over the sea")).isNull();
            assertThat(resolve("https://example.com/photo.jpg")).isNull();
        }

        @Test
        @DisplayName("a blank publisher does not turn every key into the publisher's - an empty prefix must not match everything")
        void blankPublisherMatchesNothing() {
            assertThat(resolver.trustedStorageKeyOf("/api/files/proxy?key=1%2Fa.png", "", NAMESPACE)).isNull();
            assertThat(resolver.trustedStorageKeyOf("/api/files/proxy?key=1%2Fa.png", null, NAMESPACE)).isNull();
        }
    }
}
