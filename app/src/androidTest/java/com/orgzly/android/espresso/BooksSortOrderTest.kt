package com.orgzly.android.espresso

import androidx.annotation.StringRes
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.orgzly.R
import com.orgzly.android.OrgzlyTest
import com.orgzly.android.espresso.util.EspressoUtils.clickSetting
import com.orgzly.android.espresso.util.EspressoUtils.onActionItemClick
import com.orgzly.android.espresso.util.EspressoUtils.onBook
import com.orgzly.android.espresso.util.EspressoUtils.onNoteInBook
import com.orgzly.android.ui.main.MainActivity
import org.hamcrest.Matchers.hasToString
import org.junit.After
import org.junit.Before
import org.junit.Test


class BooksSortOrderTest : OrgzlyTest() {

    private var scenario: ActivityScenario<MainActivity?>? = null

    @Before
    @Throws(Exception::class)
    override fun setUp() {
        super.setUp()

        testUtils.setupBook("Book A", "* Note A-01")
        testUtils.setupBook("Book B", "* Note B-01")

        scenario = ActivityScenario.launch(MainActivity::class.java)
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
    fun books_sortOrder() {
        onBook(0, R.id.item_book_title).check(matches(withText("Book A")))
        onBook(1, R.id.item_book_title).check(matches(withText("Book B")))
    }

    @Test
    fun books_sortOrderAfterSettingsChange() {
        modifySecondBook()
        setBooksSortOrder(R.string.notebooks_sort_order_modification_time)
        onBook(0, R.id.item_book_title).check(matches(withText("Book B")))
        onBook(1, R.id.item_book_title).check(matches(withText("Book A")))
    }

//    @Test
//    fun drawer_sortOrder() {
//        onView(withId(R.id.drawer_layout)).perform(open())
//        onItemInDrawer()
//    }

    private fun modifySecondBook() {
        // Modify book
        onBook(1).perform(click())
        onNoteInBook(1).perform(longClick())
        onView(withId(R.id.toggle_state)).perform(click())
        pressBack()
        pressBack()
    }

    private fun setBooksSortOrder(@StringRes id: Int) {
        onActionItemClick(R.id.activity_action_settings, R.string.settings)
        clickSetting(R.string.pref_title_notebooks)
        clickSetting(R.string.sort_order)
        onData(hasToString(context.getString(id))).perform(click())
        pressBack()
        pressBack()
    }
}
