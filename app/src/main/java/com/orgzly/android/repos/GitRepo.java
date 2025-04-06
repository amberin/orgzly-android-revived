package com.orgzly.android.repos;

import android.content.Context;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.orgzly.R;
import com.orgzly.android.App;
import com.orgzly.android.BookFormat;
import com.orgzly.android.BookName;
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
import com.orgzly.android.sync.BookNamesake;
import com.orgzly.android.sync.BookSyncStatus;
import com.orgzly.android.sync.SyncState;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.ignore.IgnoreNode;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.FileUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class GitRepo implements SyncRepo, IntegrallySyncedRepo {
    private final long repoId;
    private final Uri repoUri;

    /**
     * Used as cause when we try to clone into a non-empty directory
     */
    public static class DirectoryNotEmpty extends Exception {
        public File dir;

        DirectoryNotEmpty(File dir) {
            this.dir = dir;
        }
    }

    public static GitRepo getInstance(RepoWithProps props, Context context) throws IOException {
        // TODO: This doesn't seem to be implemented in the same way as WebdavRepo.kt, do
        //  we want to store configuration data the same way they do?
        Repo repo = props.getRepo();
        Uri repoUri = Uri.parse(repo.getUrl());
        RepoPreferences repoPreferences = new RepoPreferences(context, repo.getId(), repoUri);
        GitPreferencesFromRepoPrefs prefs = new GitPreferencesFromRepoPrefs(repoPreferences);

        // TODO: Build from info

        return build(props.getRepo().getId(), prefs);
    }

    private static GitRepo build(long id, GitPreferences prefs) throws IOException {
        Git git = ensureRepositoryExists(prefs, false, null);
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

    private String currentBranch() throws IOException {
        return git.getRepository().getBranch();
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public @Nullable SyncState syncRepo(@NonNull Context context,
                                        @NonNull DataRepository dataRepository) throws Exception {
        /*

        - Loop over all existing books, adding them to a "status map" with their
          preliminary status (locally modified or NO_CHANGE). If there are changes, git add the file.
        - Make one commit with all changed files.
        - Open SSH session and
          - git push
          - if push failed or if there were no local changes:
            - fetch
            - if HEAD and remote HEAD differ
              - try rebase
              - if rebase succeeds:
                - reload books with remote changes (updating them in the "status map")
                - load any new books, adding them to the "status map"
                  - unlink deleted books (and update status map)
              - else:
                - force-push to conflict branch
                - set conflict status on the relevant books
          - Close SSH session.
        - Loop through all books in repo to ensure we are not missing anything (e.g. first sync).
        - Loop through the status map and update all books' displayed statuses.

        sync statuses handled so far:
        - NO_CHANGE
        - NO_BOOK_ONE_ROOK
        - ONLY_BOOK_WITH_LINK
        - BOOK_WITH_LINK_LOCAL_MODIFIED
        - BOOK_WITH_LINK_ROOK_MODIFIED
        - ROOK_NO_LONGER_EXISTS
        - CONFLICT_PUSHED_TO_CONFLICT_BRANCH
        - ONLY_BOOK_WITHOUT_LINK_AND_ONE_REPO
        - ONLY_BOOK_WITHOUT_LINK_AND_MULTIPLE_REPOS

        test cases:
        - trying to save a book to the repo resulting in a file name collision (respecting ignore
         rules)
        - trying to load a file from the repo resulting in a book name collision
        - "sync modification time" must update properly
        - remotely deleted book must not be re-synced to the repo
        - ability to recover when there are no remote changes ("always" try to rebase or
        verify that we are synced with remote)
        - failed push or fetch must result in nice snackbars

        */

        boolean pushNeeded = false;
        SyncState syncStateToReturn = null;
        Map<String, BookNamesake> nameSakes = new HashMap<>();
        // Ensure all linked local books are synced to repo
        for (BookView bookView : dataRepository.getBooks()) { // N.B. loops over all books...
            BookSyncStatus status = BookSyncStatus.NO_CHANGE; // default status
            Repo linkRepo = bookView.getLinkRepo();
            if (linkRepo == null) {
                BookAction lastAction = bookView.getBook().getLastAction();
                if (lastAction != null && lastAction.getType() == BookAction.Type.ERROR) {
                    // Book is already in an error state - avoid re-linking to the repo.
                    status = BookSyncStatus.BOOK_WITH_PREVIOUS_ERROR_AND_NO_LINK;
                } else if (dataRepository.getRepos().size() > 1) {
                    status = BookSyncStatus.ONLY_BOOK_WITHOUT_LINK_AND_MULTIPLE_REPOS;
                } else {
                    dataRepository.setLink(bookView.getBook().getId(), new Repo(repoId, RepoType.GIT, repoUri.toString()));
                    status = BookSyncStatus.ONLY_BOOK_WITHOUT_LINK_AND_ONE_REPO;
                }
            } else {
                if (linkRepo.getId() != repoId)
                    continue; // Book is linked to another repo; ignore it.
            }
            String bookName = bookView.getBook().getName();
            BookNamesake namesake = new BookNamesake(bookName);
            namesake.setBook(bookView);
            if (bookView.isOutOfSync() || (!bookView.hasSync() && status != BookSyncStatus.BOOK_WITH_PREVIOUS_ERROR_AND_NO_LINK)) {
                if (bookView.isOutOfSync())
                    status = BookSyncStatus.BOOK_WITH_LINK_LOCAL_MODIFIED;
                else if (!bookView.hasSync())
                    status = BookSyncStatus.ONLY_BOOK_WITH_LINK;
                // Just add; we will commit later
                synchronizer.writeFileAndAddToIndex(dataRepository, bookView.getBook(),
                        BookName.repoRelativePath(bookName, BookFormat.ORG));
                pushNeeded = true;
            }
            namesake.setStatus(status);
            nameSakes.put(bookName, namesake);
        }
        try (GitTransportSetter transportSetter = preferences.createTransportSetter()) {
            boolean pushFailed = false;
            if (pushNeeded) {
                synchronizer.commitCurrentIndex();
                pushFailed = !synchronizer.push(transportSetter);
            }
            if (!pushNeeded || pushFailed) {
                synchronizer.fetch(transportSetter);
                if (!synchronizer.currentHead().toString().equals(synchronizer.currentRemoteHead().toString())) {
                    // We are out of sync with the remote. Try to rebase.
                    RevCommit headBeforeRebase = synchronizer.currentHead();
                    RebaseResult rebaseResult = synchronizer.tryRebaseOnFetchHead();
                    if (rebaseResult.getStatus().isSuccessful()) {
                        // Rebasing on the remote changes succeeded.
                        if (pushNeeded) {
                            synchronizer.push(transportSetter);
                        }
                        handleRemoteChanges(nameSakes, headBeforeRebase);
                    } else {
                        // There is a conflict between local and remote.
                        // Push local HEAD to the conflict branch on remote.
                        // Set conflict state on all locally changed books (although we don't
                        // actually know exactly which files have conflicts).
                        for (var nameSake : nameSakes.values()) {
                            if (nameSake.getBook().isModified())
                                nameSake.setStatus(BookSyncStatus.CONFLICT_PUSHED_TO_CONFLICT_BRANCH);
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
        } // SSH session is closed
        // Get all books currently in repo. Used to update "latestLinkedRook" information, and to
        // ensure that any new or missing repo books are loaded (respecting any ignore rules).
        for (var rook : getBooks()) {
            String bookName = BookName.fromRook(rook).getName();
            BookNamesake namesake = nameSakes.get(bookName);
            if (namesake == null) {
                namesake = new BookNamesake(bookName);
                namesake.setStatus(BookSyncStatus.NO_BOOK_ONE_ROOK);
                nameSakes.put(bookName, namesake);
                namesake.setBook(dataRepository.loadBookFromRepo(rook));
            } else {
                // Reload from repo if there were remote changes.
                if (Set.of(BookSyncStatus.BOOK_WITH_LINK_AND_ROOK_MODIFIED,
                        BookSyncStatus.CONFLICT_BOTH_BOOK_AND_ROOK_MODIFIED).contains(Objects.requireNonNull(nameSakes.get(bookName)).getStatus())) {
                    namesake.setBook(dataRepository.loadBookFromRepo(rook));
                }
            }
            namesake.setLatestLinkedRook(rook);
        }
        for (var namesake : nameSakes.values()) {
            Book book = namesake.getBook().getBook();
            if (namesake.getLatestLinkedRook() == null) {
                // Handle remote deletions
                if (namesake.getStatus() == BookSyncStatus.ROOK_NO_LONGER_EXISTS) {
                    dataRepository.setLink(Objects.requireNonNull(book).getId(), null);
                    dataRepository.removeBookSyncedTo(book.getId());
                } else {
                    if (namesake.getStatus() != BookSyncStatus.BOOK_WITH_PREVIOUS_ERROR_AND_NO_LINK)
                        throw new RuntimeException("Something is wrong");
                }
            }
            // Update link and status of all books
            updateDataRepository(dataRepository, book, namesake);
        }
        return syncStateToReturn;
    }

    private void handleRemoteChanges(Map<String, BookNamesake> nameSakes,
                                     RevCommit headBeforeRebase) throws IOException, GitAPIException {
        List<DiffEntry> diffEntries = synchronizer.getCommitDiff(headBeforeRebase,
                synchronizer.currentHead());
        for (DiffEntry changedFile : diffEntries) {
            // Update the status of all changed files - loading happens later.
            String bookName = BookName.fromRepoRelativePath(changedFile.getOldPath()).getName();
            if (!nameSakes.containsKey(bookName))
                continue; // Unknown or ignored file; will get loaded later
            BookNamesake namesake = nameSakes.get(bookName);
            assert namesake != null;
            switch (changedFile.getChangeType()) {
                case MODIFY: {
                    if (namesake.getStatus() == BookSyncStatus.BOOK_WITH_LINK_LOCAL_MODIFIED) {
                        namesake.setStatus(BookSyncStatus.CONFLICT_BOTH_BOOK_AND_ROOK_MODIFIED);
                    } else {
                        namesake.setStatus(BookSyncStatus.BOOK_WITH_LINK_AND_ROOK_MODIFIED);
                    }
                    break;
                }
                case ADD: {
                    // Do nothing; the file will be picked up later when we run getBooks().
                    break;
                }
                case DELETE: {
                    // This status is important to avoid re-syncing remotely deleted files.
                    namesake.setStatus(BookSyncStatus.ROOK_NO_LONGER_EXISTS);
                    break;
                }
                default:  // TODO: Handle RENAME, COPY
                    throw new IOException("Unsupported remote change in Git repo (file renamed or" +
                            " copied)");
            }
        }
    }

    private void updateDataRepository(DataRepository dataRepository,
                                      Book book,
                                      BookNamesake namesake) throws IOException {
        BookAction.Type actionType = BookAction.Type.INFO;
        String actionMessageArgument = "";
        switch (namesake.getStatus()) {
            case ONLY_BOOK_WITHOUT_LINK_AND_ONE_REPO:
            case NO_BOOK_ONE_ROOK:
            case BOOK_WITH_LINK_LOCAL_MODIFIED:
            case BOOK_WITH_LINK_AND_ROOK_MODIFIED:
            case ONLY_BOOK_WITH_LINK:
                actionMessageArgument = String.format("branch \"%s\"", currentBranch());
                dataRepository.updateBookLinkAndSync(book.getId(), namesake.getLatestLinkedRook());
                break;
            case ONLY_BOOK_WITHOUT_LINK_AND_MULTIPLE_REPOS, ROOK_NO_LONGER_EXISTS, BOOK_WITH_PREVIOUS_ERROR_AND_NO_LINK:
                actionType = BookAction.Type.ERROR;
                break;
            case CONFLICT_PUSHED_TO_CONFLICT_BRANCH:
                actionType = BookAction.Type.ERROR;
                dataRepository.updateBookLinkAndSync(book.getId(), namesake.getLatestLinkedRook());
                break;
        }
        BookAction action = BookAction.forNow(actionType, namesake.getStatus().msg(actionMessageArgument));
        dataRepository.setBookLastActionAndSyncStatus(book.getId(), action, namesake.getStatus().toString());
        dataRepository.updateBookIsModified(book.getId(), false);
    }
}
