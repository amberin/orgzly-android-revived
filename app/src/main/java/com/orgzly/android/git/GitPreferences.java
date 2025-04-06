package com.orgzly.android.git;

import android.net.Uri;

public interface GitPreferences {
    GitTransportSetter createTransportSetter();

    String getAuthor();

    String getEmail();

    String repositoryFilepath();

    Uri remoteUri();
}
