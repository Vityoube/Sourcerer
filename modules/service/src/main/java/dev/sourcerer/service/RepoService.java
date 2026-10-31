package dev.sourcerer.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.sourcerer.core.ApiException;
import dev.sourcerer.core.Models.Repo;
import dev.sourcerer.core.Principal;
import dev.sourcerer.core.Stores.RepoStore;
import dev.sourcerer.gitstore.GitService;
import java.time.Duration;
import java.util.List;

/** Repository lifecycle and authorisation. Metadata lookups are cached (they sit on every request). */
public final class RepoService {
    private final RepoStore store;
    private final GitService git;
    private final Cache<String, Repo> cache = Caffeine.newBuilder()
            .maximumSize(100_000).expireAfterWrite(Duration.ofSeconds(30)).build();

    public RepoService(RepoStore store, GitService git) {
        this.store = store;
        this.git = git;
    }

    public Repo create(Principal who, String name, String description, boolean isPrivate,
                       String defaultBranch, boolean initReadme) {
        if (who == null) throw ApiException.unauthorized("authentication required");
        name = name == null ? "" : name.toLowerCase();
        if (!GitService.validSegment(name)) {
            throw ApiException.badRequest("name must be 1-64 chars of a-z, 0-9, '.', '_' or '-' and not end with .git");
        }
        description = description == null ? "" : description;
        if (description.length() > 1000) throw ApiException.badRequest("description too long");
        String branch = defaultBranch == null || defaultBranch.isBlank() ? "main" : defaultBranch.strip();
        if (store.find(who.username(), name).isPresent()) throw ApiException.conflict("repository already exists");
        Repo repo = store.create(who.username(), name, description, branch, isPrivate);
        try {
            String readme = initReadme ? "# " + name + "\n\n" + description + "\n" : null;
            git.create(who.username(), name, branch, readme, who.username(), who.username() + "@sourcerer.local");
        } catch (RuntimeException e) {
            store.delete(repo.id()); // keep metadata and disk consistent
            throw e;
        }
        return repo;
    }

    /** Anonymous callers ({@code who == null}) may read public repositories only. */
    public Repo requireReadable(Principal who, String owner, String name) {
        Repo r = find(owner, name);
        if (r.isPrivate() && (who == null || !who.username().equals(r.owner()))) {
            throw ApiException.notFound("repository not found"); // don't leak existence
        }
        return r;
    }

    public Repo requireOwner(Principal who, String owner, String name) {
        Repo r = requireReadable(who, owner, name);
        if (who == null) throw ApiException.unauthorized("authentication required");
        if (!who.username().equals(r.owner())) throw ApiException.forbidden("only the owner may do this");
        return r;
    }

    private Repo find(String owner, String name) {
        if (!GitService.validSegment(owner) || !GitService.validSegment(name)) {
            throw ApiException.notFound("repository not found");
        }
        String key = owner + "/" + name;
        Repo r = cache.getIfPresent(key);
        if (r != null) return r;
        r = store.find(owner, name).orElseThrow(() -> ApiException.notFound("repository not found"));
        cache.put(key, r);
        return r;
    }

    public List<Repo> list(Principal who, String owner, int limit, int offset) {
        // Over-fetch is avoided by filtering in SQL terms only for the owner; private repos are
        // visible solely to their owner, so filter after the page is read.
        return store.list(owner, Math.min(Math.max(limit, 1), 100), Math.max(offset, 0)).stream()
                .filter(r -> !r.isPrivate() || (who != null && who.username().equals(r.owner())))
                .toList();
    }

    public void delete(Principal who, String owner, String name) {
        Repo r = requireOwner(who, owner, name);
        store.delete(r.id());
        cache.invalidate(owner + "/" + name);
        git.delete(owner, name);
    }

    public void invalidate(String owner, String name) {
        cache.invalidate(owner + "/" + name);
        git.invalidate(owner, name);
    }
}
