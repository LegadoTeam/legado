package io.legado.app.ui.book.searchContent

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.IntentData
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.ui.widget.TitleBar
import io.legado.app.ui.widget.anima.RefreshProgressBar
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SearchContentDirectionUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.defaultSharedPreferences
    private val preferenceKeys = listOf(
        PreferKey.contentSearchScope, PreferKey.contentSearchReplace, PreferKey.contentSearchRegex,
    )
    private val originalPreferences = preferenceKeys.associateWith { preferences.all[it] }
    private val id = UUID.randomUUID().toString()
    private val book = Book(
        bookUrl = "https://example.invalid/content-direction/$id",
        origin = "https://example.invalid/source/$id",
        name = "Content direction $id",
        author = "Fixture",
        totalChapterNum = 3,
        durChapterIndex = 1,
        canUpdate = false,
    )
    private val chapters = (0..2).map {
        BookChapter(bookUrl = book.bookUrl, url = "${book.bookUrl}/$it", index = it, title = "Chapter $it")
    }
    private val contents = chapters.map { "needle before ${it.index}\nneedle after ${it.index}\nfresh chapter ${it.index}" }
    private val restoredResult = SearchResult(query = "needle", resultText = "Restored result",
        chapterIndex = 1, resultCountWithinChapter = 1)
    private var previousIntentResults: Any? = null
    private var anchorKey: String? = null
    private var scenario: ActivityScenario<SearchContentActivity>? = null

    @Before
    fun setUp() {
        preferences.edit()
            .putInt(PreferKey.contentSearchScope, ContentSearchScope.ALL.ordinal)
            .putBoolean(PreferKey.contentSearchReplace, false)
            .putBoolean(PreferKey.contentSearchRegex, false)
            .commit()
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        chapters.forEach { BookHelp.saveText(book, it, contents[it.index]) }

        val processed = ContentProcessor.get(book).getContent(book, chapters[1], contents[1], useReplace = false)
        val anchor = checkNotNull(ContentSearchAnchor.capture(
            1, processed.toString(), processed.titleLength, processed.toString().lastIndexOf("needle"),
        ))
        previousIntentResults = IntentData.get<Any>("searchResultList")
        IntentData.put("searchResultList", listOf(restoredResult))
        anchorKey = IntentData.put(anchor)
        scenario = ActivityScenario.launch(Intent(context, SearchContentActivity::class.java).apply {
            putExtra("bookUrl", book.bookUrl)
            putExtra("searchChapterIndex", 1)
            putExtra("searchWord", "needle")
            putExtra("searchResultIndex", 0)
            putExtra("searchAnchorKey", anchorKey)
        })
        awaitRestoredResults()
    }

    @After
    fun tearDown() {
        scenario?.close()
        IntentData.get<Any>(anchorKey)
        IntentData.get<Any>("searchResultList")
        IntentData.put("searchResultList", previousIntentResults)
        preferences.edit().apply {
            originalPreferences.forEach { (key, value) ->
                when (value) {
                    is Int -> putInt(key, value)
                    is Boolean -> putBoolean(key, value)
                    else -> remove(key)
                }
            }
        }.commit()
        chapters.forEach { BookHelp.delContent(book, it) }
        appDb.bookChapterDao.delByBook(book.bookUrl)
        appDb.bookDao.delete(book)
    }

    @Test
    fun restoredResultsRememberQueryAndScopeMenuImmediatelySearchesBothDirections() {
        scenario!!.onActivity { activity ->
            assertEquals("needle", model(activity).lastQuery)
            assertEquals(listOf(restoredResult), adapter(activity).getItems())
        }
        selectScope(R.id.menu_content_scope_down)
        assertResults("needle", listOf(1 to 1, 2 to 0, 2 to 1), ContentSearchScope.DOWN)
        selectScope(R.id.menu_content_scope_up)
        assertResults("needle", listOf(1 to 1, 1 to 0, 0 to 1, 0 to 0), ContentSearchScope.UP)
    }

    @Test
    fun immediatelySubmittingAnotherQueryCancelsOldJobWithoutMixingResults() {
        lateinit var oldJob: Job
        lateinit var newJob: Job
        scenario!!.onActivity { activity ->
            val searchView = activity.findViewById<SearchView>(R.id.search_view)
            // Both submissions run in one main-thread turn. The first search must suspend for
            // its IO work, so it cannot publish a chapter before the second query cancels it.
            oldJob = searchJobStartedBy(activity) { searchView.setQuery("needle", true) }
            newJob = searchJobStartedBy(activity) { searchView.setQuery("fresh", true) }
            assertNotSame(oldJob, newJob)
            assertTrue("The superseded search is cancelled immediately", oldJob.isCancelled)
        }
        awaitJobs(oldJob, newJob)
        assertResults("fresh", listOf(0 to 0, 1 to 0, 2 to 0), ContentSearchScope.ALL)
    }

    @Test
    fun recreationKeepsLatestQueryAndItsResults() {
        submitQuery("fresh")
        val positions = listOf(0 to 0, 1 to 0, 2 to 0)
        assertResults("fresh", positions, ContentSearchScope.ALL)
        lateinit var results: List<SearchResult>
        scenario!!.onActivity { results = model(it).searchResultList.toList() }
        scenario!!.recreate()
        awaitRestoredResults("fresh", results)
        assertResults("fresh", positions, ContentSearchScope.ALL)
    }

    @Test
    fun recreationKeepsUnmatchedAndCancelledQueriesWithoutRestartingIntentSearch() {
        submitQuery("missing")
        assertResults("missing", emptyList(), ContentSearchScope.ALL, R.string.search_content_empty)
        scenario!!.recreate()
        awaitRestoredResults("missing", listOf(SearchResult(resultText = context.getString(R.string.search_content_empty))))
        assertResults("missing", emptyList(), ContentSearchScope.ALL, R.string.search_content_empty)

        lateinit var cancelledJob: Job
        scenario!!.onActivity { activity ->
            cancelledJob = searchJobStartedBy(activity) {
                activity.findViewById<SearchView>(R.id.search_view).setQuery("fresh", true)
            }
            activity.findViewById<View>(R.id.fb_stop).performClick()
            assertTrue(cancelledJob.isCancelled)
        }
        awaitJobs(cancelledJob)
        assertResults("fresh", emptyList(), ContentSearchScope.ALL)
        scenario!!.recreate()
        awaitRestoredResults("fresh", emptyList())
        assertResults("fresh", emptyList(), ContentSearchScope.ALL)
    }

    private fun submitQuery(query: String) {
        lateinit var job: Job
        scenario!!.onActivity { activity ->
            job = searchJobStartedBy(activity) {
                activity.findViewById<SearchView>(R.id.search_view).setQuery(query, true)
            }
        }
        awaitJobs(job)
    }

    private fun selectScope(menuId: Int) {
        lateinit var job: Job
        scenario!!.onActivity { activity ->
            val menu = activity.findViewById<TitleBar>(R.id.title_bar).menu
            job = searchJobStartedBy(activity) {
                activity.onCompatOptionsItemSelected(checkNotNull(menu.findItem(menuId)))
            }
        }
        awaitJobs(job)
    }

    private fun assertResults(query: String, positions: List<Pair<Int, Int>>, scope: ContentSearchScope,
        message: Int? = null) {
        scenario!!.onActivity { activity ->
            val model = model(activity)
            assertEquals(query, model.lastQuery)
            assertEquals(query, activity.findViewById<SearchView>(R.id.search_view).query.toString())
            assertEquals(scope, SearchContentViewModel.searchScope)
            assertEquals(positions, model.searchResultList.map { it.chapterIndex to it.resultCountWithinChapter })
            assertTrue(model.searchResultList.all { it.query == query && it.resultText.contains(query) })
            assertEquals(positions.size, model.searchResultCounts)
            val expectedRows = model.searchResultList + listOfNotNull(message?.let {
                SearchResult(resultText = activity.getString(it))
            })
            assertEquals(expectedRows, adapter(activity).getItems())
            assertEquals(activity.getString(R.string.search_content_size) + ": ${positions.size}",
                activity.findViewById<TextView>(R.id.tv_current_search_info).text.toString())
            assertFalse(activity.findViewById<RefreshProgressBar>(R.id.refresh_progress_bar).isAutoLoading)
            assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.fb_stop).visibility)
        }
    }

    private fun awaitRestoredResults(query: String = "needle", results: List<SearchResult> = listOf(restoredResult)) {
        lateinit var initializationJobs: List<Job>
        scenario!!.onActivity { activity ->
            initializationJobs = checkNotNull(model(activity).viewModelScope.coroutineContext[Job]).children.toList()
        }
        // initBook's ViewModel job includes the main-thread initialization callback. Joining
        // it also prevents an empty adapter restored by Android from satisfying this too early.
        awaitJobs(*initializationJobs.toTypedArray())
        scenario!!.onActivity { activity ->
            assertEquals(query, activity.findViewById<SearchView>(R.id.search_view).query.toString())
            assertEquals(results, adapter(activity).getItems())
        }
    }

    private fun searchJobStartedBy(activity: SearchContentActivity, action: () -> Unit): Job {
        val parent = checkNotNull(activity.lifecycleScope.coroutineContext[Job])
        val previousJobs = parent.children.toSet()
        action()
        return parent.children.single { it !in previousJobs }
    }

    private fun awaitJobs(vararg jobs: Job) = runBlocking {
        withTimeout(15_000) { jobs.forEach { it.join() } }
    }

    private fun model(activity: SearchContentActivity) =
        ViewModelProvider(activity)[SearchContentViewModel::class.java]

    private fun adapter(activity: SearchContentActivity) =
        activity.findViewById<RecyclerView>(R.id.recycler_view).adapter as SearchContentAdapter
}
