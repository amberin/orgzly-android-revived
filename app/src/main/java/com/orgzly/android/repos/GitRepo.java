package com.orgzly.android.repos;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.orgzly.BuildConfig;
import com.orgzly.R;
import com.orgzly.android.App;
import com.orgzly.android.BookFormat;
import com.orgzly.android.BookName;
import com.orgzly.android.LocalStorage;
import com.orgzly.android.data.DataRepository;
import com.orgzly.android.db.entity.Book;
import com.orgzly.android.db.entity.BookAction;
import com.orgzly.android.db.entity.BookView;
import com.orgzly.android.db.entity.Repo;
import com.orgzly.android.git.GitFileSynchronizer;
import com.orgzly.android.git.GitPreferences;
import com.orgzly.android.git.GitPreferencesFromRepoPrefs;
import com.orgzly.android.git.GitTransportSetter;
import com.orgzly.android.prefs.AppPreferences;
import com.orgzly.android.prefs.RepoPreferences;
import com.orgzly.android.sync.BookSyncStatus;
import com.orgzly.android.sync.SyncState;
import com.orgzly.android.util.LogUtils;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.ignore.IgnoreNode;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.FileUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class GitRepo implements SyncRepo, IntegrallySyncedRepo {
    private final static String TAG = "repos.GitRepo";
    private final long repoId;
    private final Uri repoUri;
    private final RepoType repoType = RepoType.GIT;

    /**
     * Used as cause when we try to clone into a non-empty directory
     */
    public static class DirectoryNotEmpty extends Exception {
        public File dir;

        DirectoryNotEmpty(File dir) {
            this.dir = dir;
        }
    }

    private String mainBranch() {
        return preferences.branchName();
    }

    public static GitRepo getInstance(RepoWithProps props, Context context) throws IOException {
        // TODO: This doesn't seem to be implemented in the same way as WebdavRepo.kt, do
        //  we want to store configuration data the same way they do?
        Repo repo = props.getRepo();
        Uri repoUri = Uri.parse(repo.getUrl());
        RepoPreferences repoPreferences = new RepoPreferences(context, repo.getId(), repoUri);
        GitPreferencesFromRepoPrefs prefs = new GitPreferencesFromRepoPrefs(repoPreferences);

        // TODO: Build from info

        return build(props.getRepo().getId(), prefs, false);
    }

    private static GitRepo build(long id, GitPreferences prefs, boolean clone) throws IOException {
        Git git = ensureRepositoryExists(prefs, clone, null);

        StoredConfig config = git.getRepository().getConfig();
        config.setString("remote", prefs.remoteName(), "url", prefs.remoteUri().toString());
        config.setString("user", null, "name", prefs.getAuthor());
        config.setString("user", null, "email", prefs.getEmail());
        config.setString("gc", null, "auto", "256");
        config.save();

        return new GitRepo(id, git, prefs);
    }

    static boolean isRepo(FileRepositoryBuilder frb, File f) {
        frb.addCeilingDirectory(f).findGitDir(f);
        return frb.getGitDir() != null && frb.getGitDir().exists();
    }

    public static Git ensureRepositoryExists(
            GitPreferences prefs, boolean clone, ProgressMonitor pm) throws IOException {
        return ensureRepositoryExists(
                prefs.remoteUri(), new File(prefs.repositoryFilepath()),
                prefs.createTransportSetter(), clone, pm);
    }

    public static Git ensureRepositoryExists(
            Uri repoUri, File directoryFile, GitTransportSetter transportSetter,
            boolean clone, ProgressMonitor pm)
            throws IOException {
        if (clone) {
            return cloneRepo(repoUri, directoryFile, transportSetter, pm);
        } else {
            return verifyExistingRepo(directoryFile);
        }
    }

    /**
     * Check that the given path contains a valid git repository
     * @param directoryFile the path to check
     * @return A Git repo instance
     * @throws IOException Thrown when either the directory doesnt exist or is not a git repository
     */
    private static Git verifyExistingRepo(File directoryFile) throws IOException {
        if (!directoryFile.exists()) {
            throw new IOException(String.format("The directory %s does not exist", directoryFile.toString()), new FileNotFoundException());
        }

        FileRepositoryBuilder frb = new FileRepositoryBuilder();
        if (!isRepo(frb, directoryFile)) {
            throw new IOException(
                    String.format("Directory %s is not a git repository.",
                            directoryFile.getAbsolutePath()));
        }
        return new Git(frb.build());
    }

    /**
     * Attempts to clone a git repository
     * @param repoUri Remote location of git repository
     * @param directoryFile Location to clone to
     * @param transportSetter Transport information
     * @param pm Progress reporting helper
     * @return A Git repo instance
     * @throws IOException Thrown when directoryFile doesn't exist or isn't empty. Also thrown
     * when the clone fails
     */
    private static Git cloneRepo(Uri repoUri, File directoryFile, GitTransportSetter transportSetter,
                      ProgressMonitor pm) throws IOException {
        if (!directoryFile.exists()) {
            throw new IOException(String.format("The directory %s does not exist", directoryFile.toString()), new FileNotFoundException());
        }

        // Using list() can be resource intensive if there's many files, but since we just call it
        // at the time of cloning once we should be fine for now
        if (directoryFile.list().length != 0) {
            throw new IOException(String.format("The directory must be empty"), new DirectoryNotEmpty(directoryFile));
        }

        try {
            CloneCommand cloneCommand = Git.cloneRepository().
                    setURI(repoUri.toString()).
                    setProgressMonitor(pm).
                    setDirectory(directoryFile);
            transportSetter.setTransport(cloneCommand);
            return cloneCommand.call();
        } catch (GitAPIException | JGitInternalException e) {
            try {
                FileUtils.delete(directoryFile, FileUtils.RECURSIVE);
                // This is done to show sensible error messages when trying to create a new git sync
                directoryFile.mkdirs();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            throw new IOException(e);
        }
    }

    private Git git;
    private GitFileSynchronizer synchronizer;
    private GitPreferences preferences;

    public GitRepo(long id, Git g, GitPreferences prefs) {
        repoId = id;
        git = g;
        preferences = prefs;
        repoUri = getUri();
        synchronizer = new GitFileSynchronizer(git, prefs);
    }

    public boolean isConnectionRequired() {
        return true;
    }

    @Override
    public boolean isAutoSyncSupported() {
        return true;
    }

    public VersionedRook storeBook(File file, String repoRelativePath) throws IOException {
        File destination = synchronizer.workTreeFile(repoRelativePath);

        if (destination.exists()) {
            synchronizer.updateAndCommitExistingFile(file, repoRelativePath);
        } else {
            synchronizer.addAndCommitNewFile(file, repoRelativePath);
        }
        return currentVersionedRook(Uri.EMPTY.buildUpon().appendPath(repoRelativePath).build());
    }

    private RevWalk walk() {
        return new RevWalk(git.getRepository());
    }

    RevCommit getCommitFromRevisionString(String revisionString) throws IOException {
        return walk().parseCommit(ObjectId.fromString(revisionString));
    }

    @Override
    public VersionedRook retrieveBook(String repoRelativePath, File destination) throws IOException {

        Uri sourceUri = Uri.parse("/" + repoRelativePath);

        synchronizer.retrieveLatestVersionOfFile(sourceUri.getPath(), destination);

        return currentVersionedRook(sourceUri);
    }

    @Override
    public InputStream openRepoFileInputStream(String repoRelativePath) throws IOException {
        Uri sourceUri = Uri.parse(repoRelativePath);
        return synchronizer.openRepoFileInputStream(sourceUri.getPath());
    }

    private VersionedRook currentVersionedRook(Uri uri) {
        RevCommit commit = null;
        uri = Uri.parse(Uri.decode(uri.toString()));
        try {
            commit = synchronizer.getLastCommitOfFile(uri);
        } catch (GitAPIException e) {
            e.printStackTrace();
        }
        assert commit != null;
        long mtime = (long)commit.getCommitTime()*1000;
        return new VersionedRook(repoId, RepoType.GIT, getUri(), uri, commit.name(), mtime);
    }

    public List<VersionedRook> getBooks() throws IOException {
        List<VersionedRook> result = new ArrayList<>();
        if (synchronizer.currentHead() == null) {
            return result;
        }

        TreeWalk walk = new TreeWalk(git.getRepository());
        walk.reset();
        walk.setRecursive(true);
        walk.addTree(synchronizer.currentHead().getTree());
        final RepoIgnoreNode ignores = new RepoIgnoreNode(this);
        boolean supportsSubFolders = AppPreferences.subfolderSupport(App.getAppContext());
        walk.setFilter(new TreeFilter() {
            @Override
            public boolean include(TreeWalk walker) {
                final FileMode mode = walk.getFileMode();
                final boolean isDirectory = mode == FileMode.TREE;
                final String repoRelativePath = walk.getPathString();
                if (ignores.isIgnored(repoRelativePath, isDirectory) == IgnoreNode.MatchResult.IGNORED)
                    return false;
                if (isDirectory)
                    return supportsSubFolders;
                return BookName.isSupportedFormatFileName(repoRelativePath);
            }

            @Override
            public boolean shouldBeRecursive() {
                return true;
            }

            @Override
            public TreeFilter clone() {
                return this;
            }
        });
        while (walk.next()) {
            result.add(currentVersionedRook(Uri.withAppendedPath(Uri.EMPTY, walk.getPathString())));
        }
        return result;
    }

    public Uri getUri() {
        return preferences.remoteUri();
    }

    public void delete(Uri uri) throws Exception {
        try (GitTransportSetter transportSetter = preferences.createTransportSetter()) {
            if (synchronizer.deleteFileFromRepo(uri, transportSetter)) {
                synchronizer.tryPush(transportSetter);
            }
        }
    }

    public VersionedRook renameBook(Uri oldFullUri, String newName) throws IOException {
        Context context = App.getAppContext();
        if (newName.contains("/") && !AppPreferences.subfolderSupport(context)) {
            throw new IOException(context.getString(R.string.subfolder_support_disabled));
        }
        String oldPath = oldFullUri.toString().replaceFirst("^/", "");
        String newPath = BookName.repoRelativePath(newName, BookFormat.ORG);
        try (GitTransportSetter transportSetter = preferences.createTransportSetter()) {
            if (synchronizer.renameFileInRepo(oldPath, newPath, transportSetter)) {
                synchronizer.tryPush(transportSetter);
                return currentVersionedRook(Uri.EMPTY.buildUpon().appendPath(newPath).build());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        throw new IOException(String.format("Failed to rename %s to %s", oldPath, newPath));
    }

    @Override
    public TwoWaySyncResult syncBook(
            Uri uri, VersionedRook current, File fromDB) throws IOException {
        String repoRelativePath = uri.getPath().replaceFirst("^/", "");
        boolean merged = true;
        if (current != null) {
            RevCommit rookCommit = getCommitFromRevisionString(current.getRevision());
            if (BuildConfig.LOG_DEBUG) {
                LogUtils.d(TAG, String.format("Syncing file %s, rookCommit: %s", repoRelativePath, rookCommit));
            }
            merged = synchronizer.updateAndCommitFileFromRevisionAndMerge(
                    fromDB, repoRelativePath,
                    synchronizer.getFileRevision(repoRelativePath, rookCommit),
                    rookCommit);

            if (merged) {
                // Our change was successfully merged. Make an attempt
                // to return to the main branch, if we are not on it.
                if (!git.getRepository().getBranch().equals(preferences.branchName())) {
                    synchronizer.attemptReturnToBranch(mainBranch());
                }
            }
        } else {
            Log.w(TAG, "Unable to find previous commit, loading from repository.");
        }
        File writeBackFile = synchronizer.workTreeFile(repoRelativePath);
        return new TwoWaySyncResult(
                currentVersionedRook(Uri.EMPTY.buildUpon().appendPath(repoRelativePath).build()), merged,
                writeBackFile);
    }

    private String currentBranch() throws IOException {
        return git.getRepository().getBranch();
    }

    private File getTempBookFile(Context context) throws IOException {
        return new LocalStorage(context).getTempBookFile();
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public @Nullable SyncState syncRepo(@NonNull Context context,
                                        @NonNull DataRepository dataRepository) throws Exception {
        /*

        v2:
        - Loop over all existing books, adding them to a "status map" with their
          preliminary status (locally modified or NO_CHANGE). If there are changes, git add the file.
        - Make one commit with all changed files.
        - Open SSH session and
          - git push
          - if push failed or if there were no local changes:
            - fetch and try rebase
            - if rebase succeeds:
              - reload books with remote changes (updating them in the "status map")
              - load any new books, adding them to the "status map"
                - unlink deleted books (and update status map)
            - else:
              - force-push to conflict branch
              - set conflict status on the relevant books
        - Close SSH session.
        - Loop through the status map and update all books' displayed statuses.
        
        questions:
        - should we try to pick up books with no link? if there is only one repo
          and sync is requested, they need to be handled somewhere. but perhaps not in
          this "sync repo" method
        - do we need to also handle books with no link? looking at BookSyncStatus, one would
        think so

        sync statuses handled so far:
        - NO_CHANGE
        - NO_BOOK_ONE_ROOK
        - BOOK_WITH_LINK_LOCAL_MODIFIED
        - BOOK_WITH_LINK_ROOK_MODIFIED

        */

        boolean pushNeeded = false;
        SyncState syncStateToReturn = null;
        Map<String, BookSyncStatus> bookSyncStatusMap = new HashMap<>();
        // Ensure all linked local books are synced to repo
        for (BookView bookView : dataRepository.getBooksLinkedToRepo(repoId)) {
            BookSyncStatus status = BookSyncStatus.NO_CHANGE;
            String bookName = bookView.getBook().getName();
            if (!bookView.hasSync() || bookView.isOutOfSync()) {
                if (!bookView.hasSync())
                    status = BookSyncStatus.ONLY_BOOK_WITH_LINK;
                if (bookView.isOutOfSync())
                    status = BookSyncStatus.BOOK_WITH_LINK_LOCAL_MODIFIED;
                // Just add; we will commit later
                synchronizer.writeFileAndAddToIndex(dataRepository, bookView.getBook(),
                        BookName.repoRelativePath(bookName, BookFormat.ORG));
                pushNeeded = true;
            }
            bookSyncStatusMap.put(bookName, status);
        }
        try (GitTransportSetter transportSetter = preferences.createTransportSetter()) {
            boolean pushFailed = false;
            if (pushNeeded) {
                synchronizer.commitCurrentIndex();
                try {
                    synchronizer.push(transportSetter);
                } catch (GitAPIException ignored) {
                    pushFailed = true;
                }
            }
            if (!pushNeeded || pushFailed) {
                if (synchronizer.fetch(transportSetter)) { // Always fetch the default branch only
                    // There are changes on remote. Try to rebase.
                    RevCommit headBeforeRebase = synchronizer.currentHead();
                    RebaseResult rebaseResult = synchronizer.tryRebaseOnRemoteHead();
                    if (rebaseResult.getStatus().isSuccessful()) {
                        // Rebasing on the remote changes succeeded.
                        if (pushNeeded) {
                            synchronizer.push(transportSetter);
                        }
                        handleRemoteChanges(bookSyncStatusMap, headBeforeRebase);
                    } else {
                        // There is a conflict between local and remote.
                        // Push local HEAD to the conflict branch on remote.
                        // Set conflict state on all locally changed books (although we don't
                        // actually know which files have conflicts).
                        for (var entry : bookSyncStatusMap.entrySet()) {
                            if (entry.getValue() == BookSyncStatus.BOOK_WITH_LINK_LOCAL_MODIFIED)
                                entry.setValue(BookSyncStatus.CONFLICT_PUSHED_TO_CONFLICT_BRANCH);
                        }
                        synchronizer.forcePushLocalHeadToRemoteConflictBranch(transportSetter);
                        syncStateToReturn = SyncState.getInstance(
                                SyncState.Type.FINISHED_WITH_CONFLICTS,
                                context.getString(
                                        R.string.merge_conflict_pushed_to,
                                        context.getString(R.string.git_conflict_branch_name_on_remote)));
                    }
                }
            }
        }
        // Ensure new repo books are loaded, after applying any ignore rules.
        for (var rook : getBooks()) {
            String bookName = BookName.fromRook(rook).getName();
            if (!bookSyncStatusMap.containsKey(bookName))
                bookSyncStatusMap.put(bookName, BookSyncStatus.NO_BOOK_ONE_ROOK);
        }
        for (var entry : bookSyncStatusMap.entrySet()) {
            assert entry.getValue() != null; // If there is no status at this point, our logic is
            // broken
            switch (entry.getValue()) {
                case BOOK_WITH_LINK_AND_ROOK_MODIFIED, CONFLICT_BOTH_BOOK_AND_ROOK_MODIFIED,
                     NO_BOOK_ONE_ROOK: {
                    dataRepository.loadBookFromRepo(repoId, RepoType.GIT, repoUri.toString(),
                            BookName.repoRelativePath(entry.getKey(), BookFormat.ORG));
                    break;
                }
                case ROOK_NO_LONGER_EXISTS: {
                    Book book = dataRepository.getBook(entry.getKey());
                    dataRepository.setLink(Objects.requireNonNull(book).getId(), null);
                    dataRepository.removeBookSyncedTo(book.getId());
                }
            }
            updateBookStatusInDataRepository(dataRepository, entry.getKey(), entry.getValue());
        }
        return syncStateToReturn;
    }

    private void handleRemoteChanges(Map<String, BookSyncStatus> bookSyncStatusMap,
                                     RevCommit headBeforeRebase) throws IOException, GitAPIException {
        List<DiffEntry> diffEntries = synchronizer.getCommitDiff(headBeforeRebase,
                synchronizer.currentHead());
        for (DiffEntry changedFile : diffEntries) {
            BookSyncStatus newStatus = null;
            String bookName = null;
            // Loop over changed files and update their status - loading happens later.
            switch (changedFile.getChangeType()) {
                case MODIFY: {
                    bookName =
                            BookName.fromRepoRelativePath(changedFile.getNewPath()).getName();
                    if (bookSyncStatusMap.get(bookName) == BookSyncStatus.BOOK_WITH_LINK_LOCAL_MODIFIED) {
                        newStatus = BookSyncStatus.CONFLICT_BOTH_BOOK_AND_ROOK_MODIFIED;
                    } else {
                        newStatus = BookSyncStatus.BOOK_WITH_LINK_AND_ROOK_MODIFIED;
                    }
                    break;
                }
                case ADD: {
                    // Do nothing; the file will be picked up later when we run getBooks().
                    break;
                }
                case DELETE: {
                    // It's important that we set the right status here, to avoid adding the file
                    // back to the repo.
                    bookName =
                            BookName.fromRepoRelativePath(changedFile.getOldPath()).getName();
                    newStatus = BookSyncStatus.ROOK_NO_LONGER_EXISTS;
                    break;
                }
                default:  // TODO: Handle RENAME, COPY
                    throw new IOException("Unsupported remote change in Git repo (file renamed or copied)");
            }
            if (newStatus != null && bookName != null)
                bookSyncStatusMap.put(bookName, newStatus);
        }
    }

    private void updateBookStatusInDataRepository(DataRepository dataRepository,
                                                  String bookName,
                                                  BookSyncStatus status) throws IOException {
        BookAction.Type actionType = BookAction.Type.INFO;
        String actionMessageArgument = "";
        switch (status) {
            case ONLY_BOOK_WITHOUT_LINK_AND_ONE_REPO:
            case NO_BOOK_ONE_ROOK:
            case BOOK_WITH_LINK_LOCAL_MODIFIED:
            case BOOK_WITH_LINK_AND_ROOK_MODIFIED:
            case ONLY_BOOK_WITH_LINK:
                actionMessageArgument = String.format("branch \"%s\"", currentBranch());
                break;
            case ONLY_BOOK_WITHOUT_LINK_AND_MULTIPLE_REPOS:
                actionType = BookAction.Type.ERROR;
                break;
            case CONFLICT_PUSHED_TO_CONFLICT_BRANCH:
                actionType = BookAction.Type.ERROR;
                actionMessageArgument = String.format("branch \"%s\"", currentBranch());
                break;
        }
        BookAction action = BookAction.forNow(actionType, status.msg(actionMessageArgument));
        Book book = dataRepository.getBook(bookName);
        assert book != null;
        dataRepository.setBookLastActionAndSyncStatus(book.getId(), action, status.toString());
    }
}
