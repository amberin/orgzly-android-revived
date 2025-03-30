package com.orgzly.android.git;

import android.net.Uri;
import android.util.Log;

import com.orgzly.BuildConfig;
import com.orgzly.R;
import com.orgzly.android.App;
import com.orgzly.android.util.LogUtils;
import com.orgzly.android.util.MiscUtils;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.RebaseCommand;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

public class GitFileSynchronizer {
    private final static String TAG = "git.GitFileSynchronizer";
    public final static String PRE_SYNC_MARKER_BRANCH = "orgzly-pre-sync-marker";

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
     * @return true if the current branch OR the main branch has changed on the remote side
     * @throws IOException if we had trouble fetching
     */
    public boolean fetch(GitTransportSetter transportSetter) throws IOException {
        long checkPoint;
        FetchResult fetchResult;
        try {
            if (BuildConfig.LOG_DEBUG) {
                LogUtils.d(TAG, String.format("Fetching Git repo from %s", preferences.remoteUri()));
            }
            checkPoint = System.currentTimeMillis();
            fetchResult = (FetchResult) transportSetter
                    .setTransport(git.fetch()
                            .setRemote(preferences.remoteName())
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
            return false;
        }
        return !fetchResult.getTrackingRefUpdates().isEmpty();
    }

    /**
     * Ensure our repo copy is up-to-date. This is necessary when force-loading a book.
     * @return true if merge was successful, false if not
     */
    public boolean pull(GitTransportSetter transportSetter) throws IOException {
        ensureRepoIsClean();
        try {
            fetch(transportSetter);
            RevCommit mergeTarget = getCommit(
                    String.format("%s/%s", preferences.remoteName(),
                            git.getRepository().getBranch()));
            return doMerge(mergeTarget);
        } catch (GitAPIException e) {
            Log.e(TAG, e.getMessage(), e);
            throw new IOException(e);
        }
    }

    private String createSyncBranchName() {
        String now = new SimpleDateFormat("yyyy-MM-dd_HHmmss").format(Calendar.getInstance(TimeZone.getTimeZone("UTC")).getTime());
        return "orgzly-sync-" + now;
    }

    public boolean updateAndCommitFileFromRevisionAndMerge(
            File sourceFile, String repoRelativePath,
            ObjectId fileRevision, RevCommit revision)
            throws IOException {
        ensureRepoIsClean();
        if (updateAndCommitFileFromRevision(sourceFile, repoRelativePath, fileRevision)) {
            if (BuildConfig.LOG_DEBUG) {
                LogUtils.d(TAG, String.format("File '%s' committed without conflicts.", repoRelativePath));
            }
            return true;
        }

        String originalBranch = git.getRepository().getFullBranch();
        if (BuildConfig.LOG_DEBUG) {
            LogUtils.d(TAG, String.format("originalBranch is set to %s", originalBranch));
        }
        String mergeBranch = createSyncBranchName();
        if (BuildConfig.LOG_DEBUG) {
            LogUtils.d(TAG, String.format("originalBranch is set to %s", originalBranch));
            LogUtils.d(TAG, String.format("Temporary mergeBranch is set to %s", mergeBranch));
        }
        try {
            git.branchDelete().setBranchNames(mergeBranch).call();
        } catch (GitAPIException ignored) {}
        boolean mergeSucceeded = false;
        try {
            RevCommit mergeTarget = currentHead();
            // Try to use our "pre sync marker" to find a good point in history for branching off.
            RevCommit branchStartPoint = getCommit(PRE_SYNC_MARKER_BRANCH);
            if (branchStartPoint == null) {
                branchStartPoint = revision;
            }
            if (BuildConfig.LOG_DEBUG) {
                LogUtils.d(TAG, String.format("branchStartPoint is set to %s", branchStartPoint));
            }
            git.checkout().setCreateBranch(true).setForceRefUpdate(true).
                    setStartPoint(branchStartPoint).setName(mergeBranch).call();
            if (!currentHead().equals(branchStartPoint))
                throw new IOException("Failed to create new branch at " + branchStartPoint.toString());
            if (!updateAndCommitFileFromRevision(sourceFile, repoRelativePath, fileRevision))
                throw new IOException(
                        String.format(
                                "The provided file revision %s for %s is " +
                                        "not the same as the one found in the provided commit %s.",
                                fileRevision.toString(), repoRelativePath, revision.toString()));
            mergeSucceeded = doMerge(mergeTarget);
            if (mergeSucceeded) {
                RevCommit merged = currentHead();
                git.checkout().setName(originalBranch).call();
                MergeResult result = git.merge().include(merged).call();
                if (!result.getMergeStatus().isSuccessful()) {
                    throw new IOException(String.format("Unexpected failure to merge '%s' into '%s'", merged.toString(), originalBranch));
                }
            }
        } catch (GitAPIException e) {
            e.printStackTrace();
            throw new IOException("Failed to handle merge conflict: " + e.getMessage());
        } finally {
            if (mergeSucceeded) {
                try {
                    if (BuildConfig.LOG_DEBUG) {
                        LogUtils.d(TAG, String.format("Checking out originalBranch '%s'", originalBranch));
                    }
                    git.checkout().setName(originalBranch).call();
                } catch (GitAPIException e) {
                    Log.w(TAG, String.format("Failed to checkout original branch '%s': %s", originalBranch, e.getMessage()));
                }
                try {
                    if (BuildConfig.LOG_DEBUG) {
                        LogUtils.d(TAG, String.format("Deleting temporary mergeBranch '%s'", mergeBranch));
                    }
                    git.branchDelete().setBranchNames(mergeBranch).call();
                } catch (GitAPIException e) {
                    Log.w(TAG, String.format("Failed to delete temporary mergeBranch '%s': %s", mergeBranch, e.getMessage()));
                }
            }
        }
        return mergeSucceeded;
    }

    public void switchToTempSyncBranch() throws IOException, GitAPIException {
        ensureRepoIsClean();
        String tempBranch = createSyncBranchName();
        git.checkout().setCreateBranch(true).setForceRefUpdate(true).setName(tempBranch).call();
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
     * Try to push to remote if local and remote HEADs for the current branch
     * point to different commits. This method was added to allow pushing only
     * once per sync occasion: right after the "for namesake in namesakes"-loop
     * in SyncService.doInBackground().
     */
    public void tryPushIfHeadDiffersFromRemote(GitTransportSetter transportSetter) {
        RevCommit localHead;
        RevCommit remoteHead;

        // Try to get the commit of the remote head with the same name as our local current head
        try {
            localHead = currentHead();
            remoteHead = getCommit("origin/" + git.getRepository().getBranch());
        } catch (IOException e) {
            Log.e(TAG, e.getMessage(), e);
            throw new RuntimeException(e);
        }

        if (!localHead.equals(remoteHead)) {
            tryPush(transportSetter);
        }
    }

    public void push(GitTransportSetter transportSetter) throws GitAPIException {
        final var pushCommand = transportSetter.setTransport(
                git.push().setRemote(preferences.remoteName()));
        pushCommand.call();
    }

    public void forcePushLocalHeadToRemoteConflictBranch(GitTransportSetter transportSetter) throws GitAPIException {
        final var pushCommand = transportSetter.setTransport(git.push()
            .setRefSpecs(new RefSpec("HEAD:" + App.getAppContext().getString(R.string.orgzly_conflict_branch)))
            .setForce(true));
        pushCommand.call();
    }

    public RebaseResult tryRebaseOnRemoteHead() throws IOException, GitAPIException {
        RebaseCommand command =
                git.rebase().setUpstream("origin/" + git.getRepository().getBranch());
        RebaseResult result = command.call();
        if (!result.getStatus().isSuccessful()) {
            command.setOperation(RebaseCommand.Operation.ABORT).call();
        }
        return result;
    }

    public void tryPush(GitTransportSetter transportSetter) throws RuntimeException {
        long startTime = System.currentTimeMillis();
        final var pushCommand = transportSetter.setTransport(
                git.push().setRemote(preferences.remoteName()));
        final Object monitor = new Object();

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

    public boolean updateAndCommitFileFromRevision(
            File sourceFile, String repoRelativePath, ObjectId revision) throws IOException {
        ensureRepoIsClean();
        ObjectId repositoryRevision = getFileRevision(repoRelativePath, currentHead());
        if (repositoryRevision.equals(revision)) {
            updateAndCommitFile(sourceFile, repoRelativePath);
            return true;
        }
        return false;
    }

    public boolean attemptReturnToBranch(String targetBranch) throws IOException {
        ensureRepoIsClean();
        String originalBranch = git.getRepository().getBranch();
        RevCommit mergeTarget = getCommit(
                String.format("%s/%s", preferences.remoteName(), targetBranch));
        boolean mergeSucceeded = false;
        try {
            if (doMerge(mergeTarget)) {
                mergeSucceeded = true;
                RevCommit merged = currentHead();
                git.checkout().setName(targetBranch).call();
                if (doMerge(merged)) {
                    git.branchDelete().setBranchNames(originalBranch);
                } else {
                    git.checkout().setName(originalBranch).call();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, e.getMessage(), e);
            throw new RuntimeException(e);
        }
        return mergeSucceeded;
    }

    public void updateAndCommitExistingFile(File sourceFile, String repositoryPath) throws IOException {
        ensureRepoIsClean();
        File destinationFile = workTreeFile(repositoryPath);
        if (!destinationFile.exists()) {
            throw new FileNotFoundException("File " + destinationFile + " does not exist");
        }
        updateAndCommitFile(sourceFile, repositoryPath);
    }

    public void updateFileAndAddToIndex(File sourceFile, String repositoryPath) {
        File destinationFile = repoDirectoryFile(repositoryPath);
        if (!destinationFile.exists()) {
            throw new FileNotFoundException("File " + destinationFile + " does not exist");
        }
        MiscUtils.copyFile(sourceFile, destinationFile);
        try {
            git.add().addFilepattern(repositoryPath).call();
        } catch (GitAPIException e) {
            throw new IOException("Failed to commit changes."); // TODO: String resource
        }
    }

    public void commitCurrentIndex() throws GitAPIException {
        if (gitRepoIsClean())
            return;
        git.commit().call();
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
            if (!(targetDir.exists() || targetDir.mkdirs())) {
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

    public ObjectId getFileRevision(String pathString, RevCommit commit) throws IOException {
        return TreeWalk.forPath(
                git.getRepository(), pathString, commit.getTree()).getObjectId(0);
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
