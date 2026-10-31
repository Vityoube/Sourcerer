package dev.sourcerer.security;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** PBKDF2-HMAC-SHA256, self-describing format: {@code pbkdf2$iterations$salt$hash}. */
public final class PasswordHasher {
    private static final int ITERATIONS = 120_000;
    private static final SecureRandom RNG = new SecureRandom();

    private PasswordHasher() {}

    public static String hash(String password) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        byte[] h = derive(password, salt, ITERATIONS);
        Base64.Encoder e = Base64.getEncoder().withoutPadding();
        return "pbkdf2$" + ITERATIONS + "$" + e.encodeToString(salt) + "$" + e.encodeToString(h);
    }

    public static boolean verify(String password, String stored) {
        String[] p = stored.split("[$]");
        if (p.length != 4 || !p[0].equals("pbkdf2")) return false;
        Base64.Decoder d = Base64.getDecoder();
        byte[] expected = d.decode(p[3]);
        byte[] actual = derive(password, d.decode(p[2]), Integer.parseInt(p[1]));
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
