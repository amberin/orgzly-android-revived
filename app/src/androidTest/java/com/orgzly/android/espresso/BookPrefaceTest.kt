package com.orgzly.android.espresso

import androidx.annotation.StringRes
import androidx.core.content.ContextCompat.getString
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.orgzly.R
import com.orgzly.android.OrgzlyTest
import com.orgzly.android.espresso.util.EspressoUtils.clickSetting
import com.orgzly.android.espresso.util.EspressoUtils.onBook
import com.orgzly.android.espresso.util.EspressoUtils.onPreface
import com.orgzly.android.espresso.util.EspressoUtils.replaceTextCloseKeyboard
import com.orgzly.android.ui.main.MainActivity
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Before
import org.junit.Test

class BookPrefaceTest : OrgzlyTest() {

    private var scenario: ActivityScenario<MainActivity?>? = null

    @Before
    @Throws(Exception::class)
    override fun setUp() {
        super.setUp()

        testUtils.setupBook(
                "book-name",
                """
                    Line 1
                    Line 2
                    Line 3
                    Line 4
                    Line 5

                    * Note #1.
                    * Note #2.
                    ** TODO Note #3.
                """.trimIndent())

        scenario = ActivityScenario.launch(MainActivity::class.java)

        onBook(0).perform(click())
    }

    @After
    @Throws(java.lang.Exception::class)
    override fun tearDown() {
        super.tearDown()
        if (scenario != null) {
            scenario!!.close()
        }
    }

    @Test
    fun testOpensBookDescription() {
        onPreface().perform(click())
        onView(withId(R.id.fragment_book_preface_container)).check(matches(isDisplayed()))
    }

    @Test
    fun testUpdatingBookPreface() {
        onPreface().perform(click())
        onView(withId(R.id.fragment_book_preface_content)).perform(click())
        onView(withId(R.id.fragment_book_preface_content_edit)).perform(*replaceTextCloseKeyboard("New content"))
        onView(withId(R.id.done)).perform(click()) // Preface done
        onPreface().perform(click())
        onView(withId(R.id.fragment_book_preface_content_view)).check(matches(withText("New content")))
    }

    @Test
    fun testDeleteBookPreface() {
        val context = getInstrumentation().targetContext

        // Preface is displayed
        onPreface().check(matches(isDisplayed()))

        // Open preface and delete it
        onPreface().perform(click())
        openActionBarOverflowOrOptionsMenu(context)
        onView(allOf(withText(getString(context, R.string.delete)), isDisplayed())).perform(click())

        // Preface is not displayed anymore
        onPreface().check(matches(not(isDisplayed())))
    }

    @Test
    fun testPrefaceFullDisplayed() {
        setPrefaceSetting(R.string.preface_in_book_full)
        onPreface(R.id.item_preface_text_view).check(matches(withText("Line 1\nLine 2\nLine 3\nLine 4\nLine 5")))
        // onView(withText("Line 1\nLine 2\nLine 3\nLine 4\nLine 5")).check(matches(isDisplayed()))
    }

    @Test
    fun testPrefaceHiddenNotDisplayed() {
        setPrefaceSetting(R.string.preface_in_book_hide)
        onPreface().check(matches(not(isDisplayed())))
    }

    private fun setPrefaceSetting(@StringRes id: Int) {
        openActionBarOverflowOrOptionsMenu(getInstrumentation().targetContext)
        onView(allOf(withText(getString(getInstrumentation().targetContext, R.string.settings)), isDisplayed())).perform(click())

        clickSetting(R.string.pref_title_notebooks)
        clickSetting(R.string.preface_in_book)

        onView(withText(id)).perform(click())

        pressBack()
        pressBack()
    }
}
