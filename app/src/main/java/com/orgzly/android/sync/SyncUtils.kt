package com.orgzly.android.sync

import androidx.core.net.toUri
import com.orgzly.BuildConfig
import com.orgzly.android.BookFormat
import com.orgzly.android.BookName
import com.orgzly.android.data.DataRepository
import com.orgzly.android.db.entity.BookAction
import com.orgzly.android.db.entity.BookView
import com.orgzly.android.db.entity.Repo
import com.orgzly.android.repos.RepoType
import com.orgzly.android.repos.VersionedRook
import com.orgzly.android.util.LogUtils
import java.io.IOException

object SyncUtils {
    private val TAG: String = SyncUtils::class.java.name

    /**
     * Goes through each regular SyncRepo (i.e. not IntegrallySyncedRepos) and collects
     * all books from each one.
     */
    @Throws(IOException::class)
    @JvmStatic
    fun getVrooksFromRegularSyncRepos(dataRepository: DataRepository): List<VersionedRook> {
        val result = ArrayList<VersionedRook>()

        val repoList = dataRepository.getRegularSyncRepos()

        for (repo in repoList) {
            val libBooks = repo.books
            /* Each book in repository. */
            result.addAll(libBooks)
        }
        return result
    }

    /**
     * Compares remote books with their local equivalent and calculates the syncStatus for each link.
     *
     * N.B! Ignores all local books which are synced to a repo of type GIT.
     *
     * @return number of links (unique book names)
     * @throws IOException
     */
    @Throws(IOException::class)
    @JvmStatic
    fun groupNotebooksByName(dataRepository: DataRepository): Map<String, BookNamesake> {
        if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG, "Collecting local and remote books ...")

        val repos = dataRepository.getRepos()

        val localBooks = mutableListOf<BookView>()
        /* TODO: This is a very ugly hack, as nothing ties the GIT repo type to the
            IntegrallySyncedRepo interface. Find a better solution! */
        for (localBook in dataRepository.getBooks()) {
            if (localBook.hasLink() && localBook.linkRepo!!.type == RepoType.GIT) {
                continue
            } else {
                localBooks.add(localBook)
            }
        }

        val versionedRooks = getVrooksFromRegularSyncRepos(dataRepository)

        /* Group local and remote books by name. */
        val namesakes = BookNamesake.getAll(localBooks, versionedRooks)

        /* If there is no local book, create empty "dummy" one. */
        for (namesake in namesakes.values) {
            if (namesake.book == null) {
                namesake.book = dataRepository.createDummyBook(namesake.name)
            }

            namesake.updateStatus(repos.size)
        }

        return namesakes
    }

    /**
     * Passed [com.orgzly.android.sync.BookNamesake] is NOT updated after load or save.
     *
     * FIXME: Hardcoded BookName.Format.ORG below
     */
    @Throws(Exception::class)
    @JvmStatic
    fun syncNamesake(dataRepository: DataRepository, namesake: BookNamesake): BookAction {
        val repoEntity: Repo?
        val repoUrl: String
        val repositoryPath: String
        var bookAction: BookAction? = null

        when (namesake.status!!) {
            BookSyncStatus.NO_CHANGE ->
                bookAction = BookAction.forNow(BookAction.Type.INFO, namesake.status.msg())

            /* Error states */

            BookSyncStatus.BOOK_WITHOUT_LINK_AND_ONE_OR_MORE_ROOKS_EXIST,
            BookSyncStatus.DUMMY_WITHOUT_LINK_AND_MULTIPLE_ROOKS,
            BookSyncStatus.NO_BOOK_MULTIPLE_ROOKS,
            BookSyncStatus.ONLY_BOOK_WITHOUT_LINK_AND_MULTIPLE_REPOS,
            BookSyncStatus.BOOK_WITH_LINK_AND_ROOK_EXISTS_BUT_LINK_POINTING_TO_DIFFERENT_ROOK,
            BookSyncStatus.CONFLICT_BOTH_BOOK_AND_ROOK_MODIFIED,
            BookSyncStatus.CONFLICT_BOOK_WITH_LINK_AND_ROOK_BUT_NEVER_SYNCED_BEFORE,
            BookSyncStatus.CONFLICT_LAST_SYNCED_ROOK_AND_LATEST_ROOK_ARE_DIFFERENT,
            BookSyncStatus.ROOK_AND_VROOK_HAVE_DIFFERENT_REPOS,
            BookSyncStatus.ONLY_DUMMY,
            BookSyncStatus.CONFLICT_STAYING_ON_TEMPORARY_BRANCH,
            BookSyncStatus.BOOK_WITH_PREVIOUS_ERROR_AND_NO_LINK ->
                bookAction = BookAction.forNow(BookAction.Type.ERROR, namesake.status.msg())

            BookSyncStatus.ROOK_NO_LONGER_EXISTS -> {
                /* Remove repository link and "synced to" information. User must set a repo link if
                 * they want to keep the book and sync it. */
                dataRepository.setLink(namesake.book.book.id, null)
                dataRepository.removeBookSyncedTo(namesake.book.book.id)
                bookAction = BookAction.forNow(BookAction.Type.ERROR, namesake.status.msg())
            }

            /* Load remote book. */

            BookSyncStatus.NO_BOOK_ONE_ROOK, BookSyncStatus.DUMMY_WITHOUT_LINK_AND_ONE_ROOK -> {
                dataRepository.loadBookFromRepo(namesake.rooks[0])
                bookAction = BookAction.forNow(
                    BookAction.Type.INFO,
                    namesake.status.msg(namesake.rooks[0].uri))
            }

            BookSyncStatus.DUMMY_WITH_LINK, BookSyncStatus.BOOK_WITH_LINK_AND_ROOK_MODIFIED -> {
                dataRepository.loadBookFromRepo(namesake.latestLinkedRook)
                bookAction = BookAction.forNow(
                    BookAction.Type.INFO,
                    namesake.status.msg(namesake.latestLinkedRook.uri))
            }

            /* Save local book to repository. */

            BookSyncStatus.ONLY_BOOK_WITHOUT_LINK_AND_ONE_REPO -> {
                repoEntity = dataRepository.getRepos().iterator().next()
                repoUrl = repoEntity.url
                repositoryPath = BookName.repoRelativePath(namesake.book.book.name, BookFormat.ORG)
                /* Set repo link before saving to ensure repo ignore rules are checked */
                dataRepository.setLink(namesake.book.book.id, repoEntity)
                dataRepository.saveBookToRepo(repoEntity, repositoryPath, namesake.book, BookFormat.ORG)
                bookAction = BookAction.forNow(BookAction.Type.INFO, namesake.status.msg(repoUrl))
            }

            BookSyncStatus.BOOK_WITH_LINK_LOCAL_MODIFIED -> {
                repoEntity = namesake.book.linkRepo
                repoUrl = repoEntity!!.url
                repositoryPath = BookName.getRepoRelativePath(repoUrl.toUri(), namesake.book.syncedTo!!.uri)
                dataRepository.saveBookToRepo(repoEntity, repositoryPath, namesake.book, BookFormat.ORG)
                bookAction = BookAction.forNow(BookAction.Type.INFO, namesake.status.msg(repoUrl))
            }

            BookSyncStatus.ONLY_BOOK_WITH_LINK -> {
                repoEntity = namesake.book.linkRepo
                repoUrl = repoEntity!!.url
                repositoryPath = BookName.repoRelativePath(namesake.book.book.name, BookFormat.ORG)
                dataRepository.saveBookToRepo(repoEntity, repositoryPath, namesake.book, BookFormat.ORG)
                bookAction = BookAction.forNow(BookAction.Type.INFO, namesake.status.msg(repoUrl))
            }
        }

        return bookAction
    }
}
