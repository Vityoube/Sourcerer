package dev.sourcerer.core;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.List;

/** Immutable data carriers shared by every module. */
public final class Models {
    private Models() {}

    public record User(long id, String username, String email, Instant createdAt) {}

    public record UserCredentials(User user, String passwordHash) {}

    public record Repo(long id, String owner, String name, String description, String defaultBranch,
                       boolean isPrivate, Instant createdAt) {
        @JsonIgnore
        public String fullName() { return owner + "/" + name; }
    }

    public record Issue(long id, long repoId, int number, String title, String body, String author,
                        String state, Instant createdAt, Instant updatedAt) {}

    public record Comment(long id, long issueId, String author, String body, Instant createdAt) {}

    public record MergeRequest(long id, long repoId, int number, String title, String description,
                               String author, String sourceBranch, String targetBranch, String state,
                               String mergeCommit, Instant createdAt, Instant mergedAt) {}

    // ---- git views ----
    public record CommitInfo(String sha, String shortMessage, String message, String authorName,
                             String authorEmail, Instant time, List<String> parents) {}

    public record BranchInfo(String name, String sha, boolean isDefault) {}

    public record TreeEntry(String name, String type, String mode, String sha, long size) {}

    public record BlobInfo(String sha, long size, boolean binary, boolean truncated, String content) {}

    public record RepoStats(boolean empty, int branches, int tags, CommitInfo head) {}

    public record MergeOutcome(boolean success, String commit, List<String> conflicts) {}
}
