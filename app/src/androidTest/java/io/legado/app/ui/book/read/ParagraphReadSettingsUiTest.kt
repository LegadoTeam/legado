package io.legado.app.ui.book.read

import android.content.Intent
import android.os.SystemClock
import android.view.View
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.ui.book.read.config.MoreConfigDialog
import io.legado.app.ui.book.read.config.ParagraphReadConfigDialog
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.paragraph.ParagraphReadView
import io.legado.app.utils.defaultSharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opens the actual nested reader settings and verifies persisted choices affect the reader. */
@RunWith(AndroidJUnit4::class)
class ParagraphReadSettingsUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = context.defaultSharedPreferences
    private val keys = listOf(PreferKey.paragraphReadMode, PreferKey.paragraphReadControls,
        PreferKey.paragraphReadProgress, PreferKey.paragraphReadChapterSwipe)
    private val savedPreferences = keys.associateWith { preferences.all[it] }
    private val helpKeys = listOf("readMenuHelpVersion", "readHelpVersion")
    private val savedHelp = helpKeys.associateWith { LocalConfig.all[it] as? Int }
    private var scenario: ActivityScenario<ReadBookActivity>? = null
    private lateinit var textFile: File
    private lateinit var book: Book

    @Before fun setUp() {
        LocalConfig.edit().apply { helpKeys.forEach { putInt(it, 1) } }.commit()
        preferences.edit().apply { keys.forEach { remove(it) } }.commit()
        textFile = File.createTempFile("paragraph-settings-", ".txt", context.cacheDir)
        textFile.writeText((1..40).joinToString("\n\n") { "段落设置回归测试，第 $it 段。" })
        book = Book(bookUrl = textFile.absolutePath, originName = textFile.name, name = textFile.name,
            charset = "UTF-8", type = BookType.local or BookType.text, totalChapterNum = 1)
            .apply { setPageAnim(PageAnim.noAnim) }
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(BookChapter(bookUrl = book.bookUrl, url = "paragraph-settings",
            title = "Paragraph settings", start = 0L, end = textFile.length()))
        scenario = ActivityScenario.launch(Intent(context, ReadBookActivity::class.java)
            .putExtra("bookUrl", book.bookUrl))
        await("reader content") {
            ReadBook.book?.bookUrl == book.bookUrl && ReadBook.curTextChapter?.isCompleted == true &&
                !it.findViewById<ReadView>(R.id.read_view).curPage.textPage.isMsgPage && it.bottomDialog == 0
        }
    }

    @After fun tearDown() {
        scenario?.close()
        if (::book.isInitialized) {
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookDao.delete(book)
        }
        if (::textFile.isInitialized) textFile.delete()
        TextFile.clear()
        preferences.edit().apply {
            savedPreferences.forEach { (key, value) ->
                if (value == null) remove(key) else putBoolean(key, value as Boolean)
            }
        }.commit()
        LocalConfig.edit().apply {
            savedHelp.forEach { (key, value) ->
                if (value == null) remove(key) else putInt(key, value)
            }
        }.commit()
    }

    @Test fun nestedSettingsUseSafeDefaultsAndApplySwitchesImmediately() {
        openSettings()
        onActivity { activity ->
            val fragment = paragraphSettings(activity)
            assertEquals(keys, (0 until fragment.preferenceScreen.preferenceCount)
                .mapNotNull { fragment.preferenceScreen.getPreference(it).key })
            assertFalse(switch(fragment, PreferKey.paragraphReadMode).isChecked)
            assertTrue(switch(fragment, PreferKey.paragraphReadControls).isChecked)
            assertTrue(switch(fragment, PreferKey.paragraphReadProgress).isChecked)
            assertFalse(switch(fragment, PreferKey.paragraphReadChapterSwipe).isChecked)
            keys.drop(1).forEach { assertFalse(switch(fragment, it).isEnabled) }
            assertFalse(AppConfig.paragraphReadChapterSwipe)
            switch(fragment, PreferKey.paragraphReadMode).performClick()
            keys.drop(1).forEach { assertTrue(switch(fragment, it).isEnabled) }
            assertEquals(View.VISIBLE, paragraph(activity).visibility)
            switch(fragment, PreferKey.paragraphReadControls).performClick()
            switch(fragment, PreferKey.paragraphReadProgress).performClick()
            assertEquals(View.GONE, paragraphPart(activity, "controls").visibility)
            assertEquals(View.GONE, paragraphPart(activity, "progress").visibility)
            switch(fragment, PreferKey.paragraphReadChapterSwipe).performClick()
            assertTrue(AppConfig.paragraphReadChapterSwipe)
        }
        pressBack()
        await("return to regular reader settings") {
            settings(it)?.isResumed == true && paragraphDialog(it) == null && it.bottomDialog == 1
        }
        onActivity {
            val fragment = settings(it)!!
            assertNotNull(fragment.findPreference<Preference>(PreferKey.screenOrientation))
            assertNull(fragment.findPreference<Preference>(PreferKey.paragraphReadMode))
            assertNull(fragment.findPreference<Preference>(PreferKey.paragraphReadControls))
            assertNull(fragment.findPreference<Preference>(PreferKey.paragraphReadProgress))
            assertNotNull(fragment.findPreference<Preference>("paragraphReadConfig"))
        }
        pressBack()
        await("close regular settings") { it.bottomDialog == 0 }
    }

    @Test fun existingChoicesSurviveOpeningRecreationAndModeChanges() {
        onActivity {
            preferences.edit().putBoolean(PreferKey.paragraphReadMode, true)
                .putBoolean(PreferKey.paragraphReadControls, false)
                .putBoolean(PreferKey.paragraphReadProgress, false)
                .putBoolean(PreferKey.paragraphReadChapterSwipe, true).commit()
            it.updateParagraphMode()
        }
        openSettings()
        assertSavedChoices()
        scenario!!.recreate()
        await("restore nested paragraph settings") {
            paragraphDialog(it)?.isResumed == true &&
                paragraphDialog(it)?.childFragmentManager?.fragments?.singleOrNull()?.isResumed == true
        }
        assertSavedChoices()
        onActivity { activity ->
            val fragment = paragraphSettings(activity)
            switch(fragment, PreferKey.paragraphReadChapterSwipe).performClick()
            assertFalse(AppConfig.paragraphReadChapterSwipe)
            switch(fragment, PreferKey.paragraphReadMode).performClick()
            assertEquals(View.GONE, paragraph(activity).visibility)
            keys.drop(1).forEach { assertFalse(switch(fragment, it).isEnabled) }
            switch(fragment, PreferKey.paragraphReadMode).performClick()
            keys.drop(1).forEach {
                assertTrue(switch(fragment, it).isEnabled)
                assertFalse(switch(fragment, it).isChecked)
            }
            assertEquals(View.VISIBLE, paragraph(activity).visibility)
            assertEquals(View.GONE, paragraphPart(activity, "controls").visibility)
            assertEquals(View.GONE, paragraphPart(activity, "progress").visibility)
        }
    }

    private fun assertSavedChoices() = onActivity { activity ->
        val fragment = paragraphSettings(activity)
        assertTrue(switch(fragment, PreferKey.paragraphReadMode).isChecked)
        assertFalse(switch(fragment, PreferKey.paragraphReadControls).isChecked)
        assertFalse(switch(fragment, PreferKey.paragraphReadProgress).isChecked)
        assertTrue(switch(fragment, PreferKey.paragraphReadChapterSwipe).isChecked)
        assertTrue(AppConfig.paragraphReadChapterSwipe)
    }

    private fun openSettings() {
        onActivity { MoreConfigDialog().showNow(it.supportFragmentManager, "paragraph-settings-root") }
        await("regular reader settings") { settings(it)?.isResumed == true }
        onView(withText(R.string.paragraph_read_settings)).inRoot(isDialog()).perform(click())
        await("nested paragraph settings") {
            paragraphDialog(it)?.childFragmentManager?.fragments?.singleOrNull()?.isResumed == true
        }
    }

    private fun settings(activity: ReadBookActivity) =
        (activity.supportFragmentManager.findFragmentByTag("paragraph-settings-root") as? MoreConfigDialog)
            ?.childFragmentManager?.fragments?.singleOrNull() as? MoreConfigDialog.ReadPreferenceFragment

    private fun paragraphDialog(activity: ReadBookActivity) =
        settings(activity)?.childFragmentManager?.findFragmentByTag("ParagraphReadConfigDialog")
            as? ParagraphReadConfigDialog

    private fun paragraphSettings(activity: ReadBookActivity) =
        paragraphDialog(activity)!!.childFragmentManager.fragments.single()
            as ParagraphReadConfigDialog.ParagraphPreferenceFragment

    private fun switch(fragment: ParagraphReadConfigDialog.ParagraphPreferenceFragment, key: String) =
        fragment.findPreference<SwitchPreferenceCompat>(key)!!

    private fun paragraph(activity: ReadBookActivity) =
        activity.findViewById<ParagraphReadView>(R.id.paragraph_read_view)

    private fun paragraphPart(activity: ReadBookActivity, field: String) =
        ParagraphReadView::class.java.getDeclaredField(field).apply { isAccessible = true }
            .get(paragraph(activity)) as View

    private fun onActivity(action: (ReadBookActivity) -> Unit) {
        scenario!!.onActivity(action)
    }

    private fun await(description: String, condition: (ReadBookActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Timed out waiting for $description")
    }
}
