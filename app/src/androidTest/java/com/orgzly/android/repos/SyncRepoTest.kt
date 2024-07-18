package com.orgzly.android.repos

import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.assertion.ViewAssertions
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import com.orgzly.BuildConfig
import com.orgzly.R
import com.orgzly.android.BookName
import com.orgzly.android.OrgzlyTest
import com.orgzly.android.db.entity.BookView
import com.orgzly.android.db.entity.Repo
import com.orgzly.android.espresso.util.EspressoUtils
import com.orgzly.android.git.GitFileSynchronizer
import com.orgzly.android.git.GitPreferencesFromRepoPrefs
import com.orgzly.android.prefs.AppPreferences
import com.orgzly.android.prefs.RepoPreferences
import com.orgzly.android.repos.RepoType.DIRECTORY
import com.orgzly.android.repos.RepoType.DOCUMENT
import com.orgzly.android.repos.RepoType.DROPBOX
import com.orgzly.android.repos.RepoType.GIT
import com.orgzly.android.repos.RepoType.MOCK
import com.orgzly.android.repos.RepoType.WEBDAV
import com.orgzly.android.ui.main.MainActivity
import com.orgzly.android.ui.repos.ReposActivity
import com.orgzly.android.util.MiscUtils
import com.thegrizzlylabs.sardineandroid.impl.SardineException
import org.eclipse.jgit.api.Git
import org.hamcrest.CoreMatchers
import org.hamcrest.core.AllOf
import org.junit.Assert
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

@RunWith(value = Parameterized::class)
class SyncRepoTest(private val param: Parameter) : OrgzlyTest() {

    private val repoDirectoryName = "orgzly-android-tests"
    private lateinit var repo: Repo
    private lateinit var syncRepo: SyncRepo
    // Used by GitRepo
    private lateinit var gitWorkingTree: File
    private lateinit var gitBareRepoPath: Path
    private lateinit var gitFileSynchronizer: GitFileSynchronizer
    // used by ContentRepo
    private lateinit var documentTreeSegment: String
    private lateinit var treeDocumentFileUrl: String

    data class Parameter(val repoType: RepoType)

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Parameter> {
            return listOf(
                Parameter(repoType = GIT),
                Parameter(repoType = DROPBOX),
                Parameter(repoType = DOCUMENT),
                Parameter(repoType = WEBDAV),
            )
        }
    }

    override fun tearDown() {
        super.tearDown()
        if (this::repo.isInitialized) {
            when (repo.type) {
                GIT -> tearDownGitRepo()
                MOCK -> TODO()
                DROPBOX -> tearDownDropboxRepo()
                DIRECTORY -> TODO()
                DOCUMENT -> tearDownContentRepo()
                WEBDAV -> tearDownWebdavRepo()
            }
        }
    }

    @JvmField
    @Rule
    var exceptionRule: ExpectedException = ExpectedException.none()

    @Test
    @Throws(IOException::class)
    fun testStoringFile() {
        setupSyncRepo(param.repoType, null)
        val tmpFile = dataRepository.getTempBookFile()
        try {
            MiscUtils.writeStringToFile("...", tmpFile)
            syncRepo.storeBook(tmpFile, "booky.org")
        } finally {
            tmpFile.delete()
        }
        val books = syncRepo.books
        Assert.assertEquals(1, books.size.toLong())
        Assert.assertEquals("booky", BookName.getInstance(context, books[0]).name)
        Assert.assertEquals("booky.org", BookName.getInstance(context, books[0]).fileName)
        Assert.assertEquals(repo.url, books[0].repoUri.toString())
    }

    @Test
    @Throws(IOException::class)
    fun testExtension() {
        setupSyncRepo(param.repoType, null)
        // Add multiple files to repo
        for (fileName in arrayOf("file one.txt", "file two.o", "file three.org")) {
            val tmpFile = File.createTempFile("orgzly-test", null)
            MiscUtils.writeStringToFile("book content", tmpFile)
            syncRepo.storeBook(tmpFile, fileName)
            tmpFile.delete()
        }
        val books = syncRepo.books
        Assert.assertEquals(1, books.size.toLong())
        Assert.assertEquals("file three", BookName.getInstance(context, books[0]).name)
        Assert.assertEquals("file three.org", BookName.getInstance(context, books[0]).fileName)
        Assert.assertEquals(repo.id, books[0].repoId)
        Assert.assertEquals(repo.url, books[0].repoUri.toString())
    }

    @Test
    fun testSyncNewBookWithoutLinkAndOneRepo() {
        setupSyncRepo(param.repoType, null)
        testUtils.setupBook("book 1", "content")
        testUtils.sync()
        val bookView = dataRepository.getBooks()[0]
        Assert.assertEquals(repo.url, bookView.linkRepo?.url)
        Assert.assertEquals(1, syncRepo.books.size)
        Assert.assertEquals(bookView.syncedTo.toString(), syncRepo.books[0].toString())
        Assert.assertEquals(
            context.getString(R.string.sync_status_saved, repo.url),
            bookView.book.lastAction!!.message
        )
        val expectedUriString = when (param.repoType) {
            GIT -> "/book 1.org"
            MOCK -> TODO()
            DROPBOX -> "dropbox:/orgzly-android-tests/book%201.org"
            DIRECTORY -> TODO()
            DOCUMENT -> "content://com.android.externalstorage.documents/tree/primary%3A$repoDirectoryName/document/primary%3A$repoDirectoryName%2Fbook%201.org"
            WEBDAV -> "https://use10.thegood.cloud/remote.php/dav/files/orgzlyrevived%40gmail.com/$repoDirectoryName/book 1.org"
        }
        Assert.assertEquals(expectedUriString, bookView.syncedTo!!.uri.toString())
    }

    @Test
    fun testIgnoreRulePreventsLoadingBook() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 26) // .orgzlyignore not supported below API 26
        val ignoreRules = """
            ignoredbook.org
            ignored-*.org
        """.trimIndent()
        setupSyncRepo(param.repoType, ignoreRules)
        // Add multiple files to repo
        for (fileName in arrayOf("ignoredbook.org", "ignored-3.org", "notignored.org")) {
            val tmpFile = File.createTempFile("orgzly-test", null)
            MiscUtils.writeStringToFile("book content", tmpFile)
            syncRepo.storeBook(tmpFile, fileName)
            tmpFile.delete()
        }
        testUtils.sync()
        Assert.assertEquals(1, syncRepo.books.size)
        Assert.assertEquals(1, dataRepository.getBooks().size)
        Assert.assertEquals("notignored", dataRepository.getBooks()[0].book.name)
    }

    @Test
    fun testUnIgnoredFilesInRepoAreLoaded() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 26)
        val ignoreFileContents = """
            *.org
            !notignored.org
        """.trimIndent()
        setupSyncRepo(param.repoType, ignoreFileContents)
        // Add multiple files to repo
        for (fileName in arrayOf("ignoredbook.org", "ignored-3.org", "notignored.org")) {
            val tmpFile = File.createTempFile("orgzlytest", null)
            MiscUtils.writeStringToFile("book content", tmpFile)
            syncRepo.storeBook(tmpFile, fileName)
            tmpFile.delete()
        }
        testUtils.sync()
        Assert.assertEquals(1, syncRepo.books.size)
        Assert.assertEquals(1, dataRepository.getBooks().size)
        Assert.assertEquals("notignored", dataRepository.getBooks()[0].book.name)
    }

    @Test
    fun testIgnoreRulePreventsRenamingBook() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 26)
        setupSyncRepo(param.repoType,"bad name*")

        // Create book and sync it
        testUtils.setupBook("good name", "")
        testUtils.sync()
        var bookView: BookView? = dataRepository.getBookView("good name")
        dataRepository.renameBook(bookView!!, "bad name")
        bookView = dataRepository.getBooks()[0]
        Assert.assertTrue(
            bookView.book.lastAction.toString().contains("matches a rule in .orgzlyignore")
        )
    }

    @Test
    @Throws(java.lang.Exception::class)
    fun testIgnoreRulePreventsLinkingBook() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 26)
        setupSyncRepo(param.repoType, "*.org")
        testUtils.setupBook("booky", "")
        exceptionRule.expect(IOException::class.java)
        exceptionRule.expectMessage("matches a rule in .orgzlyignore")
        testUtils.syncOrThrow()
    }

    private fun setupSyncRepo(repoType: RepoType, ignoreRules: String?) {
        when (repoType) {
            GIT -> setupGitRepo()
            MOCK -> TODO()
            DROPBOX -> setupDropboxRepo()
            DIRECTORY -> TODO()
            DOCUMENT -> setupContentRepo()
            WEBDAV -> setupWebdavRepo()
        }
        if (ignoreRules != null) {
            val tmpFile = File.createTempFile("orgzly-test", null)
            MiscUtils.writeStringToFile(ignoreRules, tmpFile)
            syncRepo.storeBook(tmpFile, RepoIgnoreNode.IGNORE_FILE)
            tmpFile.delete()
        }
    }

    private fun setupDropboxRepo() {
        testUtils.dropboxTestPreflight()
        repo = testUtils.setupRepo(DROPBOX, "dropbox:/$repoDirectoryName")
        syncRepo = testUtils.repoInstance(DROPBOX, repo.url, repo.id)
    }

    private fun tearDownDropboxRepo() {
        val dropboxRepo = syncRepo as DropboxRepo
        dropboxRepo.deleteDirectory(syncRepo.uri)
    }

    private fun setupContentRepo() {
        ActivityScenario.launch(ReposActivity::class.java).use {
            Espresso.onView(ViewMatchers.withId(R.id.activity_repos_directory))
                .perform(ViewActions.click())
            Espresso.onView(ViewMatchers.withId(R.id.activity_repo_directory_browse_button))
                .perform(ViewActions.click())
            SystemClock.sleep(100)
            // In Android file browser (Espresso cannot be used):
            val mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            mDevice.findObject(UiSelector().text("CREATE NEW FOLDER")).click()
            SystemClock.sleep(100)
            mDevice.findObject(UiSelector().text("Folder name")).text = repoDirectoryName
            mDevice.findObject(UiSelector().text("OK")).click()
            mDevice.findObject(UiSelector().text("USE THIS FOLDER")).click()
            mDevice.findObject(UiSelector().text("ALLOW")).click()
            // Back in Orgzly:
            Espresso.onView(ViewMatchers.isRoot()).perform(EspressoUtils.waitId(R.id.fab, 5000))
            Espresso.onView(AllOf.allOf(ViewMatchers.withId(R.id.fab), ViewMatchers.isDisplayed()))
                .perform(ViewActions.click())
        }
        repo = dataRepository.getRepos()[0]
        syncRepo = testUtils.repoInstance(DOCUMENT, repo.url, repo.id)
        val encodedRepoDirName = Uri.encode(repoDirectoryName)
        documentTreeSegment = "/document/primary%3A$encodedRepoDirName%2F"
        treeDocumentFileUrl = "content://com.android.externalstorage.documents/tree/primary%3A$encodedRepoDirName"
        Assert.assertEquals(treeDocumentFileUrl, repo.url)
    }

    private fun tearDownContentRepo() {
        DocumentFile.fromTreeUri(context, treeDocumentFileUrl.toUri())!!.delete()
    }

    private fun setupWebdavRepo() {
        testUtils.webdavTestPreflight()
        val repoProps: MutableMap<String, String> = mutableMapOf(
            WebdavRepo.USERNAME_PREF_KEY to BuildConfig.WEBDAV_USERNAME,
            WebdavRepo.PASSWORD_PREF_KEY to BuildConfig.WEBDAV_PASSWORD)
        repo = testUtils.setupRepo(WEBDAV, BuildConfig.WEBDAV_REPO_URL + "/" + repoDirectoryName, repoProps)
        syncRepo = dataRepository.getRepoInstance(repo.id, WEBDAV, repo.url)
        testUtils.sync() // Required to create the remote directory
    }

    private fun tearDownWebdavRepo() {
        try {
            syncRepo.delete(repo.url.toUri())
        } catch (e: SardineException) {
            if (e.statusCode != 404) {
                throw e
            }
        }
    }

    private fun setupGitRepo() {
        gitBareRepoPath = createTempDirectory()
        Git.init().setBare(true).setDirectory(gitBareRepoPath.toFile()).call()
        AppPreferences.gitIsEnabled(context, true)
        repo = testUtils.setupRepo(GIT, gitBareRepoPath.toFile().toUri().toString())
        val repoPreferences = RepoPreferences(context, repo.id, repo.url.toUri())
        val gitPreferences = GitPreferencesFromRepoPrefs(repoPreferences)
        gitWorkingTree = File(gitPreferences.repositoryFilepath())
        gitWorkingTree.mkdirs()
        val git = GitRepo.ensureRepositoryExists(gitPreferences, true, null)
        gitFileSynchronizer = GitFileSynchronizer(git, gitPreferences)
        syncRepo = dataRepository.getRepoInstance(repo.id, GIT, repo.url)
    }

    private fun tearDownGitRepo() {
        testUtils.deleteRepo(repo.url)
        gitWorkingTree.deleteRecursively()
        gitBareRepoPath.toFile()!!.deleteRecursively()
    }
}