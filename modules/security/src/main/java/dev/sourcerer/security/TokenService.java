package dev.sourcerer.security;

import dev.sourcerer.core.Principal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stateless HMAC-SHA256 signed tokens: {@code sct_<b64(uid|username|expEpochSec)>.<b64(mac)>}.
 * Verification costs one HMAC and no I/O, so authentication never touches the database on hot paths.
 */
public final class TokenService {
    public static final String PREFIX = "sct_";
    private static final String ALG = "HmacSHA256";

    private final SecretKeySpec key;
    private final Clock clock;

    public TokenService(byte[] secret) { this(secret, Clock.systemUTC()); }

    public TokenService(byte[] secret, Clock clock) {
        if (secret.length < 32) throw new IllegalArgumentException("token secret must be >= 32 bytes");
        this.key = new SecretKeySpec(secret, ALG);
        this.clock = clock;
    }

    public String issue(Principal p, long ttlSeconds) {
        long exp = clock.instant().getEpochSecond() + ttlSeconds;
        String payload = p.userId() + "|" + p.username() + "|" + exp;
        Base64.Encoder e = Base64.getUrlEncoder().withoutPadding();
        String body = e.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return PREFIX + body + "." + e.encodeToString(mac(body));
    }

    /** @return the principal, or {@code null} when the token is malformed, forged or expired. */
    public Principal verify(String token) {
        if (token == null || !token.startsWith(PREFIX)) return null;
        int dot = token.indexOf('.');
        if (dot < 0) return null;
        String body = token.substring(PREFIX.length(), dot);
        try {
            byte[] sig = Base64.getUrlDecoder().decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(sig, mac(body))) return null;
            String[] f = new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8).split("[|]");
            if (f.length != 3 || Long.parseLong(f[2]) < clock.instant().getEpochSecond()) return null;
            return new Principal(Long.parseLong(f[0]), f[1]);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private byte[] mac(String body) {
        try {
            Mac m = Mac.getInstance(ALG);
            m.init(key);
            return m.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
