package com.apimarketplace.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Delivers signed identity tombstones; failed delivery remains durable and retryable. */
@Service
@ConditionalOnProperty(name = "billing.authority.mode",
        havingValue = "paid-monolith-authority", matchIfMissing = false)
public class IdentityBindingOutboxDispatcher {

    private final JdbcTemplate jdbc;
    private final WorkloadAuthenticationService workloads;
    private final org.springframework.web.client.RestTemplate http;
    private final String endpoint;

    public IdentityBindingOutboxDispatcher(
            JdbcTemplate jdbc,
            WorkloadAuthenticationService workloads,
            RestTemplateBuilder builder,
            @Value("${trinyx.identity.cloud-revocation-url:https://cloud-internal.trinyx.private:8443/internal/v1/identity-bindings/revocations}")
            String endpoint) {
        this.jdbc = jdbc;
        this.workloads = workloads;
        this.http = builder.connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(10)).build();
        this.endpoint = endpoint;
    }

    @Scheduled(fixedDelayString = "${trinyx.identity.outbox-delay-ms:3000}")
    public void dispatch() {
        var events = jdbc.query("""
                UPDATE auth.identity_binding_outbox
                SET status='PROCESSING', next_attempt_at=now() + interval '60 seconds'
                WHERE event_id IN (
                    SELECT event_id FROM auth.identity_binding_outbox
                    WHERE status IN ('PENDING','FAILED','PROCESSING') AND next_attempt_at <= now()
                    ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 25
                )
                RETURNING event_id, signed_jws, attempt_count
                """, (rs, row) -> new Event(rs.getObject(1, UUID.class),
                rs.getString(2), rs.getInt(3)));
        for (Event event : events) deliver(event);
    }

    private void deliver(Event event) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(workloads.issue("trinyx-paid-authority"));
            ResponseEntity<Void> response = http.exchange(endpoint, HttpMethod.PUT,
                    new HttpEntity<>(java.util.Map.of("assertion", event.assertion()), headers),
                    Void.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("Cloud identity ingest returned " + response.getStatusCode());
            }
            jdbc.update("""
                    UPDATE auth.identity_binding_outbox
                    SET status='DELIVERED', delivered_at=now(), last_error=NULL
                    WHERE event_id=?
                    """, event.id());
        } catch (Exception failure) {
            int attempt = event.attemptCount() + 1;
            long cap = Math.min(300, 1L << Math.min(8, attempt));
            long delay = ThreadLocalRandom.current().nextLong(Math.max(1, cap / 2), cap + 1);
            jdbc.update("""
                    UPDATE auth.identity_binding_outbox
                    SET status='FAILED', attempt_count=?, next_attempt_at=?, last_error=?
                    WHERE event_id=?
                    """, attempt, Timestamp.from(Instant.now().plusSeconds(delay)),
                    bounded(failure.getMessage()), event.id());
        }
    }

    private static String bounded(String message) {
        String value = message == null ? "identity delivery failed" : message;
        return value.substring(0, Math.min(2000, value.length()));
    }

    private record Event(UUID id, String assertion, int attemptCount) {}
}
