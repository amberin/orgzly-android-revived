package com.orgzly.android.repos;

import android.net.Uri;


/**
 * Remote notebook.
 *
 * Defined by repository URI, URI of the notebook itself and the repo-relative path of the notebook.
 *
 * All three are necessary. For example, if notebook URI is file:/Downloads/org/notes.org
 * its repository URI could be either file:/Downloads or file:/Downloads/org. Finally,
 * DocumentFile URIs do not carry any meaning, so it is impossible to infer the repo-relative
 * path of such a SAF notebook file from the two URIs alone.
 */
public class Rook {
    protected long repoId;
    protected RepoType repoType;
    protected Uri repoUri;
    protected Uri uri;
    protected String repoRelativePath;

    public Rook(long repoId, RepoType repoType, Uri repoUri, Uri uri, String repoRelativePath) {
        this.repoId = repoId;
        this.repoType = repoType;
        this.repoUri = repoUri;
        this.uri = uri;
        this.repoRelativePath = repoRelativePath;
    }

    public long getRepoId() {
        return repoId;
    }

    public RepoType getRepoType() {
        return repoType;
    }

    public Uri getRepoUri() {
        return repoUri;
    }

    public Uri getUri() {
        return uri;
    }

    public String getRepoRelativePath() { return repoRelativePath; }

    public String toString() {
        return uri.toString();
    }
}
