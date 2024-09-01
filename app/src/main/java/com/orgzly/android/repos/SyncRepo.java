package com.orgzly.android.repos;

import android.net.Uri;

import androidx.annotation.Nullable;

import com.orgzly.android.data.DataRepository;
import com.orgzly.android.sync.SyncState;

import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Remote source of books (such as Dropbox directory, SSH directory, etc.)
 */
public interface SyncRepo {
    boolean isConnectionRequired();

    boolean isAutoSyncSupported();

    boolean isIntegrallySynced();

    /**
     * Unique URL.
     */
    Uri getUri();

    /**
     * Retrieve the list of all available books.
     *
     * @return array of all available books
     * @throws IOException
     */
    List<VersionedRook> getBooks() throws IOException;

    /**
     * Download the latest available revision of the book and store its content to {@code File}.
     */
    VersionedRook retrieveBook(String repoRelativePath, File destination) throws IOException;

    /**
     * Open a file in the repository for reading. Originally added for parsing the .orgzlyignore
     * file.
     * @param repoRelativePath The file to open
     * @throws IOException
     */
    InputStream openRepoFileInputStream(String repoRelativePath) throws IOException;

    /**
     * Uploads book storing it under given filename under repo's url.
     * @param file The contents of this file should be stored at the remote location/repo
     * @param repoRelativePath The contents ({@code file}) should be stored under this
     *                       (non-encoded) name
     */
    VersionedRook storeBook(File file, String repoRelativePath) throws IOException;

    /**
     *
     * @param oldFullUri Uri of the original repository file
     * @param newName The new desired book name
     * @return
     * @throws IOException
     */
    VersionedRook renameBook(Uri oldFullUri, String newName) throws IOException;

    /**
     * Syncs all books linked to the repo, ignoring all other local books. If a repo
     * rook without a namesake is found, an attempt must be made to create and link a namesake.
     * @param dataRepository To know which books are modified and linked to this repo
     * @return A SyncState if any problems were encountered
     */
    @Nullable
    SyncState syncRepo(DataRepository dataRepository) throws Exception;

    RepoType getType();

    // VersionedRook moveBook(Uri from, Uri uri) throws IOException;

    void delete(Uri uri) throws IOException;

    String toString();

    /**
     * Add or update a file in the remote repository without touching Orgzly's working tree. Only
     * intended for tests.
     * @param content
     * @param repoRelativePath
     * @return The expected URI of the resulting rook
     */
    String writeFileToRepo(String content, String repoRelativePath) throws Exception;
}
