package dev.sourcerer.gitstore;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.sourcerer.core.ApiException;
import dev.sourcerer.core.Models.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.merge.ThreeWayMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.FileUtils;

/**
 * Bare-repository access on top of JGit. Everything here is read-optimised:
 * open {@link Repository} handles are cached, ref lookups are cached for a few seconds
 * (and invalidated explicitly on every write we perform), and object reads are in-process
 * (no fork/exec of the git binary).
 */
public final class GitService implements AutoCloseable {
    private static final Pattern SEGMENT = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");
    private static final Pattern SHA = Pattern.compile("^[0-9a-f]{40}$");
    public static final int MAX_INLINE_BLOB = 1 << 20;
    public static final int MAX_RAW_BLOB = 32 << 20;
    private static final int MAX_DIFF = 1 << 20;

    private final Path root;
    private final Cache<String, Repository> open = Caffeine.newBuilder()
            .maximumSize(5_000)
            .removalListener((String k, Repository v, com.github.benmanes.caffeine.cache.RemovalCause c) -> {
                if (v != null) v.close();
            })
            .build();
    /** key: repoKey|ref  -> commit sha, or "" when the ref does not exist. */
    private final Cache<String, String> refs = Caffeine.newBuilder()
            .maximumSize(100_000).expireAfterWrite(Duration.ofSeconds(10)).build();

    public GitService(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    public static boolean validSegment(String s) {
        return s != null && SEGMENT.matcher(s).matches() && !s.endsWith(".git") && !s.contains("..");
    }

    private static String key(String owner, String name) { return owner + "/" + name; }

    private Path dir(String owner, String name) {
        if (!validSegment(owner) || !validSegment(name)) throw ApiException.badRequest("invalid repository path");
        return root.resolve(owner).resolve(name + ".git");
    }

    public boolean exists(String owner, String name) { return Files.isDirectory(dir(owner, name)); }

    public void create(String owner, String name, String defaultBranch, String readme,
                       String authorName, String authorEmail) {
        if (!Repository.isValidRefName(Constants.R_HEADS + defaultBranch)) {
            throw ApiException.badRequest("invalid branch name");
        }
        Path d = dir(owner, name);
        if (Files.exists(d)) throw ApiException.conflict("repository already exists on disk");
        try (Git g = Git.init().setBare(true).setDirectory(d.toFile()).setInitialBranch(defaultBranch).call()) {
            if (readme != null) initialCommit(g.getRepository(), defaultBranch, readme, authorName, authorEmail);
        } catch (Exception e) {
            throw new IllegalStateException("failed to create repository", e);
        }
    }

    private static void initialCommit(Repository r, String branch, String readme, String an, String ae)
            throws IOException {
        try (ObjectInserter ins = r.newObjectInserter()) {
            ObjectId blob = ins.insert(Constants.OBJ_BLOB, readme.getBytes(StandardCharsets.UTF_8));
            TreeFormatter tf = new TreeFormatter();
            tf.append("README.md", FileMode.REGULAR_FILE, blob);
            ObjectId tree = ins.insert(tf);
            PersonIdent who = new PersonIdent(an, ae);
            CommitBuilder cb = new CommitBuilder();
            cb.setTreeId(tree);
            cb.setAuthor(who);
            cb.setCommitter(who);
            cb.setMessage("Initial commit\n");
            ObjectId commit = ins.insert(cb);
            ins.flush();
            RefUpdate ru = r.updateRef(Constants.R_HEADS + branch);
            ru.setNewObjectId(commit);
            ru.update();
        }
    }

    public void delete(String owner, String name) {
        invalidate(owner, name);
        open.invalidate(key(owner, name));
        try {
            Path d = dir(owner, name);
            if (Files.exists(d)) FileUtils.delete(d.toFile(), FileUtils.RECURSIVE | FileUtils.RETRY);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Opens (or reuses) the repository. Callers that hand the instance to code that calls
     * {@code close()} (e.g. JGit's GitServlet) must call {@code incrementOpen()} first.
     */
    public Repository open(String owner, String name) {
        return open.get(key(owner, name), k -> {
            Path d = dir(owner, name);
            if (!Files.isDirectory(d)) throw ApiException.notFound("repository not found on disk");
            try {
                return new org.eclipse.jgit.internal.storage.file.FileRepository(d.toFile());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** Must be called after any ref change (push, merge, ...). */
    public void invalidate(String owner, String name) {
        String prefix = key(owner, name) + "|";
        refs.asMap().keySet().removeIf(k -> k.startsWith(prefix));
    }

    @Override
    public void close() {
        open.invalidateAll();
        open.cleanUp();
    }

    // ------------------------------------------------------------------ refs

    /** Resolves a branch / tag / sha to a commit sha; {@code null} when it does not exist. */
    public String resolve(String owner, String name, String ref) {
        if (ref == null || ref.isEmpty() || ref.length() > 255) return null;
        if (SHA.matcher(ref).matches()) return ref; // immutable, validated when the commit is read
        String v = refs.get(key(owner, name) + "|" + ref, k -> {
            try {
                ObjectId id = open(owner, name).resolve(ref + "^{commit}");
                return id == null ? "" : id.name();
            } catch (IOException | RuntimeException e) {
                return "";
            }
        });
        return v.isEmpty() ? null : v;
    }

    public List<BranchInfo> branches(String owner, String name, String defaultBranch) {
        try {
            List<BranchInfo> out = new ArrayList<>();
            for (Ref r : open(owner, name).getRefDatabase().getRefsByPrefix(Constants.R_HEADS)) {
                String n = r.getName().substring(Constants.R_HEADS.length());
                out.add(new BranchInfo(n, r.getObjectId().name(), n.equals(defaultBranch)));
            }
            out.sort(Comparator.comparing((BranchInfo b) -> !b.isDefault()).thenComparing(BranchInfo::name));
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public RepoStats stats(String owner, String name, String defaultBranch) {
        try {
            Repository r = open(owner, name);
            int branches = r.getRefDatabase().getRefsByPrefix(Constants.R_HEADS).size();
            int tags = r.getRefDatabase().getRefsByPrefix(Constants.R_TAGS).size();
            String head = resolve(owner, name, defaultBranch);
            CommitInfo ci = null;
            if (head != null) {
                try (RevWalk rw = new RevWalk(r)) {
                    ci = commitInfo(rw.parseCommit(ObjectId.fromString(head)));
                }
            }
            return new RepoStats(branches == 0, branches, tags, ci);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CommitInfo commitInfo(RevCommit c) {
        PersonIdent a = c.getAuthorIdent();
        List<String> parents = new ArrayList<>(c.getParentCount());
        for (RevCommit p : c.getParents()) parents.add(p.name());
        return new CommitInfo(c.name(), c.getShortMessage(), c.getFullMessage(), a.getName(),
                a.getEmailAddress(), a.getWhenAsInstant(), parents);
    }

    public List<CommitInfo> log(String owner, String name, String sha, int skip, int limit) {
        try (RevWalk rw = new RevWalk(open(owner, name))) {
            rw.markStart(parse(rw, sha));
            List<CommitInfo> out = new ArrayList<>();
            int seen = 0;
            for (RevCommit c : rw) {
                if (seen++ < skip) continue;
                out.add(commitInfo(c));
                if (out.size() >= limit) break;
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static RevCommit parse(RevWalk rw, String sha) throws IOException {
        try {
            return rw.parseCommit(ObjectId.fromString(sha));
        } catch (org.eclipse.jgit.errors.MissingObjectException
                 | org.eclipse.jgit.errors.IncorrectObjectTypeException e) {
            throw ApiException.notFound("commit not found");
        }
    }

    // ------------------------------------------------------------------ files

    public static String normalizePath(String path) {
        if (path == null) return "";
        String p = path.strip();
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.isEmpty()) return "";
        if (p.indexOf('\\') >= 0 || p.indexOf('\0') >= 0) throw ApiException.badRequest("invalid path");
        for (String s : p.split("/", -1)) {
            if (s.isEmpty() || s.equals(".") || s.equals("..")) throw ApiException.badRequest("invalid path");
        }
        return p;
    }

    public List<TreeEntry> tree(String owner, String name, String commitSha, String path) {
        try (ObjectReader reader = open(owner, name).newObjectReader(); RevWalk rw = new RevWalk(reader)) {
            RevCommit c = parse(rw, commitSha);
            ObjectId treeId = c.getTree();
            if (!path.isEmpty()) {
                TreeWalk w = TreeWalk.forPath(reader, path, c.getTree());
                if (w == null) throw ApiException.notFound("path not found");
                if (!w.isSubtree()) throw ApiException.badRequest("path is not a directory");
                treeId = w.getObjectId(0);
            }
            List<TreeEntry> out = new ArrayList<>();
            try (TreeWalk tw = new TreeWalk(reader)) {
                tw.addTree(treeId);
                tw.setRecursive(false);
                while (tw.next()) {
                    FileMode m = tw.getFileMode(0);
                    String type = m == FileMode.TREE ? "tree" : m == FileMode.GITLINK ? "commit" : "blob";
                    long size = type.equals("blob") ? reader.getObjectSize(tw.getObjectId(0), Constants.OBJ_BLOB) : -1;
                    out.add(new TreeEntry(tw.getNameString(), type, Integer.toOctalString(m.getBits()),
                            tw.getObjectId(0).name(), size));
                }
            }
            out.sort(Comparator.comparing((TreeEntry e) -> !e.type().equals("tree")).thenComparing(TreeEntry::name));
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ObjectId blobId(ObjectReader reader, RevCommit c, String path) throws IOException {
        if (path.isEmpty()) throw ApiException.badRequest("path required");
        TreeWalk w = TreeWalk.forPath(reader, path, c.getTree());
        if (w == null) throw ApiException.notFound("path not found");
        if (w.isSubtree()) throw ApiException.badRequest("path is a directory");
        return w.getObjectId(0);
    }

    public BlobInfo blob(String owner, String name, String commitSha, String path) {
        try (ObjectReader reader = open(owner, name).newObjectReader(); RevWalk rw = new RevWalk(reader)) {
            ObjectId id = blobId(reader, parse(rw, commitSha), path);
            ObjectLoader l = reader.open(id, Constants.OBJ_BLOB);
            if (l.getSize() > MAX_INLINE_BLOB) return new BlobInfo(id.name(), l.getSize(), false, true, null);
            byte[] b = l.getCachedBytes(MAX_INLINE_BLOB);
            if (RawText.isBinary(b)) return new BlobInfo(id.name(), b.length, true, false, null);
            return new BlobInfo(id.name(), b.length, false, false, new String(b, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Streams the raw file; returns its size. */
    public long raw(String owner, String name, String commitSha, String path, OutputStream out) {
        try (ObjectReader reader = open(owner, name).newObjectReader(); RevWalk rw = new RevWalk(reader)) {
            ObjectLoader l = reader.open(blobId(reader, parse(rw, commitSha), path), Constants.OBJ_BLOB);
            if (l.getSize() > MAX_RAW_BLOB) throw new ApiException(413, "file too large");
            l.copyTo(out);
            return l.getSize();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ merge requests

    /** Dry-run merge (in memory, nothing is written to the repository). */
    public MergeOutcome checkMerge(String owner, String name, String targetSha, String sourceSha) {
        Repository r = open(owner, name);
        try {
            ThreeWayMerger m = MergeStrategy.RECURSIVE.newMerger(r, true);
            boolean ok = m.merge(ObjectId.fromString(targetSha), ObjectId.fromString(sourceSha));
            List<String> conflicts = new ArrayList<>();
            if (!ok && m instanceof ResolveMerger rm) conflicts.addAll(rm.getUnmergedPaths());
            return new MergeOutcome(ok, null, conflicts);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Creates a merge commit of {@code source} into {@code targetBranch} and moves the branch. */
    public MergeOutcome merge(String owner, String name, String sourceBranch, String targetBranch,
                              String message, String authorName, String authorEmail) {
        Repository r = open(owner, name);
        try (ObjectInserter ins = r.newObjectInserter(); RevWalk rw = new RevWalk(r)) {
            ObjectId target = r.resolve(Constants.R_HEADS + targetBranch);
            ObjectId source = r.resolve(Constants.R_HEADS + sourceBranch);
            if (target == null || source == null) throw ApiException.notFound("branch not found");
            if (rw.isMergedInto(rw.parseCommit(source), rw.parseCommit(target))) {
                throw ApiException.conflict("nothing to merge: source is already contained in target");
            }
            ThreeWayMerger m = MergeStrategy.RECURSIVE.newMerger(r, true);
            if (!m.merge(target, source)) {
                List<String> conflicts = m instanceof ResolveMerger rm
                        ? new ArrayList<>(rm.getUnmergedPaths()) : List.of();
                return new MergeOutcome(false, null, conflicts);
            }
            PersonIdent who = new PersonIdent(authorName, authorEmail);
            CommitBuilder cb = new CommitBuilder();
            cb.setTreeId(m.getResultTreeId());
            cb.setParentIds(target, source);
            cb.setAuthor(who);
            cb.setCommitter(who);
            cb.setMessage(message);
            ObjectId commit = ins.insert(cb);
            ins.flush();
            RefUpdate ru = r.updateRef(Constants.R_HEADS + targetBranch);
            ru.setExpectedOldObjectId(target);
            ru.setNewObjectId(commit);
            ru.setRefLogMessage("merge " + sourceBranch, false);
            RefUpdate.Result res = ru.update(rw);
            if (res != RefUpdate.Result.FAST_FORWARD && res != RefUpdate.Result.FORCED
                    && res != RefUpdate.Result.NEW) {
                throw ApiException.conflict("target branch moved concurrently (" + res + "), retry");
            }
            invalidate(owner, name);
            return new MergeOutcome(true, commit.name(), List.of());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Unified diff of {@code head} against its merge-base with {@code base} (what a merge request introduces). */
    public String diff(String owner, String name, String baseSha, String headSha) {
        Repository r = open(owner, name);
        try (RevWalk rw = new RevWalk(r); ObjectReader reader = r.newObjectReader()) {
            RevCommit base = rw.parseCommit(ObjectId.fromString(baseSha));
            RevCommit head = rw.parseCommit(ObjectId.fromString(headSha));
            rw.setRevFilter(RevFilter.MERGE_BASE);
            rw.markStart(base);
            rw.markStart(head);
            RevCommit mb = rw.next();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (DiffFormatter df = new DiffFormatter(out)) {
                df.setRepository(r);
                df.setDetectRenames(true);
                var newTree = new CanonicalTreeParser();
                newTree.reset(reader, head.getTree());
                if (mb == null) {
                    df.format(new EmptyTreeIterator(), newTree);
                } else {
                    var oldTree = new CanonicalTreeParser();
                    try (RevWalk rw2 = new RevWalk(r)) {
                        oldTree.reset(reader, rw2.parseCommit(mb).getTree());
                    }
                    df.format(oldTree, newTree);
                }
            }
            byte[] b = out.toByteArray();
            return b.length > MAX_DIFF
                    ? new String(b, 0, MAX_DIFF, StandardCharsets.UTF_8) + "\n... diff truncated ...\n"
                    : new String(b, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
