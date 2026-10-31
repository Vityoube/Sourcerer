package dev.sourcerer.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.sourcerer.core.ApiException;
import dev.sourcerer.core.Models.User;
import dev.sourcerer.core.Models.UserCredentials;
import dev.sourcerer.core.Principal;
import dev.sourcerer.core.Stores.UserStore;
import dev.sourcerer.security.PasswordHasher;
import dev.sourcerer.security.TokenService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

public final class AuthService {
    public static final long LOGIN_TTL_SECONDS = 12 * 3600L;
    public static final long MAX_TOKEN_TTL_SECONDS = 365 * 86400L;
    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9_-]{2,31}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,189}$");
    /** Used to keep login timing identical for unknown users. */
    private static final String DUMMY_HASH = PasswordHasher.hash("dummy-password-for-timing");

    private final UserStore users;
    private final TokenService tokens;
    /** Successful password checks for Basic auth (git clients re-send credentials on every request). */
    private final Cache<String, Principal> passwordCache = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(Duration.ofMinutes(5)).build();

    public AuthService(UserStore users, TokenService tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    public User register(String username, String email, String password) {
        username = username == null ? "" : username.toLowerCase();
        if (!USERNAME.matcher(username).matches()) {
            throw ApiException.badRequest("username must be 3-32 chars of a-z, 0-9, '_' or '-'");
        }
        if (email == null || !EMAIL.matcher(email).matches()) throw ApiException.badRequest("invalid email");
        if (password == null || password.length() < 8 || password.length() > 200) {
            throw ApiException.badRequest("password must be 8-200 characters");
        }
        if (users.findCredentials(username).isPresent()) throw ApiException.conflict("username already taken");
        return users.create(username, email.toLowerCase(), PasswordHasher.hash(password));
    }

    public Principal checkPassword(String username, String password) {
        if (username == null || password == null) throw ApiException.unauthorized("invalid credentials");
        String u = username.toLowerCase();
        String cacheKey = u + ":" + sha256(password);
        Principal cached = passwordCache.getIfPresent(cacheKey);
        if (cached != null) return cached;
        Optional<UserCredentials> c = users.findCredentials(u);
        boolean ok = PasswordHasher.verify(password, c.map(UserCredentials::passwordHash).orElse(DUMMY_HASH));
        if (!ok || c.isEmpty()) throw ApiException.unauthorized("invalid credentials");
        Principal p = new Principal(c.get().user().id(), c.get().user().username());
        passwordCache.put(cacheKey, p);
        return p;
    }

    public String login(String username, String password) {
        return tokens.issue(checkPassword(username, password), LOGIN_TTL_SECONDS);
    }

    public String issueToken(Principal p, long ttlSeconds) {
        if (ttlSeconds <= 0 || ttlSeconds > MAX_TOKEN_TTL_SECONDS) {
            throw ApiException.badRequest("ttlSeconds must be between 1 and " + MAX_TOKEN_TTL_SECONDS);
        }
        return tokens.issue(p, ttlSeconds);
    }

    public Principal fromBearer(String token) {
        Principal p = tokens.verify(token);
        if (p == null) throw ApiException.unauthorized("invalid or expired token");
        return p;
    }

    /** HTTP Basic: the secret may be an access token (cheap) or the account password (cached). */
    public Principal fromBasic(String username, String secret) {
        if (secret != null && secret.startsWith(TokenService.PREFIX)) {
            Principal p = tokens.verify(secret);
            if (p == null || (username != null && !username.isEmpty() && !p.username().equalsIgnoreCase(username))) {
                throw ApiException.unauthorized("invalid or expired token");
            }
            return p;
        }
        return checkPassword(username, secret);
    }

    public User me(Principal p) {
        return users.findById(p.userId()).orElseThrow(() -> ApiException.unauthorized("unknown user"));
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
