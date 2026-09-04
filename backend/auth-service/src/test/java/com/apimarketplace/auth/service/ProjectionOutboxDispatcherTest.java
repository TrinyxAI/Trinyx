package com.apimarketplace.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.client.*;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectionOutboxDispatcherTest {

    @Test
    void entitlementFourHundredIsDeadAndFiveHundredRetries() {
        Harness terminal = new Harness();
        terminal.throwResponse(HttpStatus.CONFLICT);
        new EntitlementOutboxDispatcher(terminal.jdbc, terminal.workloads, terminal.builder,
                "https://cloud-internal.trinyx.private:8443/internal/v1/entitlement-projections")
                .dispatch();
        assertThat(terminal.jdbc.sql).anyMatch(sql -> sql.contains("status='DEAD'"));
        assertThat(terminal.jdbc.sql).noneMatch(sql -> sql.contains("status='FAILED'"));

        Harness retry = new Harness();
        retry.throwResponse(HttpStatus.SERVICE_UNAVAILABLE);
        new EntitlementOutboxDispatcher(retry.jdbc, retry.workloads, retry.builder,
                "https://cloud-internal.trinyx.private:8443/internal/v1/entitlement-projections")
                .dispatch();
        assertThat(retry.jdbc.sql).anyMatch(sql -> sql.contains("status='FAILED'"));
        assertThat(retry.jdbc.sql).noneMatch(sql -> sql.contains("status='DEAD'"));
    }

    @Test
    void projectionTimeoutAndRateLimitResponsesRetry() {
        for (HttpStatus status : new HttpStatus[]{
                HttpStatus.REQUEST_TIMEOUT, HttpStatus.TOO_MANY_REQUESTS}) {
            Harness retry = new Harness();
            retry.throwResponse(status);
            new EntitlementOutboxDispatcher(retry.jdbc, retry.workloads, retry.builder,
                    "https://cloud-internal.trinyx.private:8443/internal/v1/entitlement-projections")
                    .dispatch();
            assertThat(retry.jdbc.sql).anyMatch(sql -> sql.contains("status='FAILED'"));
            assertThat(retry.jdbc.sql).noneMatch(sql -> sql.contains("status='DEAD'"));
        }
    }

    @Test
    void identityFourHundredIsDeadAndTimeoutRetries() {
        Harness terminal = new Harness();
        terminal.throwResponse(HttpStatus.UNAUTHORIZED);
        new IdentityBindingOutboxDispatcher(terminal.jdbc, terminal.workloads, terminal.builder,
                "https://cloud-internal.trinyx.private:8443/internal/v1/identity-bindings/revocations")
                .dispatch();
        assertThat(terminal.jdbc.sql).anyMatch(sql -> sql.contains("status='DEAD'"));

        Harness retry = new Harness();
        when(retry.http.exchange(anyString(), eq(HttpMethod.PUT), any(HttpEntity.class),
                eq(Void.class))).thenThrow(new ResourceAccessException("timeout"));
        new IdentityBindingOutboxDispatcher(retry.jdbc, retry.workloads, retry.builder,
                "https://cloud-internal.trinyx.private:8443/internal/v1/identity-bindings/revocations")
                .dispatch();
        assertThat(retry.jdbc.sql).anyMatch(sql -> sql.contains("status='FAILED'"));
        assertThat(retry.jdbc.sql).noneMatch(sql -> sql.contains("status='DEAD'"));
    }

    private static final class Harness {
        final FakeJdbc jdbc = new FakeJdbc();
        final RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        final RestTemplate http = mock(RestTemplate.class);
        final WorkloadAuthenticationService workloads = mock(WorkloadAuthenticationService.class);

        Harness() {
            when(builder.connectTimeout(any(Duration.class))).thenReturn(builder);
            when(builder.readTimeout(any(Duration.class))).thenReturn(builder);
            when(builder.build()).thenReturn(http);
            when(workloads.issue("trinyx-paid-authority")).thenReturn("workload");
        }

        void throwResponse(HttpStatus status) {
            RestClientResponseException exception = status.is4xxClientError()
                    ? new HttpClientErrorException(status, status.getReasonPhrase(),
                            HttpHeaders.EMPTY, "failure".getBytes(StandardCharsets.UTF_8),
                            StandardCharsets.UTF_8)
                    : new HttpServerErrorException(status, status.getReasonPhrase(),
                            HttpHeaders.EMPTY, "failure".getBytes(StandardCharsets.UTF_8),
                            StandardCharsets.UTF_8);
            when(http.exchange(anyString(), eq(HttpMethod.PUT), any(HttpEntity.class),
                    eq(Void.class))).thenThrow(exception);
        }
    }

    private static final class FakeJdbc extends JdbcTemplate {
        final UUID eventId = UUID.randomUUID();
        final List<String> sql = new ArrayList<>();

        @Override
        public <T> List<T> query(String statement, RowMapper<T> mapper) {
            return map(mapper);
        }

        @Override
        public <T> List<T> query(String statement, RowMapper<T> mapper, Object... args) {
            return map(mapper);
        }

        private <T> List<T> map(RowMapper<T> mapper) {
            try {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getObject("event_id", UUID.class)).thenReturn(eventId);
                when(rs.getString("signed_jws")).thenReturn("signed");
                when(rs.getInt("attempt_count")).thenReturn(0);
                when(rs.getObject(1, UUID.class)).thenReturn(eventId);
                when(rs.getString(2)).thenReturn("signed");
                when(rs.getInt(3)).thenReturn(0);
                return List.of(mapper.mapRow(rs, 0));
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        @Override
        public int update(String statement, Object... args) {
            sql.add(statement);
            return 1;
        }
    }
}
