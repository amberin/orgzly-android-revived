package com.orgzly.android.espresso;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;

import com.adevinta.android.barista.rule.BaristaRule;
import com.orgzly.R;
import com.orgzly.android.OrgzlyTest;
import com.orgzly.android.ui.main.MainActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.contrib.DrawerActions.open;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.orgzly.android.espresso.util.EspressoUtils.onNoteInBook;
import static com.orgzly.android.espresso.util.EspressoUtils.onNoteInSearch;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.not;

public class ActionModeTest extends OrgzlyTest {

    @Rule
    public BaristaRule<MainActivity> baristaRule = BaristaRule.create(MainActivity.class);

    @Before
    public void setUp() throws Exception {
        super.setUp();

        testUtils.setupBook("book-one",
                "First book used for testing\n" +
                "* Note A.\n" +
                "** Note B.\n" +
                "* TODO Note C.\n" +
                "SCHEDULED: <2014-01-01>\n" +
                "** Note D.\n" +
                "*** TODO Note E.\n" +
                "");

        testUtils.setupBook("book-two",
                "Sample book used for tests\n" +
                "* Note #1.\n" +
                "* Note #2.\n" +
                "** TODO Note #3.\n" +
                "** Note #4.\n" +
                "*** DONE Note #5.\n" +
                "CLOSED: [2014-06-03 Tue 13:34]\n" +
                "**** Note #6.\n" +
                "** Note #7.\n" +
                "* DONE Note #8.\n" +
                "CLOSED: [2014-06-03 Tue 3:34]\n" +
                "**** Note #9.\n" +
                "SCHEDULED: <2014-05-26 Mon>\n" +
                "** Note #10.\n" +
                "");

        baristaRule.launchActivity();

        onView(allOf(withText("book-one"), isDisplayed())).perform(click());
    }

    @Test
    public void testQueryFragmentCabShouldBeOpenedOnNoteLongClick() {
        onView(withId(R.id.drawer_layout)).perform(open());
        onView(allOf(withText("Scheduled"), isDescendantOfA(withId(R.id.drawer_navigation_view)))).perform(click());

        onNoteInSearch(1).perform(longClick());
    }

    @Test
    public void testCabStaysOpenOnRotation() {
        Activity activity = baristaRule.getActivityTestRule().getActivity();
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        onNoteInBook(3).perform(longClick());

        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        onView(withId(R.id.toggle_state)).check(matches(isDisplayed()));

        // TODO: Check *the same* note is selected.
    }

    @Test
    public void testCabStaysOpenOnRotationInQueryFragment() {
        Activity activity = baristaRule.getActivityTestRule().getActivity();
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        onView(withId(R.id.drawer_layout)).perform(open());
        onView(withText("Scheduled")).perform(click());

        onNoteInSearch(1).perform(longClick());

        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        // TODO: Check *the same* note is selected.

        SystemClock.sleep(500);
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        onView(withId(R.id.toggle_state)).check(matches(isDisplayed()));
    }

    @Test
    public void testBackPressClosesDrawer() {
        onView(withId(R.id.drawer_layout)).perform(open());
        onView(withId(R.id.drawer_navigation_view)).check(matches(isDisplayed()));
        pressBack();
        onView(withId(R.id.drawer_navigation_view)).check(matches(not(isDisplayed())));
    }
}
