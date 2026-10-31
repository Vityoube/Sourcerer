package dev.sourcerer.core;

import dev.sourcerer.core.Models.*;
import java.util.List;
import java.util.Optional;

/** Persistence ports. Implementations must be thread-safe. */
public final class Stores {
    private Stores() {}

    public interface UserStore {
        User create(String username, String email, String passwordHash);
        Optional<UserCredentials> findCredentials(String username);
        Optional<User> findById(long id);
    }

    public interface RepoStore {
        Repo create(String owner, String name, String description, String defaultBranch, boolean isPrivate);
        Optional<Repo> find(String owner, String name);
        List<Repo> list(String owner, int limit, int offset);
        void delete(long id);
    }

    public interface IssueStore {
        Issue create(long repoId, String title, String body, String author);
        Optional<Issue> find(long repoId, int number);
        List<Issue> list(long repoId, String state, int limit, int offset);
        Issue setState(long repoId, int number, String state);
        Comment addComment(long issueId, String author, String body);
        List<Comment> comments(long issueId);
    }

    public interface MergeRequestStore {
        MergeRequest create(long repoId, String title, String description, String author,
                            String sourceBranch, String targetBranch);
        Optional<MergeRequest> find(long repoId, int number);
        List<MergeRequest> list(long repoId, String state, int limit, int offset);
        boolean openExists(long repoId, String sourceBranch, String targetBranch);
        MergeRequest setState(long repoId, int number, String state);
        MergeRequest markMerged(long repoId, int number, String mergeCommit);
    }
}
