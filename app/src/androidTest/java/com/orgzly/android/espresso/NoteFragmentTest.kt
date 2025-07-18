package com.orgzly.android.espresso

import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.widget.DatePicker
import android.widget.TimePicker
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.*
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.PickerActions.setDate
import androidx.test.espresso.contrib.PickerActions.setTime
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import com.orgzly.R
import com.orgzly.android.OrgzlyTest
import com.orgzly.android.RetryTestRule
import com.orgzly.android.espresso.util.EspressoUtils.*
import com.orgzly.android.ui.main.MainActivity
import org.hamcrest.Matchers.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NoteFragmentTest : OrgzlyTest() {
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Rule
    @JvmField
    val mRetryTestRule = RetryTestRule()

    @Before
    @Throws(Exception::class)
    override fun setUp() {
        super.setUp()

        testUtils.setupBook(
                "book-name",
                """
                    Sample book used for tests

                    * Note #1.

                    * Note #2.
                    SCHEDULED: <2014-05-22 Thu> DEADLINE: <2014-05-22 Thu>

                    ** TODO Note #3.

                    ** Note #4.
                    SCHEDULED: <2015-01-11 Sun .+1d/2d>

                    *** DONE Note #5.
                    CLOSED: [2014-01-01 Wed 20:07]

                    **** Note #6.

                    ** Note #7.

                    * ANTIVIVISECTIONISTS Note #8.

                    **** Note #9.

                    ** Note #10.
                    :PROPERTIES:
                    :CREATED:  [2019-10-04 Fri 10:23]
                    :END:

                """.trimIndent())

        scenario = ActivityScenario.launch(MainActivity::class.java)

        onBook(0).perform(click())
    }

    @After
    override fun tearDown() {
        super.tearDown()
        scenario.close()
    }

    @Test
    fun testDeleteNote() {
        onNoteInBook(1).perform(click())

        onView(withId(R.id.view_flipper)).check(matches(isDisplayed()))

        openActionBarOverflowOrOptionsMenu(context)
        onView(withText(R.string.delete)).perform(click())
        onView(withText(R.string.delete)).perform(click())

        onView(withId(R.id.fragment_book_view_flipper)).check(matches(isDisplayed()))

        onSnackbar().check(matches(withText(
                context.resources.getQuantityString(R.plurals.notes_deleted, 1, 1))))
    }

    @Test
    fun testUpdateNoteTitle() {
        onNoteInBook(1, R.id.item_head_title_view).check(matches(withText("Note #1.")))

        onNoteInBook(1).perform(click())

        onView(withId(R.id.title)).perform(click())
        onView(withId(R.id.title_edit)).perform(*replaceTextCloseKeyboard("Note title changed"))

        onView(withId(R.id.done)).perform(click()); // Note done

        onNoteInBook(1, R.id.item_head_title_view).check(matches(withText("Note title changed")))
    }

    @Test
    fun testSettingScheduleTime() {
        onNoteInBook(1).perform(click())
        onView(withId(R.id.scheduled_button)).check(matches(withText("")))
        onView(withId(R.id.scheduled_button)).perform(click())
        onView(withText(R.string.set)).perform(click())
        onView(withId(R.id.scheduled_button))
                .check(matches(withText(startsWith(defaultDialogUserDate()))))
    }

    @Test
    fun testAbortingOfSettingScheduledTime() {
        onNoteInBook(1).perform(click())
        onView(withId(R.id.scheduled_button)).check(matches(withText("")))
        onView(withId(R.id.scheduled_button)).perform(click())
        pressBack()
        onView(withId(R.id.scheduled_button)).check(matches(withText("")))
    }

    @Test
    fun testRemovingScheduledTime() {
        onNoteInBook(2).perform(click())
        onView(withId(R.id.scheduled_button)).check(matches(not(withText(""))))
        onView(withId(R.id.scheduled_button)).perform(click())
        onView(withText(R.string.clear)).perform(click())
        onView(withId(R.id.scheduled_button)).check(matches(withText("")))
    }

    @Test
    fun testRemovingScheduledTimeAndOpeningTimestampDialogAgain() {
        onNoteInBook(2).perform(click())
        onView(withId(R.id.scheduled_button)).check(matches(not(withText(""))))
        onView(withId(R.id.scheduled_button)).perform(click())
        onView(withText(R.string.clear)).perform(click())
        onView(withId(R.id.scheduled_button)).check(matches(withText("")))
        onView(withId(R.id.scheduled_button)).perform(click())
    }

    @Test
    fun testSettingDeadlineTime() {
        onNoteInBook(1).perform(click())
        onView(withId(R.id.deadline_button)).check(matches(withText("")))
        onView(withId(R.id.deadline_button)).perform(click())
        onView(withText(R.string.set)).perform(click())
        onView(withId(R.id.deadline_button))
                .check(matches(allOf(withText(startsWith(defaultDialogUserDate())), isDisplayed())))
    }

    @Test
    fun testAbortingOfSettingDeadlineTime() {
        onNoteInBook(1).perform(click())
        onView(withId(R.id.deadline_button)).check(matches(withText("")))
        onView(withId(R.id.deadline_button)).perform(click())
        pressBack()
        onView(withId(R.id.deadline_button)).check(matches(withText("")))
    }

    @Test
    fun testRemovingDeadlineTime() {
        onNoteInBook(2).perform(click())
        onView(withId(R.id.deadline_button)).check(matches(not(withText(""))))
        onView(withId(R.id.deadline_button)).perform(click())
        onView(withText(R.string.clear)).perform(click())
        onView(withId(R.id.deadline_button)).check(matches(withText("")))
    }

    @Test
    fun testStateToDoneShouldAddClosedTime() {
        onNoteInBook(2).perform(click())

        onView(withId(R.id.closed_button)).check(matches(not(isDisplayed())))
        onView(withId(R.id.state_button)).perform(click())
        onView(withText("DONE")).perform(click())
        onView(withId(R.id.closed_button))
                .check(matches(allOf(withText(startsWith(currentUserDate())), isDisplayed())))
    }

    @Test
    fun testStateToDoneShouldOverwriteLastRepeat() {
        onNoteInBook(4).perform(click())

        onView(withId(R.id.state_button)).perform(click())
        onView(withText("DONE")).perform(click())

        onView(withId(R.id.state_button)).perform(click())
        onView(withText("DONE")).perform(click())

        // This will fail if there are two or more LAST_REPEAT properties
        onView(allOf(withId(R.id.name), withText("LAST_REPEAT"))).check(matches(isDisplayed()))
    }

    @Test
    fun testStateToDoneForNoteShouldShiftTime() {
        onNoteInBook(4).perform(click())

        onView(withId(R.id.state_button)).check(matches(withText("")))
        onView(withId(R.id.scheduled_button))
                .check(matches(allOf(withText(userDateTime("<2015-01-11 Sun .+1d/2d>")), isDisplayed())))
        onView(withId(R.id.closed_button)).check(matches(not(isDisplayed())))

        onView(withId(R.id.state_button)).perform(click())
        onView(withText("DONE")).perform(click())

        onView(withId(R.id.state_button)).check(matches(withText("")))
        onView(withId(R.id.scheduled_button))
                .check(matches(not(withText(userDateTime("<2015-01-11 Sun .+1d/2d>")))))
        onView(withId(R.id.closed_button)).check(matches(not(isDisplayed())))
    }

    @Test
    fun testChangingStateSettingsFromNoteFragment() {
        onNoteInBook(1).perform(click())
        settingsSetTodoKeywords("")
        onView(withId(R.id.state_button)).perform(click())
        onListView().check(matches(listViewItemCount(1))) // Only DONE
        pressBack()
        settingsSetTodoKeywords("TODO")
        onView(withId(R.id.state_button)).perform(click())
        onListView().check(matches(listViewItemCount(2)))
    }

    @Test
    fun testTitleCanNotBeEmptyForNewNote() {
        onView(withId(R.id.fab)).perform(click()) // New note
        onView(withId(R.id.done)).perform(click()); // Note done
        onSnackbar().check(matches(withText(R.string.title_can_not_be_empty)))
    }

    @Test
    fun testTitleCanNotBeEmptyForExistingNote() {
        onNoteInBook(1).perform(click())
        onView(withId(R.id.title)).perform(click())
        onView(withId(R.id.title_edit)).perform(*replaceTextCloseKeyboard(""))
        onView(withId(R.id.done)).perform(click()); // Note done
        onSnackbar().check(matches(withText(R.string.title_can_not_be_empty)))
    }

    @Test
    fun testSavingNoteWithRepeater() {
        onNoteInBook(4).perform(click())
        onView(withId(R.id.done)).perform(click()); // Note done
    }

    @Test
    fun testClosedTimeInNoteFragmentIsSameAsInList() {
        onNoteInBook(5).perform(click())
        onView(withId(R.id.closed_button))
                .check(matches(allOf(withText(userDateTime("[2014-01-01 Wed 20:07]")), isDisplayed())))
    }

    @Test
    fun testSettingStateRemainsSetAfterRotation() {
        scenario.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        onNoteInBook(1).perform(click())
        onView(withId(R.id.state_button)).perform(click())
        onView(withText("TODO")).perform(click())
        onView(withText("TODO")).check(matches(isDisplayed()))

        scenario.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        onView(withText("TODO")).check(matches(isDisplayed()))
    }

    @Test
    fun testSettingPriorityRemainsSetAfterRotation() {
        scenario.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        onNoteInBook(1).perform(click())
        onView(withId(R.id.priority_button)).perform(click())
        onView(withText("B")).perform(click())
        onView(withId(R.id.priority_button)).check(matches(withText("B")))

        scenario.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        onView(withId(R.id.priority_button)).check(matches(withText("B")))
    }

    @Test
    fun testSettingScheduledTimeRemainsSetAfterRotation() {
        scenario.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        onNoteInBook(1).perform(click())
        onView(withId(R.id.scheduled_button)).check(matches(withText("")))
        onView(withId(R.id.scheduled_button)).perform(click())
        onView(withText(R.string.set)).perform(click())
        onView(withId(R.id.scheduled_button))
                .check(matches(withText(startsWith(defaultDialogUserDate()))))

        scenario.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        onView(withId(R.id.scheduled_button))
                .check(matches(withText(startsWith(defaultDialogUserDate()))))
    }

    @Test
    fun testSetScheduledTimeAfterRotation() {
        onNoteInBook(1).perform(click())

        scenario.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        onView(withId(R.id.scheduled_button)).check(matches(withText("")))
        onView(withId(R.id.scheduled_button)).perform(click())

        scenario.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        onView(withText(R.string.set)).perform(closeSoftKeyboardWithDelay(), click())
        onView(withId(R.id.scheduled_button))
                .check(matches(withText(startsWith(defaultDialogUserDate()))))
    }

    @Test
    fun testRemovingDoneStateRemovesClosedTime() {
        onNoteInBook(5).perform(click())
        onView(withId(R.id.closed_button))
                .check(matches(allOf(withText(userDateTime("[2014-01-01 Wed 20:07]")), isDisplayed())))
        onView(withId(R.id.state_button)).perform(click())
        onView(withText(R.string.clear)).perform(click())
        SystemClock.sleep(500)
        onView(withId(R.id.closed_button)).check(matches(not(isDisplayed())))
    }

    @Test
    fun testSettingPmTimeDisplays24HourTime() {
        onNoteInBook(1).perform(click())

        onView(withId(R.id.deadline_button)).check(matches(withText("")))
        onView(withId(R.id.deadline_button)).perform(click())

        /* Set date. */
        onView(withId(R.id.date_picker_button)).perform(click())
        onView(withClassName(equalTo(DatePicker::class.java.name))).perform(setDate(2014, 4, 1))
        onView(withText(android.R.string.ok)).perform(click())

        /* Set time. */
        onView(withId(R.id.time_picker_button)).perform(scroll(), click())
        onView(withClassName(equalTo(TimePicker::class.java.name))).perform(setTime(15, 15))
        onView(withText(android.R.string.ok)).perform(click())

        onView(withText(R.string.set)).perform(click())

        onView(withId(R.id.deadline_button))
                .check(matches(withText(userDateTime("<2014-04-01 Tue 15:15>"))))
    }

    @Test
    fun testDateTimePickerKeepsValuesAfterRotation() {
        onNoteInBook(1).perform(click())

        onView(withId(R.id.deadline_button)).check(matches(withText("")))

        scenario.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        onView(withId(R.id.deadline_button)).perform(click())

        /* Set date. */
        onView(withId(R.id.date_picker_button)).perform(click())
        onView(withClassName(equalTo(DatePicker::class.java.name))).perform(setDate(2014, 4, 1))
        onView(withText(android.R.string.ok)).perform(click())

        /* Set time. */
        onView(withId(R.id.time_picker_button)).perform(scroll(), click())
        onView(withClassName(equalTo(TimePicker::class.java.name))).perform(setTime(9, 15))
        onView(withText(android.R.string.ok)).perform(click())

        /* Set repeater. */
        onView(withId(R.id.repeater_used_checkbox)).perform(scroll(), click())
        onView(withId(R.id.repeater_picker_button)).perform(scroll(), click())
        onView(withId(R.id.value_picker)).perform(setNumber(3))
        onView(withText(R.string.ok)).perform(click())

        /* Rotate screen. */
        scenario.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        SystemClock.sleep(500) // Give AVD time to complete rotation

        /* Set time. */
        onView(withText(R.string.set)).perform(click())

        onView(withId(R.id.deadline_button))
                .check(matches(withText(userDateTime("<2014-04-01 Tue 09:15 .+3w>"))))
    }

    @Test
    fun testChangingPrioritySettingsFromNoteFragment() {
        /* Open note which has no priority set. */
        onNoteInBook(1).perform(click())

        /* Change lowest priority to A. */
        onActionItemClick(R.id.activity_action_settings, R.string.settings)
        clickSetting(R.string.pref_title_notebooks)
        clickSetting(R.string.lowest_priority)
        onData(hasToString(containsString("A"))).perform(click())
        pressBack()
        pressBack()

        onView(withId(R.id.priority_button)).perform(click())
        onListView().check(matches(listViewItemCount(1)))
        pressBack()

        /* Change lowest priority to C. */
        onActionItemClick(R.id.activity_action_settings, R.string.settings)
        clickSetting(R.string.pref_title_notebooks)
        clickSetting(R.string.lowest_priority)
        onData(hasToString(containsString("C"))).perform(click())
        pressBack()
        pressBack()

        onView(withId(R.id.priority_button)).perform(click())
        onListView().check(matches(listViewItemCount(3)))
    }

    @Test
    fun testPropertiesAfterRotatingDevice() {
        onNoteInBook(1).perform(click())

        onView(withId(R.id.scroll_view)).perform(swipeUp()) // For small screens

        onView(withId(R.id.name))
                .perform(replaceText("prop-name-1"))
        onView(allOf(withId(R.id.value), hasSibling(withText("prop-name-1"))))
                .perform(*replaceTextCloseKeyboard("prop-value-1"))

        onView(allOf(withId(R.id.name), not(withText("prop-name-1"))))
                .perform(replaceText("prop-name-2"))
        onView(allOf(withId(R.id.value), hasSibling(withText("prop-name-2"))))
                .perform(*replaceTextCloseKeyboard("prop-value-2"))

        scenario.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        onView(withId(R.id.scroll_view)).perform(swipeUp()) // For small screens
        SystemClock.sleep(500)
        
        onView(allOf(withId(R.id.name), withText("prop-name-1"))).check(matches(isDisplayed()))
        onView(allOf(withId(R.id.value), withText("prop-value-1"))).check(matches(isDisplayed()))
        onView(allOf(withId(R.id.name), withText("prop-name-2"))).check(matches(isDisplayed()))
        onView(allOf(withId(R.id.value), withText("prop-value-2"))).check(matches(isDisplayed()))
    }

    @Test
    fun testSavingProperties() {
        onNoteInBook(1).perform(click())

        onView(withId(R.id.name))
                .perform(replaceText("prop-name-1"))
        onView(allOf(withId(R.id.value), hasSibling(withText("prop-name-1"))))
                .perform(*replaceTextCloseKeyboard("prop-value-1"))

        onView(allOf(withId(R.id.name), withText("prop-name-1"))).check(matches(isDisplayed()))
        onView(allOf(withId(R.id.value), withText("prop-value-1"))).check(matches(isDisplayed()))

        onView(withId(R.id.done)).perform(click()); // Note done

        onNoteInBook(1).perform(click())

        onView(allOf(withId(R.id.name), withText("prop-name-1"))).check(matches(isDisplayed()))
        onView(allOf(withId(R.id.value), withText("prop-value-1"))).check(matches(isDisplayed()))
    }

    @Test
    fun testContentLineCountUpdatedOnNoteUpdate() {
        onNoteInBook(1).perform(click())
        onView(withId(R.id.content)).perform(scroll()) // For smaller screens
        onView(withId(R.id.content)).perform(click())
        onView(withId(R.id.content_edit)).perform(typeTextIntoFocusedView("a\nb\nc"))
        onView(withId(R.id.done)).perform(click()) // Note done
        SystemClock.sleep(1000)
        onNoteInBook(1, R.id.item_head_fold_button).perform(click())
        onNoteInBook(1, R.id.item_head_title_view).check(matches(withText(endsWith("3"))))
    }

    @Test
    fun testBreadcrumbsFollowToBook() {
        onNoteInBook(3).perform(click())

        // onView(withId(R.id.breadcrumbs_text)).perform(clickClickableSpan("book-name"));
        // SystemClock.sleep(5000);

        onView(withId(R.id.breadcrumbs_text)).perform(click())

        onView(withId(R.id.fragment_book_view_flipper)).check(matches(isDisplayed()))
    }

    @Test
    fun testBreadcrumbsFollowToNote() {
        onNoteInBook(3).perform(click())
        onView(withId(R.id.breadcrumbs_text)).perform(clickClickableSpan("Note #2."))
        onView(withId(R.id.title_view)).check(matches(withText("Note #2.")))
    }

    @Test
    fun testBreadcrumbsPromptWhenCreatingNewNote() {
        onNoteInBook(1).perform(longClick())
        onActionItemClick(R.id.new_note, R.string.new_note)
        onView(withText(R.string.new_under)).perform(click())
        onView(withId(R.id.title_edit)).perform(*replaceTextCloseKeyboard("1.1"))
        onView(withId(R.id.breadcrumbs_text)).perform(clickClickableSpan("Note #1."))

        // Dialog is displayed
        onView(withText(R.string.discard_or_save_changes))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))

        SystemClock.sleep(500) // If we click too early, the button doesn't yet work...
        onView(withText(R.string.cancel)).perform(click())

        // Title remains the same
        onView(withId(R.id.title_edit)).check(matches(withText("1.1")))
    }

    // https://github.com/orgzly/orgzly-android/issues/605
    @Test
    fun testMetadataShowSelectedOnNoteLoad() {
        onNoteInBook(10).perform(click())
        onView(withText("CREATED")).check(matches(isDisplayed()))
        openActionBarOverflowOrOptionsMenu(context)
        onView(withText(R.string.metadata)).perform(click())
        onView(withText(R.string.show_selected)).perform(click())
        onView(withText("CREATED")).check(matches(isDisplayed()))
        pressBack()
        onNoteInBook(10).perform(click())
        onView(withText("CREATED")).check(matches(isDisplayed()))
    }

    @Test
    fun testDoNotPromptAfterLeavingNewNoteUnmodified() {
        onView(withId(R.id.fab)).perform(click())
        pressBack() // Close keyboard
        pressBack() // Leave note

        onView(withId(R.id.fragment_book_view_flipper)).check(matches(isDisplayed()))
    }
}
