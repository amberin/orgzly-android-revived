package com.orgzly.android.query

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.orgzly.R
import com.orgzly.android.BookFormat
import com.orgzly.android.LocalStorage
import com.orgzly.android.data.DataRepository
import com.orgzly.android.data.DbRepoBookRepository
import com.orgzly.android.db.OrgzlyDatabase
import com.orgzly.android.db.entity.BookView
import com.orgzly.android.db.entity.NoteView
import com.orgzly.android.prefs.AppPreferences
import com.orgzly.android.query.user.InternalQueryParser
import com.orgzly.android.repos.RepoFactory
import com.orgzly.android.ui.NotePlace
import com.orgzly.android.ui.note.NotePayload
import com.orgzly.android.util.MiscUtils
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.MatcherAssert.assertThat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Unit tests for created-at property functionality, migrated from CreatedAtPropertyTest.java.
 * These tests verify created-at property queries, sorting, and automatic property assignment
 * without requiring UI or Espresso.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CreatedAtPropertyTest {

    private lateinit var context: Context
    private lateinit var dataRepository: DataRepository
    private lateinit var database: OrgzlyDatabase
    private lateinit var createdProperty: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // Set up in-memory database for tests
        database = OrgzlyDatabase.forMemory(context)

        val dbRepoBookRepository = DbRepoBookRepository(database)
        val localStorage = LocalStorage(context)
        val repoFactory = RepoFactory(context, dbRepoBookRepository)

        dataRepository = DataRepository(
            context, database, repoFactory, context.resources, localStorage)

        // Get the created property name from resources
        createdProperty = context.getString(R.string.created_property_name)

        // Setup test book with notes containing created-at properties
        setupBook(
            "book-a",
            """
            * Note [a-1]
            :PROPERTIES:
            :$createdProperty: [2018-01-05]
            :ADDED: [2018-01-01]
            :END:
            SCHEDULED: <2018-01-01>
            * Note [a-2]
            :PROPERTIES:
            :$createdProperty: [2018-01-02]
            :ADDED: [2018-01-04]
            :END:
            SCHEDULED: <2014-01-01>
            """.trimIndent()
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun setupBook(name: String, content: String): BookView {
        val tmpFile = dataRepository.getTempBookFile()
        try {
            MiscUtils.writeStringToFile(content, tmpFile)
            return dataRepository.loadBookFromFile(name, BookFormat.ORG, tmpFile, null)!!
        } catch (e: IOException) {
            throw RuntimeException("Failed to setup book: $name", e)
        } finally {
            tmpFile.delete()
        }
    }

    private fun searchNotes(queryString: String): List<NoteView> {
        val parser = InternalQueryParser()
        val parsedQuery = parser.parse(queryString)
        return dataRepository.selectNotesFromQuery(parsedQuery)
    }

    /**
     * Tests that the created-at query condition works when the feature is enabled.
     */
    @Test
    fun testCondition() {
        // Enable created-at property feature
        AppPreferences.createdAt(context, true)
        dataRepository.syncCreatedAtTimeWithProperty()

        val results = searchNotes("cr.le.today")
        assertEquals(2, results.size)
    }

    /**
     * Tests sorting by created-at property in ascending and descending order.
     */
    @Test
    fun testSortOrder() {
        // Enable created-at property feature
        AppPreferences.createdAt(context, true)
        dataRepository.syncCreatedAtTimeWithProperty()

        // Ascending order (o.cr)
        var results = searchNotes("o.cr")
        assertEquals(2, results.size)
        assertThat(results[0].note.title, `is`("Note [a-2]"))  // 2018-01-02

        // Descending order (.o.cr)
        results = searchNotes(".o.cr")
        assertEquals(2, results.size)
        assertThat(results[0].note.title, `is`("Note [a-1]"))  // 2018-01-05
    }

    /**
     * Tests that changing the created-at property name in preferences causes
     * search results to be reordered based on the new property.
     */
    @Test
    fun testChangeCreatedAtPropertyResultsShouldBeReordered() {
        // Initially, without created-at enabled, should sort by ADDED property (falls back to default)
        var results = searchNotes("o.cr")
        assertEquals(2, results.size)
        assertThat(results[0].note.title, `is`("Note [a-1]"))  // ADDED: 2018-01-01
        assertThat(results[1].note.title, `is`("Note [a-2]"))  // ADDED: 2018-01-04

        // Enable created-at feature (uses CREATED_AT property)
        AppPreferences.createdAt(context, true)
        dataRepository.syncCreatedAtTimeWithProperty()

        results = searchNotes("o.cr")
        assertEquals(2, results.size)
        assertThat(results[0].note.title, `is`("Note [a-2]"))  // CREATED_AT: 2018-01-02
        assertThat(results[1].note.title, `is`("Note [a-1]"))  // CREATED_AT: 2018-01-05

        // Change the created-at property name to "ADDED"
        AppPreferences.createdAtProperty(context, "ADDED")
        dataRepository.syncCreatedAtTimeWithProperty()

        results = searchNotes("o.cr")
        assertEquals(2, results.size)
        assertThat(results[0].note.title, `is`("Note [a-1]"))  // ADDED: 2018-01-01
        assertThat(results[1].note.title, `is`("Note [a-2]"))  // ADDED: 2018-01-04
    }

    /**
     * Tests that new notes automatically get the created-at property when the feature is enabled.
     */
    @Test
    fun testNewNote() {
        val book = dataRepository.getBooks().first()

        // Enable created-at property feature
        AppPreferences.createdAt(context, true)
        dataRepository.syncCreatedAtTimeWithProperty()

        // Create a new note
        val notePayload = NotePayload("new note created by test")
        val place = NotePlace(book.book.id)
        dataRepository.createNote(notePayload, place)

        // Verify the note exists
        val allNotes = dataRepository.getNotes(book.book.name)
        assertEquals(3, allNotes.size)

        // Verify the note has the created-at property
        val newNote = allNotes.find { it.note.title == "new note created by test" }
        val properties = dataRepository.getNoteProperties(newNote!!.note.id)
        val createdAtProperty = properties.find { it.name == createdProperty }

        // Should have the created-at property set
        assertEquals(createdProperty, createdAtProperty?.name)

        // Verify sorting: new note should be newest (descending order)
        var results = searchNotes(".o.cr")
        assertThat(results[0].note.title, `is`("new note created by test"))

        // Verify sorting: new note should be last in ascending order
        results = searchNotes("o.cr")
        assertThat(results[0].note.title, `is`("Note [a-2]"))
    }
}