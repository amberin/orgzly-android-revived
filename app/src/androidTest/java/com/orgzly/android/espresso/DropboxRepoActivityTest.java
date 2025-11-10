package com.orgzly.android.espresso;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.orgzly.android.espresso.util.EspressoUtils.onListItem;
import static com.orgzly.android.espresso.util.EspressoUtils.replaceTextCloseKeyboard;

import androidx.test.core.app.ActivityScenario;

import com.orgzly.BuildConfig;
import com.orgzly.R;
import com.orgzly.android.OrgzlyTest;
import com.orgzly.android.ui.repos.ReposActivity;

import org.junit.Assume;
import org.junit.Test;

public class DropboxRepoActivityTest extends OrgzlyTest {
    @Test
    public void testDropboxRepoWithPercentCharacter() {
        Assume.assumeTrue(BuildConfig.IS_DROPBOX_ENABLED);

        String localDir = "/Documents/user@host%2Fdir";

        ActivityScenario.launch(ReposActivity.class);

        onView(withId(R.id.activity_repos_flipper)).check(matches(isDisplayed()));
        onView(withId(R.id.activity_repos_dropbox)).perform(click());
        onView(withId(R.id.activity_repo_dropbox_directory)).perform(replaceTextCloseKeyboard(localDir));
        onView(withId(R.id.fab)).perform(click()); // Repo done
        onView(withId(R.id.activity_repos_flipper)).check(matches(isDisplayed()));

        onListItem(0).onChildView(withId(R.id.item_repo_url)).check(matches(withText("dropbox:/Documents/user%40host%252Fdir")));
        onListItem(0).perform(click());

        onView(withId(R.id.activity_repo_dropbox_directory)).check(matches(withText(localDir)));
    }
}
