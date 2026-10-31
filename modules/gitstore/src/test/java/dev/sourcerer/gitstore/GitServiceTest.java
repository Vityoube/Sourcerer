package dev.sourcerer.gitstore;

import static org.junit.jupiter.api.Assertions.*;

import dev.sourcerer.core.ApiException;
import dev.sourcerer.core.Models.*;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitServiceTest {
    @TempDir Path tmp;
    GitService git;

    @BeforeEach void setUp() { git = new GitService(tmp.resolve("repos")); }
    @AfterEach void tearDown() { git.close(); }

    @Test void createWithReadmeThenBrowse() {
        git.create("alice", "demo", "main", "# Demo\n", "Alice", "a@x.io");
        String sha = git.resolve("alice", "demo", "main");
        assertNotNull(sha);
        List<TreeEntry> tree = git.tree("alice", "demo", sha, "");
        assertEquals(1, tree.size());
        assertEquals("README.md", tree.get(0).name());
        BlobInfo b = git.blob("alice", "demo", sha, "README.md");
        assertEquals("# Demo\n", b.content());
        assertFalse(b.binary());
        RepoStats s = git.stats("alice", "demo", "main");
        assertFalse(s.empty());
        assertEquals("Initial commit", s.head().shortMessage());
        assertEquals(List.of("main"),
                git.branches("alice", "demo", "main").stream().map(BranchInfo::name).toList());
    }

    @Test void emptyRepoHasNoHead() {
        git.create("alice", "empty", "main", null, "Alice", "a@x.io");
        assertNull(git.resolve("alice", "empty", "main"));
        assertTrue(git.stats("alice", "empty", "main").empty());
    }

    @Test void rejectsTraversalAndBadNames() {
        assertThrows(ApiException.class, () -> git.create("..", "x", "main", null, "a", "a@x.io"));
        assertThrows(ApiException.class, () -> GitService.normalizePath("a/../b"));
        assertEquals("a/b", GitService.normalizePath("/a/b/"));
    }

    @Test void missingPathIs404() {
        git.create("alice", "demo", "main", "x", "Alice", "a@x.io");
        String sha = git.resolve("alice", "demo", "main");
        ApiException e = assertThrows(ApiException.class, () -> git.blob("alice", "demo", sha, "nope.txt"));
        assertEquals(404, e.status());
    }

    @Test void mergeCleanAndConflicting() throws Exception {
        git.create("alice", "demo", "main", "base\n", "Alice", "a@x.io");
        commitOnBranch("alice", "demo", "feature", "main", "feature.txt", "hello\n");
        String main = git.resolve("alice", "demo", "main");
        String feature = git.resolve("alice", "demo", "feature");
        assertTrue(git.checkMerge("alice", "demo", main, feature).success());
        assertTrue(git.diff("alice", "demo", main, feature).contains("+hello"));

        MergeOutcome m = git.merge("alice", "demo", "feature", "main", "Merge feature", "Alice", "a@x.io");
        assertTrue(m.success());
        String head = git.resolve("alice", "demo", "main");
        assertEquals(m.commit(), head);
        assertTrue(git.tree("alice", "demo", head, "").stream().anyMatch(t -> t.name().equals("feature.txt")));
        assertEquals(409, assertThrows(ApiException.class,
                () -> git.merge("alice", "demo", "feature", "main", "again", "Alice", "a@x.io")).status());

        commitOnBranch("alice", "demo", "left", "main", "README.md", "left\n");
        commitOnBranch("alice", "demo", "right", "main", "README.md", "right\n");
        MergeOutcome c = git.merge("alice", "demo", "left", "right", "x", "Alice", "a@x.io");
        assertFalse(c.success());
        assertEquals(List.of("README.md"), c.conflicts());
    }

    @Test void rawStreamsBytes() {
        git.create("alice", "demo", "main", "raw!", "Alice", "a@x.io");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        git.raw("alice", "demo", git.resolve("alice", "demo", "main"), "README.md", out);
        assertEquals("raw!", out.toString());
    }

    /** Test helper: adds a commit on a new branch through a throwaway clone. */
    private void commitOnBranch(String owner, String name, String branch, String from, String file,
                                String content) throws Exception {
        Path work = tmp.resolve("work-" + branch);
        PersonIdent who = new PersonIdent("T", "t@x.io");
        try (Git g = Git.cloneRepository().setURI(git.open(owner, name).getDirectory().toURI().toString())
                .setDirectory(work.toFile()).setBranch(from).call()) {
            g.checkout().setCreateBranch(true).setName(branch).call();
            Files.writeString(work.resolve(file), content);
            g.add().addFilepattern(file).call();
            g.commit().setMessage("change " + file).setAuthor(who).setCommitter(who).call();
            g.push().setRemote("origin").add(branch).call();
        }
        git.invalidate(owner, name);
    }
}
