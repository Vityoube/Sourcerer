package dev.sourcerer.service;

import dev.sourcerer.gitstore.GitService;
import dev.sourcerer.persistence.Database;
import dev.sourcerer.persistence.JdbcStores;
import dev.sourcerer.security.TokenService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

/** Composition root of the domain layer: no framework, so it is trivially testable and reusable. */
public final class Platform implements AutoCloseable {
    public record Config(Path dataDir, String jdbcUrl, String dbUser, String dbPassword, int dbPoolSize,
                         byte[] tokenSecret) {}

    public final Database database;
    public final GitService git;
    public final AuthService auth;
    public final RepoService repos;
    public final BrowseService browse;
    public final IssueService issues;
    public final MergeRequestService mergeRequests;

    public Platform(Config cfg) {
        Path data = cfg.dataDir().toAbsolutePath();
        String url = cfg.jdbcUrl() != null && !cfg.jdbcUrl().isBlank() ? cfg.jdbcUrl()
                : "jdbc:h2:file:" + data.resolve("db").resolve("sourcerer").toString().replace('\\', '/')
                + ";DB_CLOSE_ON_EXIT=FALSE";
        this.database = new Database(url, cfg.dbUser(), cfg.dbPassword(), cfg.dbPoolSize() > 0 ? cfg.dbPoolSize() : 32);
        this.git = new GitService(data.resolve("repos"));
        byte[] secret = cfg.tokenSecret() != null && cfg.tokenSecret().length >= 32 ? cfg.tokenSecret() : loadOrCreateSecret(data);
        this.auth = new AuthService(new JdbcStores.Users(database), new TokenService(secret));
        this.repos = new RepoService(new JdbcStores.Repos(database), git);
        this.browse = new BrowseService(repos, git);
        this.issues = new IssueService(new JdbcStores.Issues(database), repos);
        this.mergeRequests = new MergeRequestService(new JdbcStores.MergeRequests(database), repos, git);
    }

    /** Called after a push / merge changed refs, from any component. */
    public void repoChanged(String owner, String name) {
        repos.invalidate(owner, name);
        browse.invalidate(owner, name);
    }

    private static byte[] loadOrCreateSecret(Path data) {
        Path f = data.resolve("token.secret");
        try {
            Files.createDirectories(data);
            if (Files.exists(f)) return Files.readAllBytes(f);
            byte[] s = new byte[48];
            new SecureRandom().nextBytes(s);
            Files.write(f, s);
            return s;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        git.close();
        database.close();
    }
}
