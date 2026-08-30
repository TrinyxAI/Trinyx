package com.apimarketplace.auth.web.version;

import com.apimarketplace.auth.domain.CeInstall;
import com.apimarketplace.auth.repository.CeInstallRepository;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The install id is read on a path that must never fail: it hangs off the daily update check, and
 * an install that cannot report itself must still learn it is behind. These tests pin that
 * asymmetry (identity is optional, the update check is not) and the caching rule that makes a
 * failure recoverable.
 */
class CeInstallIdProviderTest {

    private static final UUID INSTALL = UUID.fromString("2f1c9b4e-1111-4c2a-9a1b-6d5e4f3a2b1c");

    /** Builds the entity the migration seeds; its fields are set by JPA, not by a constructor. */
    private static CeInstall seededRow(UUID id) {
        CeInstall row = mock(CeInstall.class);
        when(row.getInstallId()).thenReturn(id);
        return row;
    }

    @Test
    @DisplayName("returns the seeded install id")
    void returnsSeededId() {
        CeInstallRepository repository = mock(CeInstallRepository.class);
        CeInstall row = seededRow(INSTALL);
        when(repository.findById(CeInstall.SINGLETON_ID)).thenReturn(Optional.of(row));

        assertThat(new CeInstallIdProvider(repository).current()).contains(INSTALL);
    }

    @Test
    @DisplayName("reads the row once and serves the cached id afterwards")
    void cachesAfterFirstRead() {
        CeInstallRepository repository = mock(CeInstallRepository.class);
        CeInstall row = seededRow(INSTALL);
        when(repository.findById(CeInstall.SINGLETON_ID)).thenReturn(Optional.of(row));
        CeInstallIdProvider provider = new CeInstallIdProvider(repository);

        provider.current();
        provider.current();
        provider.current();

        // The id is immutable once seeded, so re-querying it on every daily poll would be pure
        // waste; this is the assertion that keeps it that way.
        verify(repository, times(1)).findById(CeInstall.SINGLETON_ID);
    }

    @Test
    @DisplayName("empty when the row is missing, and does not cache that as an answer")
    void missingRowIsNotCached() {
        CeInstallRepository repository = mock(CeInstallRepository.class);
        CeInstall row = seededRow(INSTALL);
        when(repository.findById(CeInstall.SINGLETON_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(row));
        CeInstallIdProvider provider = new CeInstallIdProvider(repository);

        assertThat(provider.current()).isEmpty();

        // Caching the miss would make an install that started before its migration finished stay
        // uncounted for the whole life of the process.
        assertThat(provider.current()).contains(INSTALL);
    }

    @Test
    @DisplayName("a database failure yields no id instead of propagating")
    void databaseFailureIsSwallowed() {
        CeInstallRepository repository = mock(CeInstallRepository.class);
        when(repository.findById(any())).thenThrow(new IllegalStateException("no connection"));
        CeInstallIdProvider provider = new CeInstallIdProvider(repository);

        // The caller is the release-feed poll. If reading the id could throw, an unreachable
        // database would stop the fleet from learning about a security release.
        assertThatCode(() -> assertThat(provider.current()).isEmpty()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("announces once, when the id first becomes readable, naming the opt-out")
    void announcesOnceOnFirstResolution() {
        CeInstallRepository repository = mock(CeInstallRepository.class);
        CeInstall row = seededRow(INSTALL);
        when(repository.findById(CeInstall.SINGLETON_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(row));
        Logger logger = (Logger) LoggerFactory.getLogger(CeInstallIdProvider.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        Level original = logger.getLevel();
        // Pinned, because another test in the same JVM boots a Spring context that reconfigures
        // logback globally; without this the assertion depends on whichever class ran first.
        logger.setLevel(Level.INFO);
        logger.addAppender(events);
        CeInstallIdProvider provider = new CeInstallIdProvider(repository);

        try {
            provider.current();  // unreadable: nothing to announce yet
            provider.current();  // first success
            provider.current();  // cached
        } finally {
            logger.detachAppender(events);
            // original is null when the level was inherited rather than set; setLevel(null)
            // restores exactly that, which is why it is passed back unguarded.
            logger.setLevel(original);
        }

        // This line is the whole "an operator can discover it without reading the source"
        // guarantee. It fires when the id first becomes readable rather than on a startup event,
        // so it cannot be skipped when the database was not up yet, and it cannot be preceded by
        // the first poll that sends the id.
        // Matched on the distinctive phrase, not on "anonymous install id": the DEBUG line for an
        // unreadable row contains that substring too, so the looser filter counted it as a second
        // notice whenever ambient config left DEBUG enabled.
        List<ILoggingEvent> notices = events.list.stream()
                .filter(e -> e.getFormattedMessage().contains("reports an anonymous install id"))
                .toList();
        assertThat(notices).hasSize(1);
        assertThat(notices.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(notices.get(0).getFormattedMessage())
                .contains(INSTALL.toString())
                .contains("ce.version-check.send-install-id=false");
    }
}
