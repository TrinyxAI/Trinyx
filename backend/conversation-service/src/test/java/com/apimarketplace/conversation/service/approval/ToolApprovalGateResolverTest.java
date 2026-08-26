package com.apimarketplace.conversation.service.approval;

import com.apimarketplace.conversation.client.StreamRedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Writing the verdict is the ONLY thing that resumes a tool call agent-service parked, so
 * the write must land under the exact key the gate polls. It is also best-effort: a Redis
 * problem must degrade to "the user resumes with a message", never to a failed click.
 *
 * <p>The return value carries real weight: the frontend skips its resume message only when
 * this reports a release. Reporting one that did not happen strands the conversation.
 */
@DisplayName("ToolApprovalGateResolver - releasing a parked tool call")
class ToolApprovalGateResolverTest {

    private static final String KEY = StreamRedisKeys.approvalDecisionKey("conv-1", "call-1");

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ToolApprovalGateResolver resolver;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        resolver = new ToolApprovalGateResolver(redisTemplate);
    }

    @Test
    @DisplayName("Approving writes 'approved' under the key the gate polls")
    void approvingWritesTheApprovedVerdict() {
        when(valueOps.setIfPresent(KEY, "approved", StreamRedisKeys.APPROVAL_DECISION_TTL))
                .thenReturn(true);

        assertThat(resolver.resolve("conv-1", "call-1", true)).isTrue();

        verify(valueOps).setIfPresent(KEY, "approved", StreamRedisKeys.APPROVAL_DECISION_TTL);
    }

    @Test
    @DisplayName("Refusing writes 'denied' so the held call stops instead of waiting out its deadline")
    void refusingWritesTheDeniedVerdict() {
        when(valueOps.setIfPresent(KEY, "denied", StreamRedisKeys.APPROVAL_DECISION_TTL))
                .thenReturn(true);

        assertThat(resolver.resolve("conv-1", "call-1", false)).isTrue();

        verify(valueOps).setIfPresent(KEY, "denied", StreamRedisKeys.APPROVAL_DECISION_TTL);
    }

    @Test
    @DisplayName("Nobody holding any more reports 'not released', and writes no orphan verdict")
    void nothingParkedReportsNotReleased() {
        // Redis answers "the key is not there": the park gave up (its deadline passed) or
        // never existed. Writing anyway would leave a verdict no one will ever read, and
        // reporting true would make the frontend skip the resume - the click would silently
        // do nothing and the conversation would sit there, finished but unanswered.
        when(valueOps.setIfPresent(anyString(), anyString(), any(java.time.Duration.class)))
                .thenReturn(false);

        assertThat(resolver.resolve("conv-1", "call-1", true)).isFalse();
        verify(valueOps, never()).set(anyString(), anyString(), any(java.time.Duration.class));
    }

    @Test
    @DisplayName("A card with no held call (no gate key) is a no-op, not a stray key")
    void noGateKeyIsANoOp() {
        assertThat(resolver.resolve("conv-1", null, true)).isFalse();
        assertThat(resolver.resolve("conv-1", "   ", true)).isFalse();
        assertThat(resolver.resolve(null, "call-1", true)).isFalse();
        assertThat(resolver.resolve("  ", "call-1", true)).isFalse();

        verify(valueOps, never()).setIfPresent(anyString(), anyString(), any(java.time.Duration.class));
    }

    @Test
    @DisplayName("A Redis failure reports 'not released' instead of failing the user's click")
    void redisFailureDoesNotBreakTheClick() {
        when(valueOps.setIfPresent(anyString(), anyString(), any(java.time.Duration.class)))
                .thenThrow(new IllegalStateException("redis down"));

        assertThatCode(() -> assertThat(resolver.resolve("conv-1", "call-1", true)).isFalse())
                .doesNotThrowAnyException();
    }
}
