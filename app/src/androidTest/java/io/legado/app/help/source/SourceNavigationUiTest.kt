package io.legado.app.help.source

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.constant.PreferKey
import io.legado.app.constant.SourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class SourceNavigationUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun optInBlocksIncidentalRuleUiButKeepsHttpAndInteractiveDetailWorking() = runBlocking {
        val preferences = context.defaultSharedPreferences
        val saved = preferences.all[PreferKey.blockSourceNavigation] as? Boolean
        val savedHelp = LocalConfig.all["bookSourceHelpVersion"] as? Int
        val source = BookSource(bookSourceUrl = "https://navigation.invalid/${UUID.randomUUID()}")
        val starts = CopyOnWriteArrayList<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                starts += intent
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = newFixedLengthResponse(
                "<article><h2>Navigation fixture</h2><a href='/book'>Book</a></article>")
        }
        var scenario: ActivityScenario<BookSourceActivity>? = null
        try {
            preferences.edit().remove(PreferKey.blockSourceNavigation).commit()
            assertFalse(AppConfig.blockSourceNavigation)
            LocalConfig.edit().putInt("bookSourceHelpVersion", 1).commit()
            scenario = ActivityScenario.launch(BookSourceActivity::class.java)
            source.loginUrl = "@js:function login() { java.openUrl('https://navigation.invalid/nested'); source.put('navigationLogin', 'ok'); }"
            appDb.bookSourceDao.insert(source)
            instrumentation.addMonitor(monitor)
            server.start()
            val pageUrl = "http://127.0.0.1:${server.listeningPort}/search"
            val open = "java.openUrl('https://navigation.invalid/login');"
            source.searchUrl = "@js:$open'$pageUrl'"
            source.ruleSearch = SearchRule(bookList = "article", name = "h2@text", bookUrl = "a@href")
            source.ruleExplore = ExploreRule(bookList = "article", name = "h2@text", bookUrl = "a@href")

            // The real search entry marks its operation even when the caller does not.
            assertEquals(1, WebBook.searchBookAwait(source, "fixture").size)
            assertEquals(1, starts.size) // Default off preserves existing source behavior.
            starts.clear()
            AppConfig.blockSourceNavigation = true
            assertEquals("Navigation fixture", WebBook.searchBookAwait(source, "fixture").single().name)
            assertTrue(starts.isEmpty())

            withContext(SuppressSourceNavigation) {
                val rule = AnalyzeRule(source = source).setCoroutineContext(currentCoroutineContext())
                val result = rule.evalJS("""
                    java.openUrl('https://navigation.invalid/login');
                    source.openUrl('https://navigation.invalid/login');
                    java.startBrowser('https://navigation.invalid/login', 'Login');
                    java.showBrowser('https://navigation.invalid/login');
                    java.openVideoPlayer('https://navigation.invalid/video.mp4', 'Video', false);
                    var blocked = false;
                    try { java.startBrowserAwait('https://navigation.invalid/login', 'Login'); }
                    catch (e) { blocked = true; }
                    blocked;
                """.trimIndent())
                assertEquals(true, result)
                assertTrue(starts.isEmpty())
                scenario!!.onActivity { assertTrue(it.supportFragmentManager.fragments.isEmpty()) }

                // Rule WebViews call both bridges on a separate Java thread; storage remains usable.
                rule.setContent("<p>Background</p>", pageUrl)
                source.header = "@js:java.openUrl('https://navigation.invalid/header'); '{\"X-Navigation\":\"ok\"}'"
                for (blocked in listOf(true, false)) {
                    AppConfig.blockSourceNavigation = blocked
                    Log.i("SourceNavigationTest", "webjs start blocked=$blocked")
                    val response = try { rule.getString("""@webjs:
                        console.info('navigation webjs: started');
                        java.openUrl('https://navigation.invalid/java');
                        source.openUrl('https://navigation.invalid/source');
                        console.info('navigation webjs: before login');
                        source.login();
                        console.info('navigation webjs: after login');
                        source.put('navigationTest', 'stored');
                        console.info('navigation webjs: stored');
                        source.get('navigationTest') + ':' + document.querySelector('p').textContent + ':' + source.get('navigationLogin');
                    """.trimIndent()) } catch (error: Throwable) {
                        File(context.getExternalFilesDir("ui-regression"), "source-navigation-timeout-threads.txt")
                            .writeText("blocked=$blocked; starts=${starts.size}\n" +
                                Thread.getAllStackTraces().entries.joinToString("\n\n") { (thread, stack) ->
                                    "${thread.name}: ${thread.state}\n${stack.joinToString("\n")}"
                                })
                        throw error
                    }
                    Log.i("SourceNavigationTest", "webjs completed blocked=$blocked response=$response")
                    assertEquals("stored:Background:ok", response)
                    assertEquals(if (blocked) 0 else 4, starts.size)
                    starts.forEach { assertEquals(SourceType.book, it.getIntExtra("sourceType", -1)) }
                    starts.clear()
                }
                source.header = null
                AppConfig.blockSourceNavigation = true
            }

            // A normal detail request still runs its required login after a blocked search.
            source.ruleBookInfo = BookInfoRule(init = "@js:${open}result", name = "h2@text")
            val book = Book(bookUrl = pageUrl, origin = source.bookSourceUrl)
            assertEquals("Navigation fixture", WebBook.getBookInfoAwait(source, book).name)
            assertEquals(1, starts.size)
            starts.clear()
            assertEquals(1, WebBook.exploreBookAwait(source, "@js:$open'$pageUrl'").size)
            assertEquals(1, starts.size)
        } finally {
            instrumentation.removeMonitor(monitor)
            scenario?.close()
            server.stop()
            appDb.bookSourceDao.delete(source)
            preferences.edit().apply {
                if (saved == null) remove(PreferKey.blockSourceNavigation)
                else putBoolean(PreferKey.blockSourceNavigation, saved)
            }.commit()
            LocalConfig.edit().apply {
                if (savedHelp == null) remove("bookSourceHelpVersion")
                else putInt("bookSourceHelpVersion", savedHelp)
            }.commit()
        }
    }
}
