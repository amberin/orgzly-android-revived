package com.orgzly.android.espresso;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.contrib.DrawerActions.open;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;
import static androidx.test.espresso.matcher.ViewMatchers.hasChildCount;
import static androidx.test.espresso.matcher.ViewMatchers.isChecked;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.isNotChecked;
import static androidx.test.espresso.matcher.ViewMatchers.withClassName;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.orgzly.android.espresso.util.EspressoUtils.contextualToolbarOverflowMenu;
import static com.orgzly.android.espresso.util.EspressoUtils.onActionItemClick;
import static com.orgzly.android.espresso.util.EspressoUtils.onBook;
import static com.orgzly.android.espresso.util.EspressoUtils.onNoteInBook;
import static com.orgzly.android.espresso.util.EspressoUtils.onSnackbar;
import static com.orgzly.android.espresso.util.EspressoUtils.replaceTextCloseKeyboard;
import static com.orgzly.android.espresso.util.EspressoUtils.sync;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;
import android.content.Intent;
import android.os.SystemClock;

import androidx.documentfile.provider.DocumentFile;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import com.orgzly.R;
import com.orgzly.android.BookFormat;
import com.orgzly.android.OrgzlyTest;
import com.orgzly.android.repos.RepoType;
import com.orgzly.android.ui.main.MainActivity;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class BooksTest extends OrgzlyTest {
    @Before
    public void setUp() throws Exception {
        super.setUp();

        testUtils.setupBook("book-1",
                "First book used for testing\n" +
                "* Note A.\n" +
                "** Note B.\n" +
                "* TODO Note C.\n" +
                "SCHEDULED: <2014-01-01>\n" +
                "** Note D.\n" +
                "*** TODO Note E.\n" +
                ""
        );

        testUtils.setupBook("book-2",
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
                ""
        );

        testUtils.setupBook("book-3", "");

        ActivityScenario.launch(MainActivity.class);
    }

    @Test
    public void testOpenSettings() {
        onActionItemClick(R.id.activity_action_settings, R.string.settings);
        onView(withText(R.string.look_and_feel)).check(matches(isDisplayed()));
    }

    @Test
    public void testReturnToNonExistentBookByPressingBack() {
        onView(allOf(withText("book-1"), withId(R.id.item_book_title))).perform(click());

        onView(withId(R.id.drawer_layout)).perform(open());
        onView(withText(R.string.notebooks)).perform(click());
        onView(allOf(withText("book-1"), withId(R.id.item_book_title))).perform(longClick());
        contextualToolbarOverflowMenu().perform(click());
        onView(withText(R.string.delete)).perform(click());
        onView(withText(R.string.delete)).perform(click());
        pressBack();

        onView(withId(R.id.fragment_book_view_flipper)).check(matches(isDisplayed()));
        onView(withText(R.string.book_does_not_exist_anymore)).check(matches(isDisplayed()));
        onView(withId(R.id.fab)).check(matches(not(isDisplayed())));
        pressBack();

        SystemClock.sleep(500);
        onView(withId(R.id.fragment_books_view_flipper)).check(matches(isDisplayed()));
        onView(allOf(withText("book-2"), withId(R.id.item_book_title))).perform(click());
        onView(allOf(withText(R.string.book_does_not_exist_anymore), isDisplayed())).check(doesNotExist());
    }

    @Test
    @Ignore("Debugging")
    public void testJustExport() {
        onBook(0).perform(longClick());
        contextualToolbarOverflowMenu().perform(click());
        onView(withText(R.string.export)).perform(click());
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressEnter();
    }

    @Test
    public void testCancelExportFileSelection() {
        onBook(0).perform(longClick());
        contextualToolbarOverflowMenu().perform(click());
        onView(withText(R.string.export)).perform(click());
        for (int i = 1; i < 10; i++) {
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack();
        }
    }

    @Test
    public void testExportWithFakeResponse() {
        // Only if DocumentsProvider is supported
        assumeTrue(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT);

        onBook(0).perform(longClick());
        contextualToolbarOverflowMenu().perform(click());

        Intents.init();

        // Response to get after app sends Intent.ACTION_CREATE_DOCUMENT
        Intent resultData = new Intent();
        File file = new File(context.getCacheDir(), "book-1.org");
        resultData.setData(DocumentFile.fromFile(file).getUri());
        ActivityResult result = new ActivityResult(Activity.RESULT_OK, resultData);
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(result);

        // Perform export
        onView(withText(R.string.export)).perform(click());

        // Check that app has sent intent
        intended(allOf(hasAction(Intent.ACTION_CREATE_DOCUMENT), hasExtra(Intent.EXTRA_TITLE, "book-1.org")));

        // Check that file was exported.
        onSnackbar().check(matches(withText(startsWith(context.getString(R.string.book_exported, "")))));

        // Delete exported file
        file.delete();

        Intents.release();
    }

    @Test
    public void testExport() throws IOException {
        // Older API versions, when file is saved in Download/
        assumeTrue(android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.KITKAT);

        onBook(0).perform(longClick());
        contextualToolbarOverflowMenu().perform(click());
        onView(withText(R.string.export)).perform(click());
        onSnackbar().check(matches(withText(startsWith(context.getString(R.string.book_exported, "")))));
        localStorage.getExportFile("book-1", BookFormat.ORG).delete();
    }

    @Test
    public void testCreateNewBookWithoutExtension() {
        onView(withId(R.id.fab)).perform(click());
        onView(withId(R.id.dialog_input)).perform(replaceTextCloseKeyboard("book-created-from-scratch"));
        onView(withText(R.string.create)).perform(click());
        onView(allOf(withText("book-created-from-scratch"), isDisplayed())).perform(click());
        onView(withId(R.id.fragment_book_view_flipper)).check(matches(isDisplayed()));
    }

    @Test
    public void testCreateNewBookWithExtension() {
        onView(withId(R.id.fab)).perform(click());
        onView(withId(R.id.dialog_input)).perform(replaceTextCloseKeyboard("book-created-from-scratch.org"));
        onView(withText(R.string.create)).perform(click());
        onView(allOf(withText("book-created-from-scratch.org"), isDisplayed())).perform(click());
        onView(withId(R.id.fragment_book_view_flipper)).check(matches(isDisplayed()));
    }

    @Test
    public void testCreateAndDeleteBook() {
        onView(withId(R.id.fab)).perform(click());
        onView(withId(R.id.dialog_input)).perform(replaceTextCloseKeyboard("book-created-from-scratch"));
        onView(withText(R.string.create)).perform(click());

        onView(allOf(withText("book-created-from-scratch"), isDisplayed())).check(matches(isDisplayed()));

        onBook(3).perform(longClick());
        contextualToolbarOverflowMenu().perform(click());
        onView(withText(R.string.delete)).perform(click());
        onView(withText(R.string.delete)).perform(click());

        onView(withText("book-created-from-scratch")).check(doesNotExist());
    }

    @Test
    public void testDifferentBookLoading() {
        onView(allOf(withText("book-1"), isDisplayed())).perform(click());
        onNoteInBook(1, R.id.item_head_title_view).check(matches(withText("Note A.")));
        pressBack();
        onView(allOf(withText("book-2"), isDisplayed())).perform(click());
        onNoteInBook(1, R.id.item_head_title_view).check(matches(withText("Note #1.")));
    }

    @Test
    public void testLoadingBookOnlyIfFragmentHasViewCreated() {
        onView(allOf(withText("book-1"), isDisplayed())).perform(click());

        onView(withId(R.id.drawer_layout)).perform(open());
        onView(withText(R.string.notebooks)).perform(click());

        onBook(1).perform(longClick());
        contextualToolbarOverflowMenu().perform(click());
        onView(withText(R.string.delete)).perform(click());
        onView(withText(R.string.delete)).perform(click());
    }

    @Test
    public void testCreateNewBookWithExistingName() {
        onView(withId(R.id.fab)).perform(click());
        onView(withId(R.id.dialog_input)).perform(replaceTextCloseKeyboard("new-book"));
        onView(withText(R.string.create)).perform(click());

        onView(withId(R.id.fab)).perform(click());
        onView(withId(R.id.dialog_input)).perform(replaceTextCloseKeyboard("new-book"));
        onView(withText(R.string.create)).perform(click());

        onSnackbar().check(matches(
                withText(context.getString(R.string.book_name_already_exists, "new-book"))));
    }

    @Test
    public void testCreateNewBookWithWhiteSpace() {
        onView(withId(R.id.fab)).perform(click());
        onView(withId(R.id.dialog_input)).perform(replaceTextCloseKeyboard(" new-book  "));
        onView(withText(R.string.create)).perform(click());
        onBook(3, R.id.item_book_title).check(matches(withText("new-book")));
    }

    @Test
    public void testRenameBookToExistingName() {
        onBook(0).perform(longClick());
        contextualToolbarOverflowMenu().perform(click());
        onView(withText(R.string.rename)).perform(click());
        onView(withId(R.id.name)).perform(replaceTextCloseKeyboard("book-2"));
        onView(withText(R.string.rename)).perform(click());
        onBook(0, R.id.item_book_last_action)
                .check(matches(withText(endsWith(context.getString(R.string.book_name_already_exists, "book-2")))));
    }

    @Test
    public void testRenameBookToSameName() {
        onBook(0).perform(longClick());
        contextualToolbarOverflowMenu().perform(click());
        onView(withText(R.string.rename)).perform(click());
        onView(withText(R.string.rename)).check(matches(not(isEnabled())));
    }

    @Test
    public void testNoteCountDisplayed() throws IOException {
        onBook(0, R.id.item_book_note_count)
                .check(matches(withText(context.getResources().getQuantityString(R.plurals.notes_count_nonzero, 5, 5))));
        onBook(1, R.id.item_book_note_count)
                .check(matches(withText(context.getResources().getQuantityString(R.plurals.notes_count_nonzero, 10, 10))));
        onBook(2, R.id.item_book_note_count)
                .check(matches(withText(R.string.notes_count_zero)));
    }

    @Test
    public void testBackPressClosesSelectionMenu() {
        // Select book
        onBook(0).perform(longClick());

        // Press back
        pressBack();

        // Make sure we're still in the app
        onBook(0, R.id.item_book_title).check(matches(withText("book-1")));
    }

    @Test
    public void testSetLinkOnSingleBookCurrentRepoIsSelected() {
        testUtils.setupRepo(RepoType.MOCK, "mock://repo");
        sync();
        onBook(0, R.id.item_book_link_repo).check(matches(withText("mock://repo")));
        onBook(0).perform(longClick());
        contextualToolbarOverflowMenu().perform(click());
        onView(withText(R.string.books_context_menu_item_set_link)).perform(click());
        onView(withText("mock://repo")).check(matches(isChecked()));
    }

    /**
     * When setting the link of multiple books, no repo should be pre-selected,
     * no matter how many repos there are, and no matter whether the books
     * already have a link or not. The reason for this is that we have no
     * intuitive way of displaying links to multiple repos.
     */
    @Test
    public void testSetLinkOnMultipleBooksNoRepoIsSelected() {
        testUtils.setupRepo(RepoType.MOCK, "mock://repo");
        sync();
        onBook(0, R.id.item_book_link_repo).check(matches(withText("mock://repo")));
        onBook(1, R.id.item_book_link_repo).check(matches(withText("mock://repo")));
        onBook(0).perform(longClick());
        onBook(1).perform(click());
        contextualToolbarOverflowMenu().perform(click());
        onView(withText(R.string.books_context_menu_item_set_link)).perform(click());
        onView(withText("mock://repo")).check(matches(isNotChecked()));
    }

    @Test
    public void testDeleteSingleBookLinkedUrlIsShown() {
        testUtils.setupRepo(RepoType.MOCK, "mock://repo");
        sync();
        onBook(0, R.id.item_book_link_repo).check(matches(withText("mock://repo")));
        onBook(0).perform(longClick());
        contextualToolbarOverflowMenu().perform(click());
        onView(withText(R.string.delete)).perform(click());
        onView(withText(R.string.also_delete_linked_book)).check(matches(isDisplayed()));
        onView(withId(R.id.delete_linked_url)).check(matches(withText("mock://repo/book-1.org")));
    }

    @Test
    public void testDeleteMultipleBooksLinkedUrlIsNotShown() {
        testUtils.setupRepo(RepoType.MOCK, "mock://repo");
        sync();
        onBook(0, R.id.item_book_link_repo).check(matches(withText("mock://repo")));
        onBook(1, R.id.item_book_link_repo).check(matches(withText("mock://repo")));
        onBook(0).perform(longClick());
        onBook(1).perform(click());
        contextualToolbarOverflowMenu().perform(click());
        onView(withText(R.string.delete)).perform(click());
        onView(withText(R.string.also_delete_linked_books)).check(matches(isDisplayed()));
        onView(withId(R.id.delete_linked_url)).check(matches(withText("")));
    }

    @Test
    public void testDeleteMultipleBooksWithNoLinks() {
        onBook(0).perform(longClick());
        onBook(1).perform(click());
        contextualToolbarOverflowMenu().perform(click());
        onView(withText(R.string.delete)).perform(click());
        onView(withText(R.string.delete)).perform(click());
        assert dataRepository.getBooks().size() == 1;
    }

    @Test
    public void testDeleteMultipleBooksAndRooks() {
        testUtils.setupRepo(RepoType.MOCK, "mock://repo");
        sync();
        onBook(0, R.id.item_book_link_repo).check(matches(withText("mock://repo")));
        onBook(1, R.id.item_book_link_repo).check(matches(withText("mock://repo")));
        onBook(0).perform(longClick());
        onBook(1).perform(click());
        contextualToolbarOverflowMenu().perform(click());
        onView(withText(R.string.delete)).perform(click());
        onView(withId(R.id.delete_linked_checkbox)).perform(click());
        onView(withText(R.string.delete)).perform(click());
        assert dataRepository.getBooks().size() == 1;
    }

    /**
     * When multiple books are selected, the "rename" and "export" actions should be removed from
     * the context menu. By also testing that only the expected number of actions are shown, we
     * protect against someone later adding actions to the menu without fully considering the support for
     * multiple selected books. When such support is added, this test will need to be updated.
     */
    @Test
    public void testMultipleBooksSelectedContextMenuShowsSupportedActionsOnly() {
        onBook(0).perform(longClick());
        contextualToolbarOverflowMenu().perform(click());
        onView(withText(R.string.rename)).check(matches(isDisplayed()));
        onView(withText(R.string.export)).check(matches(isDisplayed()));
        onView(withClassName(containsString("MenuDropDownListView"))).check(matches(hasChildCount(4)));
        pressBack();
        onBook(1).perform(click());
        contextualToolbarOverflowMenu().perform(click());
        onView(withText(R.string.rename)).check(doesNotExist());
        onView(withText(R.string.export)).check(doesNotExist());
        onView(withClassName(containsString("MenuDropDownListView"))).check(matches(hasChildCount(2)));
    }
}
