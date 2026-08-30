package com.apimarketplace.auth.web.version;

import com.apimarketplace.auth.domain.CeInstall;
import com.apimarketplace.auth.repository.CeInstallRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Supplies this install's anonymous identity to the release-feed poller.
 *
 * <p>Exists only in the embedded (CE) edition, and only while BOTH
 * {@code ce.version-check.enabled} and {@code ce.version-check.send-install-id} are true, so an
 * opt-out removes the bean rather than asking a caller to remember a flag.
 * {@link HttpReleaseFeedClient} takes it as an {@code ObjectProvider} and sends nothing when it is
 * absent.
 *
 * <p>The coarser flag has to win: with the update check off there is no request to carry an id, and
 * an install that makes none must not still be reading one and announcing at startup that it sends
 * it with a daily check that no longer runs.
 *
 * <p>Read once and cached: the row is seeded by the migration and never changes, so re-reading it
 * daily would be a pointless query. A failed read is NOT cached, so an install that boots before
 * its database is reachable still picks the identity up on the next poll.
 *
 * <p>Best-effort throughout. Knowing how many installs exist is worth strictly less than the update
 * banner working, so every failure here degrades to "send no identifier" and never propagates.
 */
@Component
@ConditionalOnExpression("'${auth.mode:keycloak}' == 'embedded' && '${ce.version-check.enabled:true}' == 'true' && '${ce.version-check.send-install-id:true}' == 'true'")
public class CeInstallIdProvider {

    private static final Logger log = LoggerFactory.getLogger(CeInstallIdProvider.class);

    private final CeInstallRepository repository;
    private final AtomicReference<UUID> cached = new AtomicReference<>();

    public CeInstallIdProvider(CeInstallRepository repository) {
        this.repository = repository;
    }

    /**
     * Says, once, what this install sends and how to turn it off.
     *
     * <p>An anonymous counter is only defensible if the operator can find out it exists without
     * reading the source, and the docs cannot reach someone who never looked. It fires at the
     * moment the id first becomes readable rather than on a startup event, so it cannot be missed
     * when the database was not up yet and cannot be preceded by the first poll that sends the id.
     */
    private static void announce(UUID id) {
        log.info("This install reports an anonymous install id ({}) with its daily update check, so "
                + "the number of live self-hosted installs can be counted. No IP, hostname or "
                + "account is sent. Disable with ce.version-check.send-install-id=false, or turn "
                + "the update check off entirely with ce.version-check.enabled=false.", id);
    }

    /**
     * @return this install's anonymous id, or empty when it cannot be read (never throws)
     */
    public Optional<UUID> current() {
        UUID known = cached.get();
        if (known != null) {
            return Optional.of(known);
        }
        try {
            UUID resolved = repository.findById(CeInstall.SINGLETON_ID)
                    .map(CeInstall::getInstallId)
                    .orElse(null);
            if (resolved == null) {
                log.debug("No anonymous install id row yet - update check will stay unidentified");
                return Optional.empty();
            }
            // compareAndSet, not set: the javadoc and the test both say this announces once, and
            // read-check-act would let two concurrent first callers both log it.
            if (cached.compareAndSet(null, resolved)) {
                announce(resolved);
            }
            return Optional.of(resolved);
        } catch (RuntimeException failure) {
            log.debug("Could not read the anonymous install id: {}", failure.getMessage());
            return Optional.empty();
        }
    }
}
