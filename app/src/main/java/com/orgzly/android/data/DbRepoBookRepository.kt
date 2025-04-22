package com.orgzly.android.data

import android.net.Uri
import androidx.core.net.toUri
import com.orgzly.android.db.OrgzlyDatabase
import com.orgzly.android.db.entity.DbRepoBook
import com.orgzly.android.repos.RepoType
import com.orgzly.android.repos.VersionedRook
import com.orgzly.android.util.MiscUtils
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DbRepoBookRepository @Inject constructor(db: OrgzlyDatabase) {
    private val dbRepoBook = db.dbRepoBook()

    fun getBooks(repoId: Long, repoUri: Uri): List<VersionedRook> {
        return dbRepoBook.getAllByRepo(repoUri.toString()).map {
            val repoRelativePath = it.url.toUri().path!!.replaceFirst("^/".toRegex(), "")
            VersionedRook(repoId, RepoType.MOCK, Uri.parse(it.repoUrl), Uri.parse(it.url), repoRelativePath, it.revision, it.mtime)
        }
    }

    fun retrieveBook(repoId: Long, repoUri: Uri, repoRelativePath: String, file: File): VersionedRook {
        val uri = repoUri.buildUpon().path(repoRelativePath).build()
        val book = dbRepoBook.getByUrl(uri.toString()) ?: throw IOException()

        MiscUtils.writeStringToFile(book.content, file)

        return VersionedRook(repoId, RepoType.MOCK, repoUri, uri, repoRelativePath, book.revision, book.mtime)
    }

    fun createBook(repoId: Long, vrook: VersionedRook, content: String): VersionedRook {
        val book = DbRepoBook(
                0,
                vrook.repoUri.toString(),
                vrook.uri.toString(),
                vrook.revision,
                vrook.mtime,
                content,
                System.currentTimeMillis()
        )

        dbRepoBook.replace(book)

        return VersionedRook(
                repoId,
                RepoType.MOCK,
                Uri.parse(book.repoUrl),
                Uri.parse(book.url),
                vrook.repoRelativePath,
                book.revision,
                book.mtime)
    }

    fun renameBook(repoId: Long, from: Uri, to: Uri): VersionedRook {
        val book = dbRepoBook.getByUrl(from.toString())
                ?: throw IOException("Failed moving notebook from $from to $to")

        val renamedBook = book.copy(
                url = to.toString(),
                revision = "MockedRenamedRevision-" + System.currentTimeMillis(),
                mtime = System.currentTimeMillis())

        dbRepoBook.update(renamedBook)

        return VersionedRook(
                repoId,
                RepoType.MOCK,
                Uri.parse(renamedBook.repoUrl),
                Uri.parse(renamedBook.url),
                Uri.parse(renamedBook.url).path!!,
                renamedBook.revision,
                renamedBook.mtime)
    }

    @Throws(IOException::class)
    fun deleteBook(uri: Uri): Int {
        val uriString = uri.toString()
        if (dbRepoBook.getByUrl(uriString) == null) {
            throw IOException("Book $uri does not exist")
        } else {
            return dbRepoBook.deleteByUrl(uriString)
        }
    }
}