package dev.sourcerer.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.sourcerer.core.ApiException;
import dev.sourcerer.core.Json;
import dev.sourcerer.core.Models.*;
import dev.sourcerer.core.Principal;
import dev.sourcerer.gitstore.GitService;
import java.io.OutputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read path for repository details and file browsing. This is the hot path, so responses are
 * cached as pre-serialised JSON. Anything addressed by commit sha is immutable and never
 * needs invalidation; only the sha a ref points at (cached for seconds, invalidated on push).
 */
public final class BrowseService {
    /** A cacheable response body. {@code etag} is null when the body must not be revalidated. */
    public record Cached(byte[] body, String etag) {}

    private final RepoService repos;
    private final GitService git;
    private final Cache<String, byte[]> content = Caffeine.newBuilder()
            .maximumWeight(256L << 20)
            .weigher((String k, byte[] v) -> v.length + k.length())
            .build();
    private final Cache<String, byte[]> details = Caffeine.newBuilder()
            .maximumSize(50_000).expireAfterWrite(Duration.ofSeconds(2)).build();

    public BrowseService(RepoService repos, GitService git) {
        this.repos = repos;
        this.git = git;
    }

    public void invalidate(String owner, String name) { details.invalidate(owner + "/" + name); }

    public Cached details(Principal who, String owner, String name) {
        Repo r = repos.requireReadable(who, owner, name);
        byte[] b = details.get(r.owner() + "/" + r.name(), id -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("repo", r);
            m.put("stats", git.stats(r.owner(), r.name(), r.defaultBranch()));
            m.put("cloneUrlPath", "/git/" + r.owner() + "/" + r.name() + ".git");
            return Json.bytes(m);
        });
        return new Cached(b, null);
    }

    public Cached branches(Principal who, String owner, String name) {
        Repo r = repos.requireReadable(who, owner, name);
        return new Cached(Json.bytes(git.branches(r.owner(), r.name(), r.defaultBranch())), null);
    }

    public Cached commits(Principal who, String owner, String name, String ref, int page, int perPage) {
        Repo r = repos.requireReadable(who, owner, name);
        String sha = sha(r, ref);
        perPage = Math.min(Math.max(perPage, 1), 100);
        page = Math.max(page, 1);
        int pp = perPage, pg = page;
        String key = r.id() + ":" + sha + ":c:" + pg + ":" + pp;
        byte[] b = content.get(key, k -> Json.bytes(Map.of("commit", sha, "page", pg, "perPage", pp,
                "commits", git.log(r.owner(), r.name(), sha, (pg - 1) * pp, pp))));
        return new Cached(b, quote(sha + "-c" + pg + "-" + pp));
    }

    public Cached tree(Principal who, String owner, String name, String ref, String path) {
        Repo r = repos.requireReadable(who, owner, name);
        String p = GitService.normalizePath(path);
        String sha = sha(r, ref);
        byte[] b = content.get(r.id() + ":" + sha + ":t:" + p, k -> Json.bytes(Map.of(
                "commit", sha, "path", p, "entries", git.tree(r.owner(), r.name(), sha, p))));
        return new Cached(b, quote(sha));
    }

    public Cached blob(Principal who, String owner, String name, String ref, String path) {
        Repo r = repos.requireReadable(who, owner, name);
        String p = GitService.normalizePath(path);
        String sha = sha(r, ref);
        byte[] b = content.get(r.id() + ":" + sha + ":b:" + p, k -> {
            BlobInfo blob = git.blob(r.owner(), r.name(), sha, p);
            return Json.bytes(Map.of("commit", sha, "path", p, "blob", blob));
        });
        return new Cached(b, quote(sha));
    }

    /** Streams the raw file; returns the commit sha used (for ETag), writing nothing if {@code ifNoneMatch} hits. */
    public String raw(Principal who, String owner, String name, String ref, String path, OutputStream out) {
        Repo r = repos.requireReadable(who, owner, name);
        String sha = sha(r, ref);
        git.raw(r.owner(), r.name(), sha, GitService.normalizePath(path), out);
        return sha;
    }

    public String resolveForRaw(Principal who, String owner, String name, String ref) {
        return sha(repos.requireReadable(who, owner, name), ref);
    }

    private String sha(Repo r, String ref) {
        String sha = git.resolve(r.owner(), r.name(), ref == null || ref.isBlank() ? r.defaultBranch() : ref);
        if (sha == null) throw ApiException.notFound("ref not found (is the repository empty?)");
        return sha;
    }

    private static String quote(String s) { return "\"" + s + "\""; }
}
