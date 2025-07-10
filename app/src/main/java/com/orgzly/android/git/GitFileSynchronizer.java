package com.orgzly.android.git;

import android.net.Uri;
import android.util.Log;

import com.orgzly.BuildConfig;
import com.orgzly.R;
import com.orgzly.android.App;
import com.orgzly.android.NotesOrgExporter;
import com.orgzly.android.data.DataRepository;
import com.orgzly.android.db.entity.Book;
import com.orgzly.android.util.LogUtils;
import com.orgzly.android.util.MiscUtils;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.RebaseCommand;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

public class GitFileSynchronizer {
    private final static String TAG = "git.GitFileSynchronizer";

    private final Git git;
    private final GitPreferences preferences;

    public GitFileSynchronizer(Git g, GitPreferences prefs) {
        git = g;
        preferences = prefs;
    }

    public void retrieveLatestVersionOfFile(
            String repositoryPath, File destination) throws IOException {
        MiscUtils.copyFile(workTreeFile(repositoryPath), destination);
    }

    public InputStream openRepoFileInputStream(String repositoryPath) throws FileNotFoundException {
        return new FileInputStream(workTreeFile(repositoryPath));
    }

    private AbstractTreeIterator prepareTreeParser(RevCommit commit) throws IOException {
        // from the commit we can build the tree which allows us to construct the TreeParser
        Repository repo = git.getRepository();
        try (RevWalk walk = new RevWalk(repo)) {
            RevTree tree = walk.parseTree(commit.getTree().getId());
            CanonicalTreeParser treeParser = new CanonicalTreeParser();
            try (ObjectReader reader = repo.newObjectReader()) {
                treeParser.reset(reader, tree.getId());
            }
            walk.dispose();
            return treeParser;
        }
    }

    public List<DiffEntry> getCommitDiff(RevCommit oldCommit, RevCommit newCommit) throws GitAPIException, IOException {
        return git.diff()
                .setShowNameAndStatusOnly(true)
                .setOldTree(prepareTreeParser(oldCommit))
                .setNewTree(prepareTreeParser(newCommit))
                .call();
    }

    /**
     * Run "git fetch origin".
     *
     * @throws IOException if we had trouble fetching
     */
    public void fetch(GitTransportSetter transportSetter) throws IOException {
        long checkPoint;
        FetchResult fetchResult;
        try {
            if (BuildConfig.LOG_DEBUG) {
                LogUtils.d(TAG, String.format("Fetching Git repo from %s", preferences.remoteUri()));
            }
            checkPoint = System.currentTimeMillis();
            fetchResult = (FetchResult) transportSetter
                    .setTransport(git.fetch()
                            .setRemoveDeletedRefs(true))
                    .call();
            Log.i(TAG, String.format("Fetch: actual git fetch took %s ms",
                    (System.currentTimeMillis() - checkPoint)));
        } catch (GitAPIException e) {
            Log.e(TAG, e.getMessage(), e);
            throw new IOException(e);
        }
        if (fetchResult.getAdvertisedRefs().isEmpty()) {
            // The remote repo is completely empty. Push our current branch to it.
            tryPush(transportSetter);
            return;
        }
        fetchResult.getTrackingRefUpdates();
    }

    /**
     * Ensure our repo copy is up-to-date. This is necessary when force-loading a book.
     * @return true if merge was successful, false if not
     */
    public boolean pull(GitTransportSetter transportSetter) throws IOException {
        ensureRepoIsClean();
        try {
            fetch(transportSetter);
            RevCommit mergeTarget = getCommit("origin/" + git.getRepository().getBranch());
            return doMerge(mergeTarget);
        } catch (GitAPIException e) {
            Log.e(TAG, e.getMessage(), e);
            throw new IOException(e);
        }
    }

    private boolean doMerge(RevCommit mergeTarget) throws IOException, GitAPIException {
        MergeResult result = git.merge().include(mergeTarget).call();
        if (result.getMergeStatus().equals(MergeResult.MergeStatus.CONFLICTING)) {
            gitResetMerge();
            return false;
        }
        return true;
    }

    /**
     * Push the current HEAD to the corresponding branch on the remote
     * @return True if push succeeded, false otherwise
     */
    public boolean push(GitTransportSetter transportSetter) {
        final var pushCommand = transportSetter.setTransport(git.push());
        try {
            Iterable<PushResult> pushResults = (Iterable<PushResult>) pushCommand.call();
            for (PushResult result : pushResults) {
                for (RemoteRefUpdate remoteRefUpdate : result.getRemoteUpdates()) {
                    if (remoteRefUpdate.getStatus() != RemoteRefUpdate.Status.OK)
                        return false;
                }
            }
            return true;
        } catch (GitAPIException ignored) {
            return false;
        }
    }

    public void forcePushLocalHeadToRemoteConflictBranch(GitTransportSetter transportSetter) throws GitAPIException {
        final var pushCommand = transportSetter.setTransport(git.push()
            .setRefSpecs(new RefSpec(Constants.HEAD + ":" + Constants.R_HEADS + App.getAppContext().getString(R.string.git_conflict_branch_name_on_remote)))
            .setForce(true));
        pushCommand.call();
    }

    public RebaseResult tryRebaseOnFetchHead() throws GitAPIException {
        RebaseCommand command =
                git.rebase().setUpstream(Constants.FETCH_HEAD);
        RebaseResult result = command.call();
        if (!result.getStatus().isSuccessful()) {
            command.setOperation(RebaseCommand.Operation.ABORT).call();
        }
        return result;
    }

    public void tryPush(GitTransportSetter transportSetter) throws RuntimeException {
        long startTime = System.currentTimeMillis();
        final var pushCommand = transportSetter.setTransport(git.push());

        if (BuildConfig.LOG_DEBUG) {
            String currentBranch = "UNKNOWN_BRANCH";
            try {
                currentBranch = git.getRepository().getBranch();
            } catch (IOException ignored) {}
            LogUtils.d(TAG, "Pushing branch " + currentBranch + " to " + preferences.remoteUri());
        }
        try {
            pushCommand.call();
        } catch (GitAPIException e) {
            throw new RuntimeException(e);
        }
        Log.i(TAG, String.format("Push took %s ms", (System.currentTimeMillis() - startTime)));
    }

    private void gitResetMerge() throws IOException, GitAPIException {
        git.getRepository().writeMergeCommitMsg(null);
        git.getRepository().writeMergeHeads(null);
        git.reset().setMode(ResetCommand.ResetType.HARD).call();
    }

    public void updateAndCommitExistingFile(File sourceFile, String repositoryPath) throws IOException {
        ensureRepoIsClean();
        File destinationFile = workTreeFile(repositoryPath);
        if (!destinationFile.exists()) {
            throw new FileNotFoundException("File " + destinationFile + " does not exist");
        }
        updateAndCommitFile(sourceFile, repositoryPath);
    }

    public void writeFileAndAddToIndex(DataRepository dataRepository, Book book,
                                       String repositoryPath) throws IOException {
        // TODO: String resources
        File destinationFile = workTreeFile(repositoryPath);
        if (!(destinationFile.exists() || destinationFile.createNewFile()))
            throw new IOException("Failed to create file " + repositoryPath + " in work tree");
        new NotesOrgExporter(dataRepository).exportBook(book, destinationFile);
        try {
            git.add().addFilepattern(repositoryPath).call();
        } catch (GitAPIException e) {
            throw new IOException("Failed to add file to index.");
        }
    }

    public void commitCurrentIndex() throws GitAPIException {
        if (gitRepoIsClean())
            return;
        git.commit().setMessage("Orgzly repository sync").call(); // TODO: strings.xml
    }

    /**
     * Add a new file to the repository, while ensuring that it didn't already exist.
     * @param sourceFile This will become the contents of the added file
     * @param repositoryPath Path inside the repo where the file should be added
     * @throws IOException If the file already exists
     */
    public void addAndCommitNewFile(File sourceFile, String repositoryPath) throws IOException {
        ensureRepoIsClean();
        File destinationFile = workTreeFile(repositoryPath);
        if (destinationFile.exists()) {
            throw new IOException("Can't add new file " + repositoryPath + " that already exists.");
        }
        ensureDirectoryHierarchy(repositoryPath);
        updateAndCommitFile(sourceFile, repositoryPath);
    }

    private void ensureDirectoryHierarchy(String repositoryPath) throws IOException {
        if (repositoryPath.contains("/")) {
            File targetDir = workTreeFile(repositoryPath).getParentFile();
            if (!(Objects.requireNonNull(targetDir).exists() || targetDir.mkdirs())) {
                throw new IOException("The directory " + targetDir.getAbsolutePath() + " could " +
                        "not be created");
            }
        }
    }

    private void updateAndCommitFile(
            File sourceFile, String repoRelativePath) throws IOException {
        File destinationFile = workTreeFile(repoRelativePath);
        MiscUtils.copyFile(sourceFile, destinationFile);
        try {
            git.add().addFilepattern(repoRelativePath).call();
            if (!gitRepoIsClean())
                commit(String.format("Orgzly update: %s", repoRelativePath));
        } catch (GitAPIException e) {
            throw new IOException("Failed to commit changes.");
        }
    }

    private void commit(String message) throws GitAPIException {
        git.commit().setMessage(message).call();
    }

    public RevCommit currentHead() throws IOException {
        return getCommit(Constants.HEAD);
    }

    public RevCommit currentRemoteHead() throws IOException {
        return getCommit(Constants.R_REMOTES + "origin/" + git.getRepository().getBranch());
    }

    public RevCommit getCommit(String identifier) throws IOException {
        if (isEmptyRepo()) {
            return null;
        }
        Ref target = git.getRepository().findRef(identifier);
        if (target == null) {
            return null;
        }
        return new RevWalk(git.getRepository()).parseCommit(target.getObjectId());
    }

    public RevCommit getLastCommitOfFile(Uri uri) throws GitAPIException {
        String repoRelativePath = uri.toString().replaceFirst("^/", "");
        return git.log().setMaxCount(1).addPath(repoRelativePath).call().iterator().next();
    }

    public String workTreePath() {
        return git.getRepository().getWorkTree().getAbsolutePath();
    }

    private boolean gitRepoIsClean() {
        try {
            Status status = git.status().call();
            return !status.hasUncommittedChanges();
        } catch (GitAPIException e) {
            return false;
        }
    }

    public void ensureRepoIsClean() throws IOException {
        if (!gitRepoIsClean())
            throw new IOException("Refusing to update because there are uncommitted changes.");
    }

    public File workTreeFile(String filePath) {
        return new File(workTreePath(), filePath);
    }

    public boolean isEmptyRepo() throws IOException{
        return git.getRepository().exactRef(Constants.HEAD).getObjectId() == null;
    }

    public boolean deleteFileFromRepo(Uri uri, GitTransportSetter transportSetter) throws IOException {
        if (pull(transportSetter)) {
            String repoRelativePath = uri.toString().replaceFirst("^/", "");
            try {
                git.rm().addFilepattern(repoRelativePath).call();
                if (!gitRepoIsClean())
                    commit(String.format("Orgzly deletion: %s", repoRelativePath));
                return true;
            } catch (GitAPIException e) {
                throw new IOException(String.format("Failed to commit deletion of %s, %s", repoRelativePath, e.getMessage()));
            }
        } else {
            return false;
        }
    }

    public boolean renameFileInRepo(String oldPath, String newPath,
                                    GitTransportSetter transportSetter) throws IOException {
        ensureRepoIsClean();
        if (pull(transportSetter)) {
            File oldFile = workTreeFile(oldPath);
            File newFile = workTreeFile(newPath);
            // Abort if destination file exists
            if (newFile.exists()) {
                throw new IOException("Repository file " + newPath + " already exists.");
            }
            ensureDirectoryHierarchy(newPath);
            // Copy the file contents and add it to the index
            MiscUtils.copyFile(oldFile, newFile);
            try {
                git.add().addFilepattern(newPath).call();
                if (!gitRepoIsClean()) {
                    // Remove the old file from the Git index
                    git.rm().addFilepattern(oldPath).call();
                    commit(String.format("Orgzly: rename %s to %s", oldPath, newPath));
                    return true;
                }
            } catch (GitAPIException e) {
                throw new IOException("Failed to rename file in repo, " + e.getMessage());
            }
        }
        return false;
    }
}
