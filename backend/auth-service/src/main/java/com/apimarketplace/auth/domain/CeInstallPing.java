package com.apimarketplace.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Cloud-side ledger row: one self-hosted install that has been seen on the public release feed.
 *
 * <p>The primary key is the install's own random UUID, and the row holds nothing else that could
 * identify anyone: no IP, no hostname, no tenant, no account. What it answers is deliberately
 * narrow: how many installs are live, how long they stay, and which version they run.
 *
 * <p>"Anonymous" is a statement about this ROW, not about the request that produced it. Like any
 * HTTP call, that request reaches the edge with an IP the web server logs, so a small fleet is
 * correlatable by joining an edge log against these timestamps. What the design guarantees is that
 * nothing here carries an identifier, and that a linked install's tenant-bound cloud-link id can
 * never land in this column (see {@code CeInstallHeaders}).
 *
 * <p>Written only through the repository's refresh and insert, never by JPA dirty-checking: several pods answer
 * the same feed, so "read, increment, save" would lose counts under concurrency.
 */
@Entity
@Table(name = "ce_install_ping", schema = "auth")
public class CeInstallPing {

    @Id
    @Column(name = "install_id", nullable = false, updatable = false)
    private UUID installId;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "last_version", length = 64)
    private String lastVersion;

    protected CeInstallPing() {
        // for JPA
    }

    // No accessors on purpose. Nothing reads an individual ledger row: the repository exposes the
    // two writes, four aggregates and the purge, and Hibernate maps these fields directly. Getters
    // here would exist only to make a per-install read easy to write later.
}
