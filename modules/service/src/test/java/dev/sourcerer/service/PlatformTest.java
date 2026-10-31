package dev.sourcerer.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.sourcerer.core.ApiException;
import dev.sourcerer.core.Models.*;
import dev.sourcerer.core.Principal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformTest {
    @TempDir Path tmp;
    Platform p;
    Principal alice;
    Principal bob;

    @BeforeEach void setUp() {
        p = new Platform(new Platform.Config(tmp, "jdbc:h2:mem:t" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", null, null, 4, null));
        User a = p.auth.register("Alice", "alice@x.io", "password1");
        User b = p.auth.register("bob", "bob@x.io", "password2");
        alice = new Principal(a.id(), a.username());
        bob = new Principal(b.id(), b.username());
    }

    @AfterEach void tearDown() { p.close(); }

    @Test void authFlow() {
        assertEquals(409, assertThrows(ApiException.class, () -> p.auth.register("alice", "z@x.io", "password1")).status());
        assertEquals(400, assertThrows(ApiException.class, () -> p.auth.register("x", "z@x.io", "password1")).status());
        String token = p.auth.login("alice", "password1");
        assertEquals("alice", p.auth.fromBearer(token).username());
        assertEquals(401, assertThrows(ApiException.class, () -> p.auth.login("alice", "nope")).status());
        assertEquals("alice", p.auth.fromBasic("alice", token).username());
        assertEquals("alice", p.auth.fromBasic("alice", "password1").username());
        assertEquals(401, assertThrows(ApiException.class, () -> p.auth.fromBasic("bob", token)).status());
    }

    @Test void createViewAndBrowse() {
        Repo r = p.repos.create(alice, "Demo", "my demo", false, null, true);
        assertEquals("demo", r.name());
        assertEquals("main", r.defaultBranch());
        assertEquals(409, assertThrows(ApiException.class, () -> p.repos.create(alice, "demo", "", false, null, true)).status());

        String details = new String(p.browse.details(null, "alice", "demo").body());
        assertTrue(details.contains("\"name\":\"demo\"") && details.contains("Initial commit"), details);
        String tree = new String(p.browse.tree(null, "alice", "demo", null, "").body());
        assertTrue(tree.contains("README.md"), tree);
        String blob = new String(p.browse.blob(null, "alice", "demo", "main", "README.md").body());
        assertTrue(blob.contains("# demo"), blob);
        assertEquals(404, assertThrows(ApiException.class, () -> p.browse.tree(null, "alice", "demo", "nope", "")).status());
    }

    @Test void privateReposAreHidden() {
        p.repos.create(alice, "secret", "", true, null, true);
        assertEquals(404, assertThrows(ApiException.class, () -> p.browse.details(null, "alice", "secret")).status());
        assertEquals(404, assertThrows(ApiException.class, () -> p.browse.details(bob, "alice", "secret")).status());
        assertNotNull(p.browse.details(alice, "alice", "secret"));
        assertTrue(p.repos.list(bob, "alice", 10, 0).isEmpty());
        assertEquals(1, p.repos.list(alice, "alice", 10, 0).size());
    }

    @Test void issuesLifecycle() {
        p.repos.create(alice, "demo", "", false, null, true);
        Issue i = p.issues.create(bob, "alice", "demo", "Broken", "it is");
        assertEquals(1, i.number());
        assertEquals(2, p.issues.create(alice, "alice", "demo", "Second", "").number());
        assertEquals(401, assertThrows(ApiException.class, () -> p.issues.create(null, "alice", "demo", "x", "")).status());
        p.issues.comment(alice, "alice", "demo", 1, "looking");
        assertEquals(1, p.issues.comments(null, "alice", "demo", 1).size());
        assertEquals("closed", p.issues.setState(alice, "alice", "demo", 1, "closed").state());
        assertEquals(1, p.issues.list(null, "alice", "demo", "open", 10, 0).size());
        Principal eve = new Principal(99, "eve");
        assertEquals(403, assertThrows(ApiException.class, () -> p.issues.setState(eve, "alice", "demo", 2, "closed")).status());
    }

    @Test void mergeRequestLifecycle() throws Exception {
        p.repos.create(alice, "demo", "", false, null, true);
        Path work = tmp.resolve("work");
        PersonIdent who = new PersonIdent("T", "t@x.io");
        try (Git g = Git.cloneRepository().setURI(p.git.open("alice", "demo").getDirectory().toURI().toString())
                .setDirectory(work.toFile()).call()) {
            g.checkout().setCreateBranch(true).setName("feature").call();
            Files.writeString(work.resolve("a.txt"), "hi\n");
            g.add().addFilepattern("a.txt").call();
            g.commit().setMessage("add a").setAuthor(who).setCommitter(who).call();
            g.push().setRemote("origin").add("feature").call();
        }
        p.repoChanged("alice", "demo");
        MergeRequest mr = p.mergeRequests.create(bob, "alice", "demo", "Add a", "", "feature", "main");
        assertEquals(1, mr.number());
        assertEquals(409, assertThrows(ApiException.class,
                () -> p.mergeRequests.create(bob, "alice", "demo", "dup", "", "feature", "main")).status());
        assertTrue(p.mergeRequests.mergeability(bob, "alice", "demo", 1).success());
        assertTrue(p.mergeRequests.diff(null, "alice", "demo", 1).contains("+hi"));
        assertEquals(403, assertThrows(ApiException.class, () -> p.mergeRequests.merge(bob, "alice", "demo", 1)).status());
        MergeRequest merged = p.mergeRequests.merge(alice, "alice", "demo", 1);
        assertEquals("merged", merged.state());
        assertNotNull(merged.mergeCommit());
        String tree = new String(p.browse.tree(null, "alice", "demo", "main", "").body());
        assertTrue(tree.contains("a.txt"), tree);
        assertEquals(List.of(), p.mergeRequests.list(null, "alice", "demo", "open", 10, 0));
    }
}
