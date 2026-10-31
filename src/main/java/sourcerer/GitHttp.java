package sourcerer;

import dev.sourcerer.core.ApiException;
import dev.sourcerer.core.Principal;
import dev.sourcerer.service.Platform;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;

/** Wiring of JGit's smart-HTTP servlet to our repositories, authentication and cache invalidation. */
public final class GitHttp {
    private static final Pattern PATH = Pattern.compile("^/git/([^/]+)/([^/]+)\\.git(/.*)?$");
    private static final long MAX_OBJECT_BYTES = 100L << 20;

    private GitHttp() {}

    /** Parses {@code owner/name.git} (as handed over by GitServlet, without the /git prefix). */
    static String[] parseName(String name) {
        int slash = name.indexOf('/');
        if (slash <= 0 || !name.endsWith(".git")) return null;
        return new String[] {name.substring(0, slash), name.substring(slash + 1, name.length() - 4)};
    }

    public static final class Resolver implements RepositoryResolver<HttpServletRequest> {
        private final Platform platform;

        public Resolver(Platform platform) { this.platform = platform; }

        @Override
        public Repository open(HttpServletRequest req, String name) throws RepositoryNotFoundException {
            String[] on = parseName(name);
            if (on == null) throw new RepositoryNotFoundException(name);
            try {
                Repository r = platform.git.open(on[0], on[1]);
                r.incrementOpen(); // GitServlet closes the handle after the request; ours is cached and shared
                return r;
            } catch (ApiException e) {
                throw new RepositoryNotFoundException(name);
            }
        }
    }

    public static final class ReceivePackFactory implements org.eclipse.jgit.transport.resolver.ReceivePackFactory<HttpServletRequest> {
        private final Platform platform;

        public ReceivePackFactory(Platform platform) { this.platform = platform; }

        @Override
        public ReceivePack create(HttpServletRequest req, Repository db) {
            Matcher m = PATH.matcher(req.getRequestURI());
            String owner = m.matches() ? m.group(1) : null;
            String name = m.matches() ? m.group(2) : null;
            ReceivePack rp = new ReceivePack(db);
            rp.setMaxObjectSizeLimit(MAX_OBJECT_BYTES);
            rp.setPostReceiveHook((pack, cmds) -> {
                if (owner != null) platform.repoChanged(owner, name);
            });
            return rp;
        }
    }

    /** Authenticates (HTTP Basic: password or access token) and authorises before JGit sees the request. */
    public static final class AuthFilter implements Filter {
        private final Platform platform;

        public AuthFilter(Platform platform) { this.platform = platform; }

        @Override
        public void doFilter(ServletRequest rq, ServletResponse rs, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest req = (HttpServletRequest) rq;
            HttpServletResponse res = (HttpServletResponse) rs;
            Matcher m = PATH.matcher(req.getRequestURI());
            if (!m.matches()) {
                res.sendError(404);
                return;
            }
            String owner = m.group(1), name = m.group(2), rest = m.group(3) == null ? "" : m.group(3);
            boolean write = rest.endsWith("/git-receive-pack") || "git-receive-pack".equals(req.getParameter("service"));
            Principal who = null;
            try {
                who = credentials(req);
                if (write) platform.repos.requireOwner(who, owner, name);
                else platform.repos.requireReadable(who, owner, name);
            } catch (ApiException e) {
                // Anonymous callers get a challenge so git clients prompt for credentials.
                if (e.status() == 401 || who == null) {
                    res.setHeader("WWW-Authenticate", "Basic realm=\"Sourcerer\", charset=\"UTF-8\"");
                    res.sendError(401);
                } else {
                    res.sendError(e.status() == 404 ? 404 : 403);
                }
                return;
            }
            chain.doFilter(rq, rs);
        }

        private Principal credentials(HttpServletRequest req) {
            String h = req.getHeader("Authorization");
            if (h == null) return null;
            if (h.regionMatches(true, 0, "Basic ", 0, 6)) {
                String[] up = new String(Base64.getDecoder().decode(h.substring(6).trim()), StandardCharsets.UTF_8)
                        .split(":", 2);
                return platform.auth.fromBasic(up[0], up.length > 1 ? up[1] : "");
            }
            if (h.regionMatches(true, 0, "Bearer ", 0, 7)) return platform.auth.fromBearer(h.substring(7).trim());
            throw ApiException.unauthorized("unsupported authorization scheme");
        }
    }
}
