package com.apimarketplace.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * This install's anonymous identity: one random UUID, generated once and never changed.
 *
 * <p>Single row, pinned to {@link #SINGLETON_ID} by a DB CHECK and seeded by the migration rather
 * than on first read, so concurrent boots cannot mint two identities for one install.
 *
 * <p>The value is derived from nothing - not the IP, not the hostname, not a licence, not a user -
 * so it identifies an instance and not a person. It is sent on the daily release-feed poll, which
 * is how a self-hosted install shows up in the fleet count at all, and suppressed by
 * {@code ce.version-check.send-install-id=false}.
 *
 * <p>Deliberately NOT the cloud-link install id: that one only exists once an install links to the
 * cloud and is bound to a tenant, so it would miss every unlinked install and tie an anonymous
 * counter to an identified account.
 *
 * <p>Present on the cloud deployment too (the schema is shared), where it is never read: only the
 * embedded edition polls the feed.
 */
@Entity
@Table(name = "ce_install", schema = "auth")
public class CeInstall {

    /** The only id the table accepts; enforced by a CHECK constraint. */
    public static final short SINGLETON_ID = 1;

    @Id
    @Column(name = "id", nullable = false)
    private Short id = SINGLETON_ID;

    @Column(name = "install_id", nullable = false, unique = true, updatable = false)
    private UUID installId;

    protected CeInstall() {
        // for JPA
    }

    public UUID getInstallId() {
        return installId;
    }
}
