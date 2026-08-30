package com.apimarketplace.publication.service;

import com.apimarketplace.common.storage.signing.ShowcaseUrlSigner;
import com.apimarketplace.common.storage.url.FileProxyUrls;
import com.apimarketplace.common.storage.url.StorageKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides whether a file-proxy URL found inside publication data may be treated as a
 * reference to a file this publication is allowed to touch.
 *
 * <p>The distinction matters because of where these strings come from. A publication's
 * interface data is whatever the publisher's own workflow put there, so it is publisher
 * input, not platform output: a value shaped like one of our URLs is a claim, not a fact.
 * Acting on the claim means copying the named file into the publication's storage namespace
 * and then serving it to anonymous visitors, so a bare {@link FileProxyUrls#parse} result is
 * not enough to go on.
 *
 * <ul>
 *   <li><strong>Signed</strong> ({@code /api/files/proxy-signed}): accepted only when the
 *       HMAC verifies against this install's key. The signature is the proof: the platform
 *       mints one only for a caller that owned the key at the time, so a valid signature
 *       says the file was legitimately reachable by whoever minted the link. The expiry is
 *       deliberately ignored, because a dead link is precisely what is being repaired.</li>
 *   <li><strong>Unsigned</strong> ({@code /api/files/proxy}): carries no proof at all, so
 *       only the relative form is accepted, which is the form the interface renderer bakes
 *       in. An absolute one names a host, and a host is not something this string is
 *       entitled to choose.</li>
 * </ul>
 *
 * <p>Every accepted key must ALSO belong to the publisher, to the declared source owner, or
 * to this publication's own namespace - tested before the copy rather than only before
 * signing. A raw FileRef map is held to the same rule by {@code CopyScope} at the copy pass
 * itself; this class is where the rule is applied to a reference that arrived as a string.
 */
@Component
public class PublicationFileUrlResolver {

    private static final Logger log = LoggerFactory.getLogger(PublicationFileUrlResolver.class);

    private final ShowcaseUrlSigner signer;

    public PublicationFileUrlResolver(ShowcaseUrlSigner signer) {
        this.signer = signer;
    }

    /**
     * @param value any string found in publication data
     * @return the storage key when the value is a file-proxy URL this install can vouch for,
     *         {@code null} otherwise (including when it is not a URL at all)
     */
    public String trustedStorageKeyOf(String value, String publisherId, String publicationNamespace) {
        return trustedStorageKeyOf(value, publisherId, null, publicationNamespace);
    }

    /**
     * @param sourceTenantId the second tenant whose files this publication may act on, stated
     *        by whoever can know it (the orchestrator for a captured run, the snapshotter for
     *        a landing). Without it a cross-org publication cannot re-home the run owner's
     *        files, so its media keep the expiry they were born with - the very bug this
     *        whole mechanism exists to fix, unfixed for exactly the publications where the
     *        files are not the publisher's own.
     */
    public String trustedStorageKeyOf(String value, String publisherId, String sourceTenantId,
                                      String publicationNamespace) {
        FileProxyUrls.ProxyUrl url = FileProxyUrls.parse(value);
        if (url == null) return null;
        if (!ownedHere(url.key(), publisherId, sourceTenantId, publicationNamespace)) {
            return refuse("key belongs to neither the publisher nor this publication", url.key());
        }
        if (!url.signed()) {
            return url.absolute() ? refuse("unsigned absolute URL", url.key()) : url.key();
        }
        if (!signer.isEnabled()) {
            // No key configured: nothing can be vouched for, and the marketplace file
            // channel is dead anyway (the rewriter signs nothing either).
            return null;
        }
        if (!signer.isAuthentic(url.key(), url.exp(), url.disposition(), url.sig())) {
            return refuse("signature not ours", url.key());
        }
        return url.key();
    }

    /**
     * The same test {@code ShowcaseFileRefRewriter.mintSignedUrl} applies before signing,
     * applied here BEFORE the copy instead of only after it.
     *
     * <p>Provenance alone is not enough, and the gap is not theoretical. A
     * {@code core:public_link} URL is public by design: its owner mints one and posts it
     * somewhere. Anyone can paste that string into their own interface data, and a signature
     * check alone would say yes, because the signature attests to the MINTER, not to the
     * publisher acting on it. The file would then be copied into the pasting publication and
     * re-signed on every read from then on, turning the owner's 7-day link into somebody
     * else's permanent public re-host that the owner cannot revoke.
     *
     * <p>The unsigned shape carries no attestation at all, so this is the only test it gets,
     * and it is what stops a hand-written {@code /api/files/proxy?key=<someone-else>} from
     * naming a stranger's file.
     *
     * <p>The same rule reaches a FileRef MAP through {@code CopyScope} at the copy pass. Both
     * shapes are publisher-authored - a {@code core:code} node's output lands in interface
     * data verbatim - so neither gets to name a file on trust.
     */
    private boolean ownedHere(String key, String publisherId, String sourceTenantId,
                              String publicationNamespace) {
        // Shared rule: a prefix test is only a boundary while the suffix cannot walk out of
        // it, and this is the first place the string being tested is publisher-authored text.
        if (!StorageKeys.isWellFormed(key)) {
            return false;
        }
        if (publisherId != null && !publisherId.isEmpty() && key.startsWith(publisherId + "/")) {
            return true;
        }
        if (sourceTenantId != null && !sourceTenantId.isEmpty() && key.startsWith(sourceTenantId + "/")) {
            return true;
        }
        return publicationNamespace != null && !publicationNamespace.isEmpty()
                && key.startsWith(publicationNamespace);
    }

    /**
     * Would the repair re-home this value? Answers the dry run without touching storage.
     *
     * <p>Lives here rather than on {@code WorkflowPublicationService} on purpose: that class
     * is {@code @Transactional}, so a call per string node in a snapshot would begin and
     * commit a JPA transaction for each one - hundreds of thousands of them on a fleet-wide
     * dry run, for a pure function of its arguments.
     */
    public boolean wouldRehome(String value, String publisherId, String sourceTenantId,
                               String publicationNamespace) {
        String key = trustedStorageKeyOf(value, publisherId, sourceTenantId, publicationNamespace);
        return key != null && !key.startsWith("_publications/");
    }

    private String refuse(String reason, String key) {
        log.warn("[FileUrlResolver] refusing a file URL in publication data ({}): key={}", reason, key);
        return null;
    }
}
