package io.legado.app.help.source

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.swipeDown
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.PreferKey
import io.legado.app.constant.SourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.CacheBook
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.hamcrest.Matchers.allOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class SourceNavigationUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun actualShelfRefreshBlocksRuleCallbacksAndPreloadsWithoutBlockingParallelDetail() = runBlocking {
        val preferences = context.defaultSharedPreferences
        val keys = listOf(PreferKey.blockSourceNavigation, PreferKey.cronet, PreferKey.preDownloadNum,
            PreferKey.autoRefresh, PreferKey.autoCheckNewBackup, PreferKey.defaultHomePage, "autoUpdateVariant")
        val saved = keys.associateWith { preferences.all[it] }
        val localKeys = listOf("privacyPolicyOk", "appVersionCode", "password")
        val savedLocal = localKeys.associateWith { LocalConfig.all[it] }
        val starts = CopyOnWriteArrayList<Intent>()
        val requests = CopyOnWriteArrayList<String>()
        val tocGate = AtomicReference<CountDownLatch?>()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                requests += session.uri
                if (session.uri.startsWith("/toc/")) tocGate.get()?.await(10, TimeUnit.SECONDS)
                val id = session.uri.substringAfterLast('/')
                return newFixedLengthResponse("<h2>Shelf navigation $id</h2>" +
                    "<a href='/chapter/$id'>Chapter $id</a><p>HTTP body $id</p>")
            }
        }
        val source = BookSource(bookSourceUrl = "https://shelf-navigation.invalid/${UUID.randomUUID()}",
            bookSourceName = "Shelf navigation fixture", eventListener = true)
        val books = mutableListOf<Book>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                starts += intent
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            server.start()
            val base = "http://127.0.0.1:${server.listeningPort}"
            source.header = "@js:java.openUrl('https://navigation.invalid/header'); '{}'"
            source.ruleBookInfo = BookInfoRule(init = "@js:java.openUrl('https://navigation.invalid/info');result",
                name = "h2@text", tocUrl = "@js:'$base/toc/' + book.bookUrl.substring(book.bookUrl.lastIndexOf('/') + 1)")
            source.ruleToc = TocRule(preUpdateJs = "java.openUrl('https://navigation.invalid/pre');java.ajax('$base/event/pre');",
                chapterList = "a", chapterName = "text", chapterUrl = "href")
            source.ruleContent = ContentRule(content = "@js:java.openUrl('https://navigation.invalid/content');result",
                callBackJs = "java.openUrl('https://navigation.invalid/' + event);java.ajax('$base/event/' + event);true;")
            appDb.bookSourceDao.insert(source)
            preferences.edit().putBoolean(PreferKey.cronet, false).putInt(PreferKey.preDownloadNum, 1)
                .putBoolean(PreferKey.autoRefresh, false).putBoolean(PreferKey.autoCheckNewBackup, false)
                .putBoolean("autoUpdateVariant", false).putString(PreferKey.defaultHomePage, "bookshelf").commit()
            LocalConfig.edit().putBoolean("privacyPolicyOk", true).putLong("appVersionCode", appInfo.versionCode)
                .putString("password", "").commit()
            for (blocked in listOf(true, false)) {
                AppConfig.blockSourceNavigation = blocked
                val pair = (0..1).map { index ->
                    val id = "${UUID.randomUUID()}-$index"
                    Book(bookUrl = "$base/book/$id", origin = source.bookSourceUrl,
                        name = "Shelf navigation $id", tocUrl = if (index == 0) "" else "$base/toc/$id")
                }
                books += pair
                appDb.bookDao.insert(*pair.toTypedArray())
                scenario = ActivityScenario.launch(MainActivity::class.java)
                withTimeout(10_000) {
                    var loaded = false
                    while (!loaded) {
                        scenario!!.onActivity { loaded = (it.findViewById<RecyclerView>(R.id.rv_bookshelf)?.adapter?.itemCount ?: 0) >= 2 }
                        if (!loaded) delay(20)
                    }
                }
                instrumentation.addMonitor(monitor)
                requests.clear()
                starts.clear()
                val gate = CountDownLatch(1)
                tocGate.set(gate)
                onView(allOf(withId(R.id.refresh_layout), isDisplayed())).perform(swipeDown())
                withTimeout(10_000) { while (requests.none { it.startsWith("/toc/") }) delay(20) }
                if (blocked) {
                    // The refresh remains in flight: its context must not leak to this reader request.
                    assertTrue(starts.isEmpty())
                    withContext(IO) { WebBook.getBookInfoAwait(source,
                        Book(bookUrl = "$base/interactive", origin = source.bookSourceUrl)) }
                    instrumentation.waitForIdleSync()
                    assertEquals(2, starts.size) // Header + detail init retain their navigation.
                    starts.clear()
                }
                gate.countDown()
                tocGate.set(null)
                withTimeout(30_000) {
                    while (!requests.containsAll(listOf("/event/startShelfRefresh", "/event/endShelfRefresh", "/event/pre")) ||
                        pair.any { appDb.bookChapterDao.getChapter(it.bookUrl, 0)?.let { chapter ->
                            BookHelp.getContent(it, chapter)?.contains("HTTP body") != true
                        } != false || CacheBook.cacheBookMap[it.bookUrl]?.isRun() == true }) delay(20)
                }
                instrumentation.waitForIdleSync()
                pair.forEach { assertEquals(1, appDb.bookChapterDao.getChapterCount(it.bookUrl)) }
                if (blocked) {
                    assertTrue("Background navigation: $starts", starts.isEmpty())
                    File(context.getExternalFilesDir("ui-regression"), "source-navigation-shelf-blocked.png")
                        .outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
                } else {
                    val paths = starts.mapNotNull { it.getStringExtra("uri")?.substringAfter("navigation.invalid") }.toSet()
                    assertTrue("Disabled switch must preserve every stage: $paths", paths.containsAll(
                        listOf("/header", "/info", "/pre", "/content", "/startShelfRefresh", "/endShelfRefresh")))
                }
                instrumentation.removeMonitor(monitor)
                scenario!!.close()
                scenario = null
                pair.forEach { appDb.bookDao.delete(it); appDb.bookChapterDao.delByBook(it.bookUrl) }
            }
        } finally {
            tocGate.getAndSet(null)?.countDown()
            instrumentation.removeMonitor(monitor)
            scenario?.close()
            server.stop()
            books.forEach {
                CacheBook.cacheBookMap[it.bookUrl]?.stop()
                appDb.bookDao.delete(it)
                appDb.bookChapterDao.delByBook(it.bookUrl)
                BookHelp.clearCache(it)
            }
            appDb.bookSourceDao.delete(source)
            for ((prefs, values) in listOf(preferences to saved, LocalConfig to savedLocal)) {
                prefs.edit().apply { values.forEach { (key, value) ->
                    when (value) {
                        null -> remove(key)
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is String -> putString(key, value)
                    }
                } }.commit()
            }
        }
    }

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
