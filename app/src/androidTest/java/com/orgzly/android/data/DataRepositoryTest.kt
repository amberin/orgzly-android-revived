package com.orgzly.android.data

import com.google.gson.Gson
import com.orgzly.android.OrgzlyTest
import com.orgzly.android.prefs.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataRepositoryTest : OrgzlyTest() {

    /**
     * If the user attempts to export app settings to a note with a non-unique "ID" value, then
     * - a runtime exception should be thrown
     * - no export should happen
     */
    @Test(expected = RuntimeException::class)
    fun testExportSettingsToNonUniqueNoteId() {
        testUtils.setupBook(
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
        try {
            dataRepository.exportSettingsAndSearchesToSelectedNote()
        } catch (e: java.lang.RuntimeException) {
            assertTrue(e.message!!.contains("Found multiple"))
            throw e
        } finally {
            assertEquals("content", dataRepository.getNotes("book1")[0].note.content)
            assertEquals("content", dataRepository.getNotes("book1")[1].note.content)
        }
    }

    /**
     * If the user attempts to import app settings from a note with a non-unique "ID" value, then
     * - a runtime exception should be thrown
     * - no import should happen
     */
    @Test(expected = RuntimeException::class)
    fun testImportSettingsFromNonUniqueNoteId() {
        // Assert that a setting is in its default state
        assertEquals("TODO", AppPreferences.getFirstTodoState(context))
        // Create book with exported settings JSON
        testUtils.setupBook(
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
                
                {"settings":{"pref_key_states":"NEXT | DONE"},"saved_searches":{}}
                
           """.trimIndent()
        )
        assertEquals(2, dataRepository.getNotes("book1").size)
        AppPreferences.settingsExportAndImportNoteId(context, "not-unique-value")
        try {
            dataRepository.importSettingsAndSearchesFromSelectedNote()
        } catch (e: java.lang.RuntimeException) {
            assertTrue(e.message!!.contains("Found multiple"))
            throw e
        } finally {
            // Check that the setting was not changed
            assertEquals("TODO", AppPreferences.getFirstTodoState(context))
        }
    }

    /**
     * Unknown keys in the JSON blob must be silently ignored during import
     * without causing issues.
     */
    @Test
    fun testImportSettingsWithInvalidEntries() {
        // Given
        testUtils.setupBook(
            "book1",
            """
                * Note 1
                :PROPERTIES:
                :ID: myexportnote
                :END:

                {"settings":{"pref_key_states":"NEXT | DONE","invalid_key":"invalid_value"},"saved_searches":{}}

           """.trimIndent()
        )
        AppPreferences.settingsExportAndImportNoteId(context, "myexportnote")
        // Check that a setting has its default value
        assertEquals("TODO NEXT | DONE", AppPreferences.states(context))

        // When
        dataRepository.importSettingsAndSearchesFromSelectedNote()

        // Then
        // Check that the setting was changed
        assertEquals("NEXT | DONE", AppPreferences.states(context))
    }

    /**
     * An attempt to import completely invalid data must fail gracefully.
     */
    @Test
    fun testImportInvalidSettingsData() {
        // Given
        testUtils.setupBook(
            "book1",
            """
                * Note 1
                :PROPERTIES:
                :ID: myexportnote
                :END:

                Sorry, I'm just a little note. I may even look a little bit like
                JSON. {"something":"nothing"}

           """.trimIndent()
        )
        AppPreferences.settingsExportAndImportNoteId(context, "myexportnote")

        // Expect
        assertEquals(false, dataRepository.importSettingsAndSearchesFromSelectedNote())
    }

    /**
     * If any of the "settings" or "saved_searches" keys is missing, no import should happen.
     */
    @Test
    fun testImportSettingsOneKeyMissing() {
        // Given
        testUtils.setupBook(
            "book1",
            """
                * Note 1
                :PROPERTIES:
                :ID: settingsnote1
                :END:

                {"settings":{"pref_key_states":"NEXT | DONE"}}

           """.trimIndent()
        )
        testUtils.setupBook(
            "book2",
            """
                * Note 2
                :PROPERTIES:
                :ID: settingsnote2
                :END:

                {"saved_searches":{"Agenda":".it.done ad.7"}}

           """.trimIndent()
        )

        // When
        AppPreferences.settingsExportAndImportNoteId(context, "exportnote1")
        // Expect
        assertEquals(false, dataRepository.importSettingsAndSearchesFromSelectedNote())
        // When
        AppPreferences.settingsExportAndImportNoteId(context, "exportnote2")
        // Expect
        assertEquals(false, dataRepository.importSettingsAndSearchesFromSelectedNote())
    }

    /**
     * Either of the "settings" or "saved_searches" keys may be empty.
     */

    @Test
    fun testImportSettingsWithSettingsDataWithoutSearchesData() {
        // Given
        testUtils.setupBook(
            "book1", """
                * Note 1
                :PROPERTIES:
                :ID: myexportnote
                :END:

                {"settings":{"pref_key_states":"NEXT | DONE"},"saved_searches":{}}

           """.trimIndent()
        )
        AppPreferences.settingsExportAndImportNoteId(context, "myexportnote")
        // Check that searches and settings are the defaults
        val searches = dataRepository.getSavedSearches()
        assertEquals(4, searches.size)
        assertEquals("TODO NEXT | DONE", AppPreferences.states(context))

        // Expect
        // Import should happen
        assertEquals(true, dataRepository.importSettingsAndSearchesFromSelectedNote())
        // Searches have not changed
        assertEquals(searches, dataRepository.getSavedSearches())
        // Settings have changed
        assertEquals("NEXT | DONE", AppPreferences.states(context))
    }

    @Test
    fun testImportSettingsWithSearchesDataWithoutSettingsData() {
        // Given
        testUtils.setupBook(
            "book1", """
                * Note 1
                :PROPERTIES:
                :ID: myexportnote
                :END:

                {"settings":{},"saved_searches":{"Agenda":".it.done ad.7"}}

           """.trimIndent()
        )
        AppPreferences.settingsExportAndImportNoteId(context, "myexportnote")
        // Store searches
        val searches = dataRepository.getSavedSearches()
        // Assert default number of searches
        assertEquals(4, searches.size)
        // Store settings
        val settings = Gson().toJson(AppPreferences.getDefaultPrefsAsJsonObject(context))

        // Expect
        // Import should happen
        assertEquals(true, dataRepository.importSettingsAndSearchesFromSelectedNote())
        // Searches have changed
        assertEquals(1, dataRepository.getSavedSearches().size)
        // Settings have not changed
        assertEquals(settings, Gson().toJson(AppPreferences.getDefaultPrefsAsJsonObject(context)))
    }

    @Test
    fun testImportSettingsValidJsonButNoData() {
        testUtils.setupBook(
            "book1", """
                * Note 1
                :PROPERTIES:
                :ID: myexportnote
                :END:

                {"settings":{},"saved_searches":{}}

           """.trimIndent()
        )
        AppPreferences.settingsExportAndImportNoteId(context, "myexportnote")
        // Store searches
        val searches = dataRepository.getSavedSearches()
        // Store settings
        val settings = Gson().toJson(AppPreferences.getDefaultPrefsAsJsonObject(context))

        // Expect
        // Import should not happen
        assertEquals(false, dataRepository.importSettingsAndSearchesFromSelectedNote())
        // Searches have not changed
        assertEquals(searches, dataRepository.getSavedSearches())
        // Settings have not changed
        assertEquals(settings, Gson().toJson(AppPreferences.getDefaultPrefsAsJsonObject(context)))
    }
}
