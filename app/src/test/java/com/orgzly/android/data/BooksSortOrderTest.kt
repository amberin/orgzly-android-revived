package com.orgzly.android.data

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.orgzly.R
import com.orgzly.android.LocalStorage
import com.orgzly.android.TestUtils
import com.orgzly.android.db.OrgzlyDatabase
import com.orgzly.android.prefs.AppPreferences
import com.orgzly.android.repos.RepoFactory
import com.orgzly.android.usecase.NoteUpdateStateToggle
import org.hamcrest.CoreMatchers
import org.hamcrest.MatcherAssert
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests book sort order logic migrated from Espresso to unit tests.
 * Tests that books are sorted correctly based on user preferences.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BooksSortOrderTest {

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
        database = OrgzlyDatabase.Companion.forMemory(context)

        dbRepoBookRepository = DbRepoBookRepository(database)
        localStorage = LocalStorage(context)
        val repoFactory = RepoFactory(context, dbRepoBookRepository)

        dataRepository = DataRepository(
            context, database, repoFactory, context.resources, localStorage
        )

        testUtils = TestUtils(dataRepository, dbRepoBookRepository)

        // Set up default test preferences
        setupPreferences()

        // Create test books
        testUtils.setupBook("Book A", "* Note A-01")
        testUtils.setupBook("Book B", "* Note B-01")
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
    fun books_sortOrder() {
        // Default sort order is by name
        val books = dataRepository.getBooks()

        MatcherAssert.assertThat(books.size, CoreMatchers.`is`(2))
        MatcherAssert.assertThat(books[0].book.name, CoreMatchers.`is`("Book A"))
        MatcherAssert.assertThat(books[1].book.name, CoreMatchers.`is`("Book B"))
    }

    @Test
    fun books_sortOrderAfterSettingsChange() {
        // Get the second book and modify it by changing a note's state
        val note = dataRepository.getNotes("Book B")[0].note

        // Modify the note (this will update the book's mtime)
        NoteUpdateStateToggle(setOf(note.id)).run(dataRepository)

        // Change sort order preference to modification time
        val prefKey = context.getString(R.string.pref_key_notebooks_sort_order)
        val mtimeValue = context.getString(R.string.pref_value_notebooks_sort_order_modification_time)

        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(prefKey, mtimeValue)
            .apply()

        // Verify books are now sorted by modification time (Book B first)
        val booksAfterChange = dataRepository.getBooks()

        MatcherAssert.assertThat(booksAfterChange.size, CoreMatchers.`is`(2))
        MatcherAssert.assertThat(booksAfterChange[0].book.name, CoreMatchers.`is`("Book B"))
        MatcherAssert.assertThat(booksAfterChange[1].book.name, CoreMatchers.`is`("Book A"))
    }
}