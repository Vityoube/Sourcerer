package dev.sourcerer.security;

import static org.junit.jupiter.api.Assertions.*;

import dev.sourcerer.core.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SecurityTest {
    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes();

    @Test void passwordRoundTrip() {
        String h = PasswordHasher.hash("correct horse");
        assertTrue(PasswordHasher.verify("correct horse", h));
        assertFalse(PasswordHasher.verify("wrong", h));
    }

    @Test void tokenRoundTripAndTamper() {
        TokenService t = new TokenService(SECRET);
        String tok = t.issue(new Principal(7, "alice"), 60);
        assertEquals(new Principal(7, "alice"), t.verify(tok));
        assertNull(t.verify(tok + "x"));
        assertNull(t.verify(new TokenService("ffffffffffffffffffffffffffffffff".getBytes()).issue(new Principal(7, "alice"), 60)));
        assertNull(t.verify("garbage"));
    }

    @Test void tokenExpires() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        String tok = new TokenService(SECRET, Clock.fixed(now, ZoneOffset.UTC)).issue(new Principal(1, "bob"), 10);
        Clock later = Clock.fixed(now.plus(Duration.ofSeconds(11)), ZoneOffset.UTC);
        assertNull(new TokenService(SECRET, later).verify(tok));
    }
}
