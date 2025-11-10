package com.orgzly.android.repos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.orgzly.R
import com.orgzly.android.LocalStorage
import com.orgzly.android.TestUtils
import com.orgzly.android.data.DataRepository
import com.orgzly.android.data.DbRepoBookRepository
import com.orgzly.android.db.OrgzlyDatabase
import com.orgzly.android.prefs.AppPreferences
import com.orgzly.android.usecase.RepoCreate
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.MatcherAssert.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests repository configuration and URI encoding logic. Migrated from Espresso to unit tests.
 * Tests repo creation, URI encoding/decoding, and validation without requiring UI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReposTest {

    private lateinit var context: Context
    private lateinit var dataRepository: DataRepository
    private lateinit var database: OrgzlyDatabase
    private lateinit var dbRepoBookRepository: DbRepoBookRepository
    private lateinit var localStorage: LocalStorage
    private lateinit var testUtils: TestUtils

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // In-memory database for tests (faster than file-based)
        database = OrgzlyDatabase.forMemory(context)

        dbRepoBookRepository = DbRepoBookRepository(database)
        localStorage = LocalStorage(context)
        val repoFactory = RepoFactory(context, dbRepoBookRepository)

        dataRepository = DataRepository(
            context, database, repoFactory, context.resources, localStorage
        )

        testUtils = TestUtils(dataRepository, dbRepoBookRepository)

        // Set up default test preferences
        setupPreferences()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun setupPreferences() {
        AppPreferences.states(context, "TODO NEXT | DONE")
        AppPreferences.isGettingStartedNotebookLoaded(context, true)
        AppPreferences.displayedBookDetails(
            context,
            context.resources.getStringArray(R.array.displayed_book_details_values).toList()
        )
        AppPreferences.prefaceDisplay(
            context,
            context.getString(R.string.pref_value_preface_in_book_few_lines)
        )
        AppPreferences.inheritedTagsInSearchResults(context, true)
        AppPreferences.colorTheme(context, "light")
        AppPreferences.logMajorEvents(context, true)
    }

    @Test
    fun testDirectoryRepoWithPercentCharacter() {
        val localBaseDir = context.externalCacheDir!!.absolutePath
        val localDir = "$localBaseDir/nextcloud/user@host%2Fdir"
        val repoUri = "file:$localBaseDir/nextcloud/user@host%252Fdir"

        // Create the directory
        File(localDir).mkdirs()

        // Create repo with encoded URI
        val repo = testUtils.setupRepo(RepoType.DIRECTORY, repoUri)

        // Verify the repo was created with the correct URI
        assertThat(repo.url, `is`(repoUri))

        // Verify we can retrieve it
        val retrievedRepo = dataRepository.getRepo(repoUri)
        assertThat(retrievedRepo, `is`(repo))
        assertThat(retrievedRepo?.url, `is`(repoUri))
    }

    @Test
    fun testCreateRepoWithExistingUrl() {
        val url = "file:${context.externalCacheDir!!.absolutePath}"

        // Create first repo
        val firstRepo = testUtils.setupRepo(RepoType.DIRECTORY, url)
        assertThat(firstRepo.url, `is`(url))

        // Attempt to create second repo with same URL
        try {
            testUtils.setupRepo(RepoType.DIRECTORY, url)
            throw AssertionError("Expected exception when creating duplicate repo")
        } catch (ignored: RepoCreate.AlreadyExists) {
            // Expected - duplicate repos not allowed
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            // Also acceptable - database constraint violation
            assertThat(e.message?.contains("UNIQUE constraint") ?: false, `is`(true))
        }

        // Verify only one repo exists
        val allRepos = dataRepository.getRepos()
        assertThat(allRepos.size, `is`(1))
        assertThat(allRepos[0].url, `is`(url))
    }

    @Test
    fun testCreateRepoWithNonExistentDirectory() {
        // This test verifies that creating a repo with a non-existent directory
        // doesn't crash - the validation happens when syncing, not when creating
        val nonExistentUrl = "file:/non-existent-directory"

        val repo = testUtils.setupRepo(RepoType.DIRECTORY, nonExistentUrl)

        // Verify the repo was created (even though directory doesn't exist)
        assertThat(repo.url, `is`(nonExistentUrl))

        // Verify we can retrieve it
        val retrievedRepo = dataRepository.getRepo(nonExistentUrl)
        assertThat(retrievedRepo, `is`(repo))
    }
}