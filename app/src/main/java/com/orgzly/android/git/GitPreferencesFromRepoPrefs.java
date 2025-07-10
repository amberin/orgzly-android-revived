package com.orgzly.android.git;

import android.net.Uri;

import com.orgzly.R;
import com.orgzly.android.prefs.AppPreferences;
import com.orgzly.android.prefs.RepoPreferences;

import java.util.Objects;

public class GitPreferencesFromRepoPrefs implements GitPreferences {
    private RepoPreferences repoPreferences;

    public GitPreferencesFromRepoPrefs(RepoPreferences prefs) {
        repoPreferences = prefs;
    }

    @Override
    public GitTransportSetter createTransportSetter() {
        String scheme = remoteUri().getScheme();
        if (Objects.equals(scheme, "https")) {
            String username = repoPreferences.getStringValue(R.string.pref_key_git_https_username, "");
            String password = repoPreferences.getStringValue(R.string.pref_key_git_https_password, "");
            return new HTTPSTransportSetter(username, password);
        } else {
            //            case "file":
            //                return tc -> tc;
            return new GitSshKeyTransportSetter();
        }
    }

    @Override
    public String getAuthor() {
        return repoPreferences.getStringValueWithGlobalDefault(
                R.string.pref_key_git_author, "orgzly");
    }

    @Override
    public String getEmail() {
        return repoPreferences.getStringValueWithGlobalDefault(
                R.string.pref_key_git_email, "");
    }

    @Override
    public String repositoryFilepath() {
        return repoPreferences.getStringValueWithGlobalDefault(
                R.string.pref_key_git_repository_filepath,
                AppPreferences.repositoryStoragePathForUri(
                        repoPreferences.getContext(), remoteUri()));
    }

    @Override
    public Uri remoteUri() {
        return repoPreferences.getRepoUri();
    }
}
