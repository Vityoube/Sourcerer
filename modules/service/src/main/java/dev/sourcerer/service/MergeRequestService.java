package dev.sourcerer.service;

import dev.sourcerer.core.ApiException;
import dev.sourcerer.core.Models.MergeOutcome;
import dev.sourcerer.core.Models.MergeRequest;
import dev.sourcerer.core.Models.Repo;
import dev.sourcerer.core.Principal;
import dev.sourcerer.core.Stores.MergeRequestStore;
import dev.sourcerer.gitstore.GitService;
import java.util.List;

/** Merge requests between two branches of the same repository (forks are out of scope). */
public final class MergeRequestService {
    private final MergeRequestStore store;
    private final RepoService repos;
    private final GitService git;

    public MergeRequestService(MergeRequestStore store, RepoService repos, GitService git) {
        this.store = store;
        this.repos = repos;
        this.git = git;
    }

    public MergeRequest create(Principal who, String owner, String name, String title, String description,
                               String source, String target) {
        if (who == null) throw ApiException.unauthorized("authentication required");
        Repo r = repos.requireReadable(who, owner, name);
        if (source == null || target == null || source.isBlank() || target.isBlank()) {
            throw ApiException.badRequest("sourceBranch and targetBranch are required");
        }
        if (source.equals(target)) throw ApiException.badRequest("source and target must differ");
        if (git.resolve(r.owner(), r.name(), "refs/heads/" + source) == null) throw ApiException.notFound("source branch not found");
        if (git.resolve(r.owner(), r.name(), "refs/heads/" + target) == null) throw ApiException.notFound("target branch not found");
        if (store.openExists(r.id(), source, target)) throw ApiException.conflict("an open merge request already exists for these branches");
        return store.create(r.id(), IssueService.requireText(title, "title", 255, true),
                IssueService.requireText(description, "description", 65000, false), who.username(), source, target);
    }

    public List<MergeRequest> list(Principal who, String owner, String name, String state, int limit, int offset) {
        Repo r = repos.requireReadable(who, owner, name);
        if (state != null && !List.of("open", "closed", "merged").contains(state)) {
            throw ApiException.badRequest("state must be open, closed or merged");
        }
        return store.list(r.id(), state, Math.min(Math.max(limit, 1), 100), Math.max(offset, 0));
    }

    public MergeRequest get(Principal who, String owner, String name, int number) {
        Repo r = repos.requireReadable(who, owner, name);
        return store.find(r.id(), number).orElseThrow(() -> ApiException.notFound("merge request not found"));
    }

    /** Dry-run: can the MR be merged right now without conflicts? */
    public MergeOutcome mergeability(Principal who, String owner, String name, int number) {
        MergeRequest mr = get(who, owner, name, number);
        if (!mr.state().equals("open")) throw ApiException.conflict("merge request is " + mr.state());
        String t = git.resolve(owner, name, "refs/heads/" + mr.targetBranch());
        String s = git.resolve(owner, name, "refs/heads/" + mr.sourceBranch());
        if (t == null || s == null) throw ApiException.conflict("a branch no longer exists");
        return git.checkMerge(owner, name, t, s);
    }

    public String diff(Principal who, String owner, String name, int number) {
        MergeRequest mr = get(who, owner, name, number);
        String base = git.resolve(owner, name, "refs/heads/" + mr.targetBranch());
        String head = git.resolve(owner, name, "refs/heads/" + mr.sourceBranch());
        if (base == null || head == null) throw ApiException.conflict("a branch no longer exists");
        return git.diff(owner, name, base, head);
    }

    public MergeRequest merge(Principal who, String owner, String name, int number) {
        Repo r = repos.requireOwner(who, owner, name);
        MergeRequest mr = get(who, owner, name, number);
        if (!mr.state().equals("open")) throw ApiException.conflict("merge request is " + mr.state());
        MergeOutcome res = git.merge(r.owner(), r.name(), mr.sourceBranch(), mr.targetBranch(),
                "Merge branch '" + mr.sourceBranch() + "' into '" + mr.targetBranch() + "'\n\n" + mr.title()
                        + "\n\nMerge request !" + mr.number(),
                who.username(), who.username() + "@sourcerer.local");
        if (!res.success()) {
            throw ApiException.conflict("merge conflicts in: " + String.join(", ", res.conflicts()));
        }
        repos.invalidate(owner, name);
        return store.markMerged(r.id(), number, res.commit());
    }

    public MergeRequest setState(Principal who, String owner, String name, int number, String state) {
        if (who == null) throw ApiException.unauthorized("authentication required");
        if (!"open".equals(state) && !"closed".equals(state)) throw ApiException.badRequest("state must be 'open' or 'closed'");
        MergeRequest mr = get(who, owner, name, number);
        if (mr.state().equals("merged")) throw ApiException.conflict("merge request already merged");
        if (!who.username().equals(mr.author()) && !who.username().equals(owner)) {
            throw ApiException.forbidden("only the author or the repository owner may change the state");
        }
        return store.setState(mr.repoId(), number, state);
    }
}
