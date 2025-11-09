package com.orgzly.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.orgzly.android.BookFormat
import com.orgzly.android.LocalStorage
import com.orgzly.android.db.OrgzlyDatabase
import com.orgzly.android.db.entity.BookView
import com.orgzly.android.prefs.AppPreferences
import com.orgzly.android.repos.RepoFactory
import com.orgzly.android.util.MiscUtils
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.MatcherAssert.assertThat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Unit tests for DataRepository, focusing on security validations and settings import/export.
 * Tests run on JVM using Robolectric for fast execution without requiring an emulator.
 *
 * Settings import/export tests migrated from app/src/androidTest/java/com/orgzly/android/data/DataRepositoryTest.kt
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DataRepositoryTest {

    private lateinit var context: Context
    private lateinit var dataRepository: DataRepository
    private lateinit var database: OrgzlyDatabase

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
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * Helper method to setup a book with org content.
     * Similar to TestUtils.setupBook() but for Robolectric tests.
     */
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

    // ===== Tests for createBook() path traversal validation =====

    @Test
    fun testCreateBookWithPathTraversalAtStartThrowsException() {
        val exception = assertThrows(IOException::class.java) {
            dataRepository.createBook("../malicious-name")
        }
        assertThat(exception.message, containsString("Book names cannot contain '../'"))
    }

    @Test
    fun testCreateBookWithPathTraversalInMiddleThrowsException() {
        val exception = assertThrows(IOException::class.java) {
            dataRepository.createBook("prefix/../malicious")
        }
        assertThat(exception.message, containsString("Book names cannot contain '../'"))
    }

    @Test
    fun testCreateBookWithPathTraversalAtEndThrowsException() {
        val exception = assertThrows(IOException::class.java) {
            dataRepository.createBook("malicious../")
        }
        assertThat(exception.message, containsString("Book names cannot contain '../'"))
    }

    @Test
    fun testCreateBookWithMultiplePathTraversalsThrowsException() {
        val exception = assertThrows(IOException::class.java) {
            dataRepository.createBook("../../very-malicious")
        }
        assertThat(exception.message, containsString("Book names cannot contain '../'"))
    }

    @Test
    fun testCreateBookWithValidNameSucceeds() {
        // Should not throw
        val book = dataRepository.createBook("valid-book-name")
        assertThat(book.book.name, `is`("valid-book-name"))
    }

    @Test
    fun testCreateBookWithDotsInNameSucceeds() {
        // Single dots and multiple consecutive dots (not "../") should be allowed
        val book = dataRepository.createBook("file.with.dots")
        assertThat(book.book.name, `is`("file.with.dots"))
    }

    @Test
    fun testCreateBookWithConsecutiveDotsSucceeds() {
        // ".." without "/" should be allowed (edge case)
        val book = dataRepository.createBook("file..name")
        assertThat(book.book.name, `is`("file..name"))
    }

    // ===== Tests for renameBook() path traversal validation =====

    @Test
    fun testRenameBookWithPathTraversalGivesError() {
        val book = dataRepository.createBook("valid-book")
        dataRepository.renameBook(book, "../malicious-name")
        assertThat(
            dataRepository.getBook(book.book.id)!!.lastAction!!.message,
            containsString("Book names cannot contain '../'")
        )
    }

    @Test
    fun testRenameBookWithPathTraversalInMiddleGivesError() {
        val book = dataRepository.createBook("valid-book")

        dataRepository.renameBook(book, "prefix/../malicious")
        assertThat(
            dataRepository.getBook(book.book.id)!!.lastAction!!.message,
            containsString("Book names cannot contain '../'")
        )
    }

    @Test
    fun testRenameBookWithPathTraversalAtEndGivesError() {
        val book = dataRepository.createBook("valid-book")

        dataRepository.renameBook(book, "malicious../")
        assertThat(
            dataRepository.getBook(book.book.id)!!.lastAction!!.message,
            containsString("Book names cannot contain '../'")
        )
    }

    @Test
    fun testRenameBookWithMultiplePathTraversalsGivesError() {
        val book = dataRepository.createBook("valid-book")

        dataRepository.renameBook(book, "../../../very-malicious")
        assertThat(
            dataRepository.getBook(book.book.id)!!.lastAction!!.message,
            containsString("Book names cannot contain '../'")
        )
    }

    @Test
    fun testRenameBookWithValidNameSucceeds() {
        val book = dataRepository.createBook("original-name")

        // Should not throw
        dataRepository.renameBook(book, "new-valid-name")
        assertEquals("new-valid-name", dataRepository.getBook(book.book.id)!!.name)
    }

    @Test
    fun testRenameBookWithDotsInNameSucceeds() {
        val book = dataRepository.createBook("original-name")

        dataRepository.renameBook(book, "renamed.with.dots")
        assertEquals("renamed.with.dots", dataRepository.getBook(book.book.id)!!.name)
    }

    @Test
    fun testRenameBookWithConsecutiveDotsSucceeds() {
        val book = dataRepository.createBook("original-name")

        // ".." without "/" should be allowed
        dataRepository.renameBook(book, "renamed..name")
        assertEquals("renamed..name", dataRepository.getBook(book.book.id)!!.name)
    }

    // ===== Tests for settings import/export validation =====
    // Migrated from app/src/androidTest/java/com/orgzly/android/data/DataRepositoryTest.kt

    /**
     * If the user attempts to export app settings to a note with a non-unique "ID" value, then
     * - an exception should be thrown
     * - no export should happen
     */
    @Test
    fun testExportSettingsToNonUniqueNoteId() {
        // Given
        setupBook(
            "book1",
            """
                * Note 1
                :PROPERTIES:
                :ID: not-unique-value
                :END:

                content

                * Note 2
                :PROPERTIES:
                :ID: not-unique-value
                :END:

                content

           """.trimIndent()
        )
        assertEquals(2, dataRepository.getNotes("book1").size)
        AppPreferences.settingsExportAndImportNoteId(context, "not-unique-value")
        val targetNote = dataRepository.getNotes("book1")[0].note

        // Expect
        val exception = assertThrows(RuntimeException::class.java) {
            dataRepository.exportSettingsAndSearchesToNote(targetNote)
        }
        assertTrue(exception.message!!.contains("Found multiple"))

        // Verify no export happened
        assertEquals("content", dataRepository.getNotes("book1")[0].note.content)
        assertEquals("content", dataRepository.getNotes("book1")[1].note.content)
    }

    /**
     * Unknown keys in the JSON blob must be silently ignored during import
     * without causing issues.
     */
    @Test
    fun testImportSettingsWithInvalidEntries() {
        // Given
        val noteId = "my-export-note"
        setupBook(
            "book1",
            """
                * Note 1
                :PROPERTIES:
                :ID: $noteId
                :END:

                {"settings":{"pref_key_states":"NEXT | DONE","invalid_key":"invalid_value"},"saved_searches":{}}

           """.trimIndent()
        )
        val searchesBeforeImport = dataRepository.getSavedSearches()
        // Check that a setting has its default value
        assertEquals("TODO NEXT | DONE", AppPreferences.states(context))
        val sourceNote = dataRepository.getNotes("book1")[0].note

        // When
        dataRepository.importSettingsAndSearchesFromNote(sourceNote)

        // Expect the setting to have changed
        assertEquals("NEXT | DONE", AppPreferences.states(context))
        // Expect searches not to have changed
        assertEquals(searchesBeforeImport, dataRepository.getSavedSearches())
    }

    /**
     * An attempt to import completely invalid data must fail gracefully, with no changes.
     */
    @Test
    fun testImportInvalidSettingsData() {
        // Given
        val noteId = "my-export-note"
        setupBook(
            "book1",
            """
                * Note 1
                :PROPERTIES:
                :ID: $noteId
                :END:

                Sorry, I'm just a little note. I may even look a little bit like
                JSON. {"something":"nothing"}

           """.trimIndent()
        )
        val searchesBeforeImport = dataRepository.getSavedSearches()
        val settingsBeforeImport = Gson().toJson(AppPreferences.getDefaultPrefsAsJsonObject(context))
        val sourceNote = dataRepository.getNotes("book1")[0].note

        // Expect
        val exception = assertThrows(RuntimeException::class.java) {
            dataRepository.importSettingsAndSearchesFromNote(sourceNote)
        }
        assertTrue(exception.message!!.contains("valid JSON"))

        // Verify no changes
        assertEquals(searchesBeforeImport, dataRepository.getSavedSearches())
        assertEquals(settingsBeforeImport, Gson().toJson(AppPreferences.getDefaultPrefsAsJsonObject(context)))
    }

    /**
     * If the "settings" key is missing, no import should happen.
     */
    @Test
    fun testImportSettingsNoSettingsKey() {
        // Given
        val noteId = "my-export-note"
        setupBook(
            "book1",
            """
                * Note 1
                :PROPERTIES:
                :ID: $noteId
                :END:

                {"saved_searches":{"Agenda":".it.done ad.7"}}

           """.trimIndent()
        )
        val searchesBeforeImport = dataRepository.getSavedSearches()
        val settingsBeforeImport = Gson().toJson(AppPreferences.getDefaultPrefsAsJsonObject(context))
        val sourceNote = dataRepository.getNotes("book1")[0].note

        // Expect
        val exception = assertThrows(RuntimeException::class.java) {
            dataRepository.importSettingsAndSearchesFromNote(sourceNote)
        }
        assertTrue(exception.message!!.contains("missing mandatory fields"))

        // Verify no changes
        assertEquals(searchesBeforeImport, dataRepository.getSavedSearches())
        assertEquals(settingsBeforeImport, Gson().toJson(AppPreferences.getDefaultPrefsAsJsonObject(context)))
    }

    /**
     * If the "searches" key is missing, no import should happen.
     */
    @Test
    fun testImportSettingsNoSearchesKey() {
        // Given
        val noteId = "my-export-note"
        setupBook(
            "book1",
            """
                * Note 1
                :PROPERTIES:
                :ID: $noteId
                :END:

                {"settings":{"pref_key_states":"NEXT | DONE"}}

           """.trimIndent()
        )
        val searchesBeforeImport = dataRepository.getSavedSearches()
        val settingsBeforeImport = Gson().toJson(AppPreferences.getDefaultPrefsAsJsonObject(context))
        val sourceNote = dataRepository.getNotes("book1")[0].note

        // Expect
        val exception = assertThrows(RuntimeException::class.java) {
            dataRepository.importSettingsAndSearchesFromNote(sourceNote)
        }
        assertTrue(exception.message!!.contains("missing mandatory fields"))

        // Verify no changes
        assertEquals(searchesBeforeImport, dataRepository.getSavedSearches())
        assertEquals(settingsBeforeImport, Gson().toJson(AppPreferences.getDefaultPrefsAsJsonObject(context)))
    }

    /**
     * The "settings" and "saved_searches" keys must be present, but they may be empty.
     */
    @Test
    fun testImportSettingsWithSettingsDataWithoutSearchesData() {
        // Given
        val noteId = "my-export-note"
        setupBook(
            "book1", """
                * Note 1
                :PROPERTIES:
                :ID: $noteId
                :END:

                {"settings":{"pref_key_states":"NEXT | DONE"},"saved_searches":{}}

           """.trimIndent()
        )
        val searchesBeforeImport = dataRepository.getSavedSearches()
        // Check that the setting has the default value
        assertEquals("TODO NEXT | DONE", AppPreferences.states(context))
        val sourceNote = dataRepository.getNotes("book1")[0].note

        // When
        dataRepository.importSettingsAndSearchesFromNote(sourceNote)

        // Expect searches not to have changed
        assertEquals(searchesBeforeImport, dataRepository.getSavedSearches())
        // Expect settings to have changed
        assertEquals("NEXT | DONE", AppPreferences.states(context))
    }

    @Test
    fun testImportSettingsWithSearchesDataWithoutSettingsData() {
        // Given
        val noteId = "my-export-note"
        setupBook(
            "book1", """
                * Note 1
                :PROPERTIES:
                :ID: $noteId
                :END:

                {"settings":{},"saved_searches":{"Agenda":".it.done ad.7"}}

           """.trimIndent()
        )
        val searchesBeforeImport = dataRepository.getSavedSearches()
        assertEquals(0, searchesBeforeImport.size)
        // Store current settings
        val settingsBeforeImport = Gson().toJson(AppPreferences.getDefaultPrefsAsJsonObject(context))
        val sourceNote = dataRepository.getNotes("book1")[0].note

        // When
        dataRepository.importSettingsAndSearchesFromNote(sourceNote)

        // Then
        // Searches have changed
        assertEquals(1, dataRepository.getSavedSearches().size)
        // Settings have not changed
        assertEquals(settingsBeforeImport, Gson().toJson(AppPreferences.getDefaultPrefsAsJsonObject(context)))
    }

    @Test
    fun testImportSettingsValidJsonButNoData() {
        val noteId = "my-export-note"
        setupBook(
            "book1", """
                * Note 1
                :PROPERTIES:
                :ID: $noteId
                :END:

                {"settings":{},"saved_searches":{}}

           """.trimIndent()
        )
        val searchesBeforeImport = dataRepository.getSavedSearches()
        val settingsBeforeImport = Gson().toJson(AppPreferences.getDefaultPrefsAsJsonObject(context))
        val sourceNote = dataRepository.getNotes("book1")[0].note

        // Expect
        val exception = assertThrows(RuntimeException::class.java) {
            dataRepository.importSettingsAndSearchesFromNote(sourceNote)
        }
        assertTrue(exception.message!!.contains("Found no settings or saved searches to import"))

        // Verify no changes
        assertEquals(searchesBeforeImport, dataRepository.getSavedSearches())
        assertEquals(
            settingsBeforeImport,
            Gson().toJson(AppPreferences.getDefaultPrefsAsJsonObject(context))
        )
    }
}