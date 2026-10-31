package dev.sourcerer.persistence;

import dev.sourcerer.core.ApiException;
import dev.sourcerer.core.Models.*;
import dev.sourcerer.core.Stores.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** JDBC implementations of every store port. */
public final class JdbcStores {
    private JdbcStores() {}

    private static Instant ts(long ms) { return Instant.ofEpochMilli(ms); }

    private static long insert(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, args);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong("id");
            }
        }
    }

    private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
    }

    private static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        }
    }

    private static int nextSeq(Connection c, String column, long repoId) throws SQLException {
        // The UPDATE takes the row lock, serialising number allocation per repository.
        if (update(c, "UPDATE repos SET " + column + " = " + column + " + 1 WHERE id = ?", repoId) == 0) {
            throw ApiException.notFound("repository not found");
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT " + column + " FROM repos WHERE id = ?")) {
            ps.setLong(1, repoId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // ------------------------------------------------------------------ users

    public static final class Users implements UserStore {
        private final Database db;

        public Users(Database db) { this.db = db; }

        private static User map(ResultSet rs) throws SQLException {
            return new User(rs.getLong("id"), rs.getString("username"), rs.getString("email"),
                    ts(rs.getLong("created_at")));
        }

        @Override
        public User create(String username, String email, String passwordHash) {
            long now = System.currentTimeMillis();
            long id = db.tx(c -> insert(c,
                    "INSERT INTO users(username, email, password_hash, created_at) VALUES (?,?,?,?)",
                    username, email, passwordHash, now));
            return new User(id, username, email, ts(now));
        }

        @Override
        public Optional<UserCredentials> findCredentials(String username) {
            return db.read(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT * FROM users WHERE username = ?")) {
                    ps.setString(1, username);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next()
                                ? Optional.of(new UserCredentials(map(rs), rs.getString("password_hash")))
                                : Optional.empty();
                    }
                }
            });
        }

        @Override
        public Optional<User> findById(long id) {
            return db.read(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT * FROM users WHERE id = ?")) {
                    ps.setLong(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                    }
                }
            });
        }
    }

    // ------------------------------------------------------------------ repos

    public static final class Repos implements RepoStore {
        private final Database db;

        public Repos(Database db) { this.db = db; }

        private static Repo map(ResultSet rs) throws SQLException {
            return new Repo(rs.getLong("id"), rs.getString("owner"), rs.getString("name"),
                    rs.getString("description"), rs.getString("default_branch"), rs.getBoolean("is_private"),
                    ts(rs.getLong("created_at")));
        }

        @Override
        public Repo create(String owner, String name, String description, String defaultBranch, boolean isPrivate) {
            long now = System.currentTimeMillis();
            long id = db.tx(c -> insert(c,
                    "INSERT INTO repos(owner, name, description, default_branch, is_private, created_at) "
                            + "VALUES (?,?,?,?,?,?)", owner, name, description, defaultBranch, isPrivate, now));
            return new Repo(id, owner, name, description, defaultBranch, isPrivate, ts(now));
        }

        @Override
        public Optional<Repo> find(String owner, String name) {
            return db.read(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT * FROM repos WHERE owner = ? AND name = ?")) {
                    ps.setString(1, owner);
                    ps.setString(2, name);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                    }
                }
            });
        }

        @Override
        public List<Repo> list(String owner, int limit, int offset) {
            return db.read(c -> {
                String sql = "SELECT * FROM repos " + (owner == null ? "" : "WHERE owner = ? ")
                        + "ORDER BY owner, name LIMIT ? OFFSET ?";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    int i = 1;
                    if (owner != null) ps.setString(i++, owner);
                    ps.setInt(i++, limit);
                    ps.setInt(i, offset);
                    try (ResultSet rs = ps.executeQuery()) {
                        List<Repo> out = new ArrayList<>();
                        while (rs.next()) out.add(map(rs));
                        return out;
                    }
                }
            });
        }

        @Override
        public void delete(long id) {
            db.tx(c -> update(c, "DELETE FROM repos WHERE id = ?", id));
        }
    }

    // ------------------------------------------------------------------ issues

    public static final class Issues implements IssueStore {
        private final Database db;

        public Issues(Database db) { this.db = db; }

        private static Issue map(ResultSet rs) throws SQLException {
            return new Issue(rs.getLong("id"), rs.getLong("repo_id"), rs.getInt("num"), rs.getString("title"),
                    rs.getString("body"), rs.getString("author"), rs.getString("state"),
                    ts(rs.getLong("created_at")), ts(rs.getLong("updated_at")));
        }

        @Override
        public Issue create(long repoId, String title, String body, String author) {
            long now = System.currentTimeMillis();
            return db.tx(c -> {
                int num = nextSeq(c, "issue_seq", repoId);
                long id = insert(c, "INSERT INTO issues(repo_id, num, title, body, author, state, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?)", repoId, num, title, body, author, "open", now, now);
                return new Issue(id, repoId, num, title, body, author, "open", ts(now), ts(now));
            });
        }

        @Override
        public Optional<Issue> find(long repoId, int number) {
            return db.read(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT * FROM issues WHERE repo_id = ? AND num = ?")) {
                    ps.setLong(1, repoId);
                    ps.setInt(2, number);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                    }
                }
            });
        }

        @Override
        public List<Issue> list(long repoId, String state, int limit, int offset) {
            return db.read(c -> {
                String sql = "SELECT * FROM issues WHERE repo_id = ? " + (state == null ? "" : "AND state = ? ")
                        + "ORDER BY num DESC LIMIT ? OFFSET ?";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    int i = 1;
                    ps.setLong(i++, repoId);
                    if (state != null) ps.setString(i++, state);
                    ps.setInt(i++, limit);
                    ps.setInt(i, offset);
                    try (ResultSet rs = ps.executeQuery()) {
                        List<Issue> out = new ArrayList<>();
                        while (rs.next()) out.add(map(rs));
                        return out;
                    }
                }
            });
        }

        @Override
        public Issue setState(long repoId, int number, String state) {
            long now = System.currentTimeMillis();
            db.tx(c -> update(c, "UPDATE issues SET state = ?, updated_at = ? WHERE repo_id = ? AND num = ?",
                    state, now, repoId, number));
            return find(repoId, number).orElseThrow(() -> ApiException.notFound("issue not found"));
        }

        @Override
        public Comment addComment(long issueId, String author, String body) {
            long now = System.currentTimeMillis();
            long id = db.tx(c -> {
                update(c, "UPDATE issues SET updated_at = ? WHERE id = ?", now, issueId);
                return insert(c, "INSERT INTO issue_comments(issue_id, author, body, created_at) VALUES (?,?,?,?)",
                        issueId, author, body, now);
            });
            return new Comment(id, issueId, author, body, ts(now));
        }

        @Override
        public List<Comment> comments(long issueId) {
            return db.read(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT * FROM issue_comments WHERE issue_id = ? ORDER BY id")) {
                    ps.setLong(1, issueId);
                    try (ResultSet rs = ps.executeQuery()) {
                        List<Comment> out = new ArrayList<>();
                        while (rs.next()) {
                            out.add(new Comment(rs.getLong("id"), rs.getLong("issue_id"), rs.getString("author"),
                                    rs.getString("body"), ts(rs.getLong("created_at"))));
                        }
                        return out;
                    }
                }
            });
        }
    }

    // ------------------------------------------------------------------ merge requests

    public static final class MergeRequests implements MergeRequestStore {
        private final Database db;

        public MergeRequests(Database db) { this.db = db; }

        private static MergeRequest map(ResultSet rs) throws SQLException {
            long merged = rs.getLong("merged_at");
            Instant mergedAt = rs.wasNull() ? null : ts(merged);
            return new MergeRequest(rs.getLong("id"), rs.getLong("repo_id"), rs.getInt("num"),
                    rs.getString("title"), rs.getString("description"), rs.getString("author"),
                    rs.getString("source_branch"), rs.getString("target_branch"), rs.getString("state"),
                    rs.getString("merge_commit"), ts(rs.getLong("created_at")), mergedAt);
        }

        @Override
        public MergeRequest create(long repoId, String title, String description, String author,
                                   String sourceBranch, String targetBranch) {
            long now = System.currentTimeMillis();
            return db.tx(c -> {
                int num = nextSeq(c, "mr_seq", repoId);
                long id = insert(c, "INSERT INTO merge_requests(repo_id, num, title, description, author, "
                                + "source_branch, target_branch, state, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                        repoId, num, title, description, author, sourceBranch, targetBranch, "open", now);
                return new MergeRequest(id, repoId, num, title, description, author, sourceBranch,
                        targetBranch, "open", null, ts(now), null);
            });
        }

        @Override
        public Optional<MergeRequest> find(long repoId, int number) {
            return db.read(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT * FROM merge_requests WHERE repo_id = ? AND num = ?")) {
                    ps.setLong(1, repoId);
                    ps.setInt(2, number);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                    }
                }
            });
        }

        @Override
        public List<MergeRequest> list(long repoId, String state, int limit, int offset) {
            return db.read(c -> {
                String sql = "SELECT * FROM merge_requests WHERE repo_id = ? "
                        + (state == null ? "" : "AND state = ? ") + "ORDER BY num DESC LIMIT ? OFFSET ?";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    int i = 1;
                    ps.setLong(i++, repoId);
                    if (state != null) ps.setString(i++, state);
                    ps.setInt(i++, limit);
                    ps.setInt(i, offset);
                    try (ResultSet rs = ps.executeQuery()) {
                        List<MergeRequest> out = new ArrayList<>();
                        while (rs.next()) out.add(map(rs));
                        return out;
                    }
                }
            });
        }

        @Override
        public boolean openExists(long repoId, String sourceBranch, String targetBranch) {
            return db.read(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM merge_requests WHERE repo_id = ? "
                        + "AND state = 'open' AND source_branch = ? AND target_branch = ?")) {
                    ps.setLong(1, repoId);
                    ps.setString(2, sourceBranch);
                    ps.setString(3, targetBranch);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next();
                    }
                }
            });
        }

        @Override
        public MergeRequest setState(long repoId, int number, String state) {
            db.tx(c -> update(c, "UPDATE merge_requests SET state = ? WHERE repo_id = ? AND num = ?",
                    state, repoId, number));
            return find(repoId, number).orElseThrow(() -> ApiException.notFound("merge request not found"));
        }

        @Override
        public MergeRequest markMerged(long repoId, int number, String mergeCommit) {
            long now = System.currentTimeMillis();
            db.tx(c -> update(c, "UPDATE merge_requests SET state = 'merged', merge_commit = ?, merged_at = ? "
                    + "WHERE repo_id = ? AND num = ?", mergeCommit, now, repoId, number));
            return find(repoId, number).orElseThrow(() -> ApiException.notFound("merge request not found"));
        }
    }
}
