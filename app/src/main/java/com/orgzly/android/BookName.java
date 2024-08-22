package com.orgzly.android;

import android.content.Context;
import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;

import com.orgzly.BuildConfig;
import com.orgzly.android.db.entity.BookView;
import com.orgzly.android.repos.Rook;
import com.orgzly.android.repos.VersionedRook;
import com.orgzly.android.util.LogUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Given a filename determines book's format based on extension.
 * Given a book name and a format - constructs a filename.
 */
public class BookName {
    private static final String TAG = BookName.class.getName();

    private static final Pattern PATTERN = Pattern.compile("(.*)\\.(org)(\\.txt)?$");
    private static final Pattern SKIP_PATTERN = Pattern.compile("^\\.#.*");

    private final String mRepoRelativePath;
    private final String mName;
    private final BookFormat mFormat;

    private BookName(String repoRelativePath, String name, BookFormat format) {
        mRepoRelativePath = repoRelativePath;
        mName = name;
        mFormat = format;
    }

    public static String getRepoRelativePath(BookView bookView) {
        if (bookView.getSyncedTo() != null) {
            VersionedRook vrook = bookView.getSyncedTo();
            return getRepoRelativePath(vrook.getRepoUri(), vrook.getUri());
        } else {
            // There is no remote book; we can only guess the repo path from the book's name.
            return repoRelativePath(bookView.getBook().getName(), BookFormat.ORG);
        }
    }

    /**
     * Used when creating a Book from an imported file.
     * @param context Used for getting a DocumentFile, if possible
     * @param uri URI provided by the file picker
     * @return The book's file name
     */
    public static String getFileName(Context context, Uri uri) {
        String fileName;
        DocumentFile documentFile = DocumentFile.fromSingleUri(context, uri);

        if ("content".equals(uri.getScheme()) && documentFile != null) {
            // Try using DocumentFile first (KitKat and above)
            fileName = documentFile.getName();
        } else { // Just get the last path segment
            fileName = uri.getLastPathSegment();
        }

        if (BuildConfig.LOG_DEBUG) LogUtils.d(
                TAG,
                uri,
                documentFile,
                fileName);

        return fileName;
    }

    public static String getRepoRelativePath(Uri repoUri, Uri fileUri) {
        /* The content:// repository type requires special handling */
        if ("content".equals(repoUri.getScheme())) {
            String repoUriLastSegment = repoUri.toString().replaceAll("^.*/", "");
            String repoRootUriSegment = repoUri + "/document/" + repoUriLastSegment + "%2F";
            return Uri.decode(fileUri.toString().replace(repoRootUriSegment, ""));
        } else {
            // Just return the decoded fileUri stripped of the repoUri (if present), and stripped
            // of any leading / (if present).
            return Uri.decode(
                    fileUri.toString().replace(repoUri.toString(), "")
            ).replaceFirst("^/", "");
        }
    }

    public static BookName fromRook(Rook rook) {
        return fromRepoRelativePath(getRepoRelativePath(rook.getRepoUri(), rook.getUri()));
    }

    public static boolean isSupportedFormatFileName(String path) {
        return PATTERN.matcher(path).matches() && !SKIP_PATTERN.matcher(path).matches();
    }

    public static String repoRelativePath(String name, BookFormat format) {
        if (format == BookFormat.ORG) {
            return name + ".org";
        } else {
            throw new IllegalArgumentException("Unsupported format " + format);
        }
    }

    public static String lastPathSegment(String name, BookFormat format) {
        if (format == BookFormat.ORG) {
            return Uri.parse(name).getLastPathSegment() + ".org";
        } else {
            throw new IllegalArgumentException("Unsupported format " + format);
        }
    }

    public static BookName fromRepoRelativePath(String repoRelativePath) {
        if (repoRelativePath != null) {
            Matcher m = PATTERN.matcher(repoRelativePath);

            if (m.find()) {
                String name = m.group(1);
                String extension = m.group(2);

                if (extension.equals("org")) {
                    return new BookName(repoRelativePath, name, BookFormat.ORG);
                }
            }
        }

        throw new IllegalArgumentException("Unsupported book file name " + repoRelativePath);
    }

    public String getName() {
        return mName;
    }

    public BookFormat getFormat() {
        return mFormat;
    }

    public String getRepoRelativePath() {
        return mRepoRelativePath;
    }

}
