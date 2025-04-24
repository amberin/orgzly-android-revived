package com.orgzly.android.repos;

import android.net.Uri;
import android.os.SystemClock;


import androidx.test.core.app.ApplicationProvider;

import com.orgzly.android.data.DbRepoBookRepository;
import com.orgzly.android.prefs.AppPreferences;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Wrapper around {@link DatabaseRepo}.
 *
 * Simulates slow network.
 *
 * TODO: Use {@link DirectoryRepo} instead, remove {@link DbRepoBookRepository}.
 */
public class MockRepo implements SyncRepo {
    private static final long SLEEP_FOR_GET_BOOKS = 100;
    private static final long SLEEP_FOR_RETRIEVE_BOOK = 200;
    private static final long SLEEP_FOR_STORE_BOOK = 200;
    private static final long SLEEP_FOR_DELETE_BOOK = 100;

    public static final String IGNORE_RULES_PREF_KEY = "ignore_rules";

    private String ignoreRules;

    private DatabaseRepo databaseRepo;

    public MockRepo(RepoWithProps repoWithProps, DbRepoBookRepository dbRepo) {
        databaseRepo = new DatabaseRepo(repoWithProps, dbRepo);
        ignoreRules = AppPreferences.repoPropsMap(ApplicationProvider.getApplicationContext(),
                repoWithProps.getRepo().getId()).get(IGNORE_RULES_PREF_KEY);
    }

    @Override
    public boolean isConnectionRequired() {
        return false;
    }

    @Override
    public boolean isAutoSyncSupported() {
        return true;
    }

    @Override
    public Uri getUri() {
        return databaseRepo.getUri();
    }

    @Override
    public List<VersionedRook> getBooks() throws IOException {
        SystemClock.sleep(SLEEP_FOR_GET_BOOKS);
        return databaseRepo.getBooks();
    }

    @Override
    public VersionedRook retrieveBook(String repoRelativePath, File file) throws IOException {
        SystemClock.sleep(SLEEP_FOR_RETRIEVE_BOOK);
        return databaseRepo.retrieveBook(repoRelativePath, file);
    }

    @Override
    public InputStream openRepoFileInputStream(String repoRelativePath) throws IOException {
        if (repoRelativePath.equals(RepoIgnoreNode.IGNORE_FILE) && ignoreRules != null) {
            return new ByteArrayInputStream(ignoreRules.getBytes());
        } else {
            throw new FileNotFoundException();
        }
    }

    @Override
    public VersionedRook storeBook(File file, String repoRelativePath) throws IOException {
        SystemClock.sleep(SLEEP_FOR_STORE_BOOK);
        return databaseRepo.storeBook(file, repoRelativePath);
    }

    @Override
    public VersionedRook renameBook(Uri oldFullUri, String newName) throws IOException {
        SystemClock.sleep(SLEEP_FOR_STORE_BOOK);
        return databaseRepo.renameBook(oldFullUri, newName);
    }

    @Override
    public void delete(Uri uri) throws IOException {
        SystemClock.sleep(SLEEP_FOR_DELETE_BOOK);
        databaseRepo.delete(uri);
    }
}
