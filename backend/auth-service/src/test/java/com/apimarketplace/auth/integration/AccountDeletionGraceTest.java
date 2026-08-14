package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.AccountPurgeScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who the nightly purge picks up, and who it must leave alone, on a real database.
 *
 * <p>Two production defects meet here. The first: {@code deactivated_at} was a one-way door, so a
 * user deactivated on 2026-08-09 who signed in again on 2026-08-10 was refused and stayed queued
 * for irreversible deletion, with the deactivation e-mail promising 30 days to change their mind.
 * The answer to that is the restore endpoint plus the interstitial, which clear the column.
 *
 * <p>The second is what these tests now pin: the fix for the first must not become "a deactivated
 * account is never deleted". Excluding anyone whose {@code lastLoginAt} is newer than their
 * {@code deactivatedAt} looks like "they came back", but that timestamp is refreshed by gateway
 * resolution on every request a blocked account makes, including the one the interstitial itself
 * issues. Selection is therefore the deactivation date alone, and the date reported to the user is
 * the date the purge acts on.
 *
 * <p>Repository-level on purpose: the guard has to hold in the SQL, not in a service a future
 * caller might bypass, and only a real database says whether the JPQL means what it reads like.
 */
@SpringBootTest
@DisplayName("Account deletion grace period - who gets purged (real Postgres)")
class AccountDeletionGraceTest extends AuthPostgresIntegrationTest {

    @Autowired private UserRepository userRepository;

    /** The production value, read from the scheduler rather than mirrored, so a change to it is
     *  exercised here instead of silently diverging from what the purge really does. */
    private static final int GRACE_DAYS = AccountPurgeScheduler.GRACE_PERIOD_DAYS;

    private LocalDateTime cutoff;

    @BeforeEach
    void reset() {
        userRepository.deleteAll();
        cutoff = LocalDateTime.now().minusDays(GRACE_DAYS);
    }

    private User user(String email, LocalDateTime deactivatedAt, LocalDateTime lastLoginAt, boolean enabled) {
        User u = new User();
        u.setEmail(email);
        u.setUsername(email);
        u.setEnabled(enabled);
        u.setDeactivatedAt(deactivatedAt);
        u.setLastLoginAt(lastLoginAt);
        return userRepository.save(u);
    }

    @Test
    @DisplayName("an account past the grace period IS purged")
    void abandonedAccountIsPurged() {
        LocalDateTime deactivated = LocalDateTime.now().minusDays(GRACE_DAYS + 10);
        User u = user("gone@test.local", deactivated, deactivated.minusHours(2), false);

        assertThat(userRepository.findAccountsPastGracePeriod(cutoff))
                .extracting(User::getId).contains(u.getId());
    }

    @Test
    @DisplayName("signing in during the grace period does NOT move the deletion date")
    void signingInDoesNotDeferTheDeletion() {
        // The account is still selected on the day it was promised. Excluding it here would mean
        // deleting on day 60 while getDeletionStatus keeps telling this very person "day 30", and
        // the interstitial's own status request is enough to set this timestamp.
        LocalDateTime deactivated = LocalDateTime.now().minusDays(GRACE_DAYS + 10);
        User u = user("looked-and-left@test.local", deactivated, LocalDateTime.now(), false);

        assertThat(userRepository.findAccountsPastGracePeriod(cutoff))
                .as("the advertised deletion date has to be the date the purge acts on")
                .extracting(User::getId).contains(u.getId());
    }

    @Test
    @DisplayName("an account that never recorded a login IS purged")
    void accountWithNoLoginEverIsPurged() {
        User u = user("never-signed-in@test.local", LocalDateTime.now().minusDays(GRACE_DAYS + 10), null, false);

        assertThat(userRepository.findAccountsPastGracePeriod(cutoff))
                .extracting(User::getId).contains(u.getId());
    }

    @Test
    @DisplayName("an account still inside the grace period is not purged yet")
    void insideGracePeriodIsNotPurged() {
        User u = user("recent@test.local", LocalDateTime.now().minusDays(3), null, false);

        assertThat(userRepository.findAccountsPastGracePeriod(cutoff))
                .extracting(User::getId).doesNotContain(u.getId());
    }

    @Test
    @DisplayName("cancelling the deletion takes the account out of the purge for good")
    void restoredAccountIsNeverPurged() {
        // This is what actually protects someone who changes their mind: restoreUser clears the
        // column this query selects on, so the row can never be picked up again.
        User u = user("restored@test.local", null, LocalDateTime.now(), true);

        List<User> candidates = userRepository.findAccountsPastGracePeriod(cutoff);
        assertThat(candidates).extracting(User::getId).doesNotContain(u.getId());
    }

    @Test
    @DisplayName("a disabled account with no deletion date is never a purge candidate")
    void operatorSuspendedAccountIsNeverPurged() {
        // enabled=false without deactivated_at is an operator suspension, not a deletion request.
        User u = user("suspended@test.local", null, LocalDateTime.now(), false);

        assertThat(userRepository.findAccountsPastGracePeriod(cutoff))
                .extracting(User::getId).doesNotContain(u.getId());
    }

    @Test
    @DisplayName("a never-deactivated active account is never a purge candidate")
    void activeAccountIsNeverPurged() {
        User u = user("active@test.local", null, LocalDateTime.now(), true);

        assertThat(userRepository.findAccountsPastGracePeriod(cutoff))
                .extracting(User::getId).doesNotContain(u.getId());
    }
}
