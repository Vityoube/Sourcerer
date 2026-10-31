package dev.sourcerer.service;

import dev.sourcerer.core.ApiException;
import dev.sourcerer.core.Models.Comment;
import dev.sourcerer.core.Models.Issue;
import dev.sourcerer.core.Models.Repo;
import dev.sourcerer.core.Principal;
import dev.sourcerer.core.Stores.IssueStore;
import java.util.List;

public final class IssueService {
    private final IssueStore store;
    private final RepoService repos;

    public IssueService(IssueStore store, RepoService repos) {
        this.store = store;
        this.repos = repos;
    }

    static String requireText(String v, String field, int max, boolean required) {
        String s = v == null ? "" : v.strip();
        if (required && s.isEmpty()) throw ApiException.badRequest(field + " is required");
        if (s.length() > max) throw ApiException.badRequest(field + " too long (max " + max + ")");
        return s;
    }

    static String requireState(String s) {
        if (s != null && !s.equals("open") && !s.equals("closed")) {
            throw ApiException.badRequest("state must be 'open' or 'closed'");
        }
        return s;
    }

    public Issue create(Principal who, String owner, String name, String title, String body) {
        if (who == null) throw ApiException.unauthorized("authentication required");
        Repo r = repos.requireReadable(who, owner, name);
        return store.create(r.id(), requireText(title, "title", 255, true), requireText(body, "body", 65000, false),
                who.username());
    }

    public List<Issue> list(Principal who, String owner, String name, String state, int limit, int offset) {
        Repo r = repos.requireReadable(who, owner, name);
        return store.list(r.id(), requireState(state), Math.min(Math.max(limit, 1), 100), Math.max(offset, 0));
    }

    public Issue get(Principal who, String owner, String name, int number) {
        Repo r = repos.requireReadable(who, owner, name);
        return store.find(r.id(), number).orElseThrow(() -> ApiException.notFound("issue not found"));
    }

    public List<Comment> comments(Principal who, String owner, String name, int number) {
        return store.comments(get(who, owner, name, number).id());
    }

    public Issue setState(Principal who, String owner, String name, int number, String state) {
        if (who == null) throw ApiException.unauthorized("authentication required");
        if (state == null) throw ApiException.badRequest("state is required");
        Issue i = get(who, owner, name, number);
        requireState(state);
        if (!who.username().equals(i.author()) && !who.username().equals(owner)) {
            throw ApiException.forbidden("only the author or the repository owner may change the state");
        }
        return store.setState(i.repoId(), number, state);
    }

    public Comment comment(Principal who, String owner, String name, int number, String body) {
        if (who == null) throw ApiException.unauthorized("authentication required");
        Issue i = get(who, owner, name, number);
        return store.addComment(i.id(), who.username(), requireText(body, "body", 65000, true));
    }
}
