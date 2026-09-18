package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ServerProviderTokensTest {
    private static final String A = "a".repeat(128), B = "b".repeat(128);
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance() { now = now.plusSeconds(601); }
    }

    @Test void tokensAreUniqueScopedSingleTransferAndExpire() {
        MutableClock clock = new MutableClock();
        ServerProviderTokens tokens = new ServerProviderTokens(clock);
        String first = tokens.issue(Set.of(A));
        String second = tokens.issue(Set.of(A));
        assertTrue(first.matches("[0-9a-f]{64}"));
        assertNotEquals(first, second);
        assertFalse(tokens.acquire("0".repeat(64), A));
        assertFalse(tokens.acquire(first, B));
        assertTrue(tokens.acquire(first, A));
        assertFalse(tokens.acquire(first, A));
        tokens.release(first);
        assertTrue(tokens.acquire(first, A));
        clock.advance();
        assertFalse(tokens.acquire(first, A));
        assertFalse(tokens.known(second));
        tokens.clear();
    }
}
