package io.legado.app.ui.book.changesource

import io.legado.app.data.entities.SearchBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChangeSourceViewLifecycleContractTest {

    @Test
    fun `search results stay in the view model across view recreation`() {
        val source = source("ChangeBookSourceViewModel.kt")
            .section("val searchDataFlow", "override fun onCleared")

        assertTrue(source.contains(".shareIn("))
        assertTrue(source.contains("scope = viewModelScope"))
        assertTrue(source.contains("started = SharingStarted.Lazily"))
        assertTrue(source.contains("replay = 1"))
    }

    @Test
    fun `dialog collectors are cancelled with the current view`() {
        val book = source("ChangeBookSourceDialog.kt")
            .section("private fun initLiveData()", "private fun showChangeSourceLoading")
        val chapter = source("ChangeChapterSourceDialog.kt")
            .section("private fun initLiveData()", "private fun showEmptySearchGroupDialog")

        listOf(book, chapter).forEach { initLiveData ->
            assertTrue(initLiveData.contains("val owner = viewLifecycleOwner"))
            assertTrue(initLiveData.contains("owner.lifecycleScope.launch"))
            assertTrue(initLiveData.contains("owner.lifecycle.currentStateFlow"))
            assertFalse(initLiveData.contains("\n        lifecycleScope.launch"))
        }
        assertTrue(book.contains("owner.repeatOnLifecycle(STARTED)"))
    }

    @Test
    fun `progress replay is not discarded when a view restarts`() {
        val dialog = source("ChangeBookSourceDialog.kt")
        val progress = dialog.section(
            "viewModel.changeSourceProgress",
            "appDb.bookSourceDao.flowEnabledGroups()",
        )

        assertFalse(progress.contains(".drop("))
        assertTrue(progress.contains("if (count == 0 && name.isEmpty())"))
        assertTrue(progress.contains("callBack?.oldBook?.originName"))
    }

    @Test
    fun `book source dialog locates the current source after first results`() {
        val dialog = source("ChangeBookSourceDialog.kt")
        val collector = dialog.section(
            "viewModel.searchDataFlow",
            "viewModel.changeSourceProgress",
        )

        assertTrue(dialog.contains("private var autoScrollCurrentSource = true"))
        assertTrue(collector.contains("if (autoScrollCurrentSource && it.isNotEmpty())"))
        assertTrue(collector.contains("binding.recyclerView.post"))
        assertTrue(collector.contains("if (scrollToDurSource()) autoScrollCurrentSource = false"))
        assertTrue(dialog.contains("autoScrollCurrentSource = true"))
        assertTrue(
            dialog.section("private fun scrollToDurSource(): Boolean", "override fun changeTo")
                .contains("if (searchBook.bookUrl == oldBookUrl)")
        )
    }

    @Test
    fun `current book source keeps a subtle background and check mark`() {
        val adapter = source("ChangeBookSourceAdapter.kt")
        assertTrue(adapter.contains("viewSelectedBackground"))
        assertTrue(adapter.contains("ColorUtils.withAlpha(context.accentColor, 0.1f)"))
        assertTrue(adapter.contains("ivChecked.visible()"))
    }

    @Test
    fun `pending business results wait for the current host to resume`() {
        val book = source("ChangeBookSourceDialog.kt")
        val chapter = source("ChangeChapterSourceDialog.kt")
        val observers = listOf(
            book.section(
                "viewModel.changeSourceResult.observe(owner)",
                "viewModel.changeSourceProgress",
            ),
            chapter.section(
                "viewModel.contentResult.observe(owner)",
                "viewModel.changeSourceResult.observe(owner)",
            ),
            chapter.section(
                "viewModel.changeSourceResult.observe(owner)",
                "viewModel.batchCaching.observe(owner",
            ),
            chapter.section(
                "viewModel.batchCacheResult.observe(owner)",
                "viewModel.searchDataFlow",
            ),
        )

        observers.forEach { observer ->
            assertTrue(observer.contains("withStateAtLeast(RESUMED)"))
            assertTrue(
                observer.indexOf("withStateAtLeast(RESUMED)") <
                    observer.indexOf("event.take()")
            )
        }
    }

    @Test
    fun `closing or replacing a toc cancels its pending content request`() {
        val viewModel = source("ChangeChapterSourceViewModel.kt")
        val loadToc = viewModel.section("fun loadToc", "fun clearToc")
        val clearToc = viewModel.section("fun clearToc", "private fun cancelContent")
        val cancelContent = viewModel.section("private fun cancelContent", "fun cacheContents")

        assertTrue(loadToc.contains("cancelContent()"))
        assertTrue(clearToc.contains("cancelContent()"))
        assertTrue(cancelContent.contains("contentTask?.cancel()"))
        assertTrue(cancelContent.contains("contentTask = null"))
        assertTrue(cancelContent.contains("contentLoading.value = false"))
    }

    @Test
    fun `deleting the current source waits for a successful replacement`() {
        val bookDialog = source("ChangeBookSourceDialog.kt")
        val chapterDialog = source("ChangeChapterSourceDialog.kt")
        val viewModel = source("ChangeBookSourceViewModel.kt")

        listOf(bookDialog, chapterDialog).forEach { dialog ->
            val deleteSource = dialog.section(
                "override fun deleteSource",
                "override fun setBookScore",
            )

            assertTrue(deleteSource.contains("viewModel.autoChangeSource("))
            assertTrue(deleteSource.contains(", searchBook)"))
            assertTrue(deleteSource.contains("else {\n            viewModel.del(searchBook)"))
            assertTrue(dialog.contains("SourceChangeCompletion("))
            assertTrue(dialog.contains("completion::success"))
        }

        val autoChange = viewModel.section("fun autoChangeSource", "fun setBookScore")
        val delete = viewModel.section("fun del", "fun autoChangeSource")
        val loading = bookDialog.section(
            "private fun showChangeSourceLoading",
            "private val startStopMenuItem",
        )
        assertTrue(delete.contains("Coroutine.async"))
        assertFalse(delete.contains("execute {"))
        assertTrue(autoChange.contains("deleteAfterChange: SearchBook"))
        assertTrue(autoChange.contains("it.origin != deleteAfterChange.origin"))
        assertTrue(autoChange.contains("deleteAfterChange = deleteAfterChange"))
        assertTrue(loading.contains("dialog.setCancelable(cancelable)"))
        assertTrue(loading.contains("if (cancelable)"))
        assertTrue(loading.contains("viewModel.cancelChangeSource()"))
    }

    @Test
    fun `old source deletion runs once only after migration success`() {
        val source = SearchBook(origin = "old")
        val deleted = mutableListOf<SearchBook>()
        val completion = SourceChangeCompletion(source, deleted::add)

        assertTrue(deleted.isEmpty())
        completion.success()
        completion.success()
        assertEquals(listOf(source), deleted)

        SourceChangeCompletion(null, deleted::add).success()
        assertEquals(listOf(source), deleted)
    }

    @Test
    fun `hosts acknowledge source changes only from successful migration callbacks`() {
        val viewModels = listOf(
            appSource("book/read/ReadBookViewModel.kt")
                .section("fun changeTo(", "/**\n     * 自动换源"),
            appSource("book/audio/AudioPlayViewModel.kt")
                .section("fun changeTo(", "fun removeFromBookshelf"),
            appSource("book/info/BookInfoViewModel.kt")
                .section("fun changeTo(", "fun saveBook"),
            appSource("book/manga/ReadMangaViewModel.kt")
                .section("fun changeTo(", "private fun checkLocalBookFileExist"),
        )
        viewModels.forEach { changeTo ->
            assertTrue(changeTo.contains("onSuccess: () -> Unit"))
            assertTrue(changeTo.contains(".onSuccess {\n            onSuccess()"))
            assertFalse(changeTo.contains(".onFinally {\n            onSuccess()"))
        }

        val readActivity = appSource("book/read/ReadBookActivity.kt")
            .section("override fun changeTo(", "override fun replaceContent")
        val audioActivity = appSource("book/audio/AudioPlayActivity.kt")
            .section("override fun changeTo(", "override fun finish")
        val infoActivity = appSource("book/info/BookInfoActivity.kt")
            .section("override fun changeTo(", "override fun coverChangeTo")
        val mangaActivity = appSource("book/manga/ReadMangaActivity.kt")
            .section("override fun changeTo(", "override fun updateColorFilter")

        assertTrue(readActivity.contains("viewModel.changeTo(book, toc, onSuccess)"))
        assertTrue(audioActivity.contains("viewModel.changeTo(source, book, toc, onSuccess)"))
        assertTrue(infoActivity.contains("viewModel.changeTo(source, book, toc, onSuccess)"))
        assertTrue(mangaActivity.contains("viewModel.changeTo(book, toc, onSuccess)"))
        listOf(readActivity, audioActivity).forEach { changeTo ->
            assertTrue(
                changeTo.indexOf("appDb.bookDao.insert(book)") <
                    changeTo.indexOf("onSuccess()")
            )
        }
    }

    @Test
    fun `adapter delete confirmation is released with the recycler view`() {
        val adapter = source("ChangeBookSourceAdapter.kt")
        val detach = adapter.section(
            "override fun onDetachedFromRecyclerView",
            "interface CallBack",
        )

        assertTrue(adapter.contains("if (deleteSourceDialog == null)"))
        assertTrue(adapter.contains("if (deleteSourceDialog === dialog)"))
        assertTrue(detach.contains("deleteSourceDialog?.dismiss()"))
        assertTrue(detach.contains("deleteSourceDialog = null"))
    }

    @Test
    fun `view model owns asynchronous results instead of fragment callbacks`() {
        val bookViewModel = source("ChangeBookSourceViewModel.kt")
        val chapterViewModel = source("ChangeChapterSourceViewModel.kt")
        val bookDialog = source("ChangeBookSourceDialog.kt")
        val chapterDialog = source("ChangeChapterSourceDialog.kt")

        assertTrue(bookViewModel.contains("searchFinishData.postValue(PendingEvent("))
        assertTrue(bookViewModel.contains("changeSourceResult.value = PendingEvent("))
        assertFalse(bookViewModel.contains("searchFinishCallback"))
        assertFalse(bookDialog.contains("viewModel.getToc(book,"))
        assertFalse(chapterDialog.contains("viewModel.getToc(book,"))
        assertFalse(chapterDialog.contains("viewModel.getContent("))
        assertFalse(chapterDialog.contains("cacheTask?.cancel()"))
        assertTrue(chapterViewModel.contains("contentResult.value = PendingEvent("))
        assertTrue(chapterViewModel.contains("batchCacheResult.value = PendingEvent("))
        assertTrue(chapterViewModel.contains("tocState.value = ChapterTocState.Success("))
        assertTrue(chapterViewModel.contains("withContext(NonCancellable)"))
        assertTrue(chapterViewModel.contains("if (cacheCommitStarted) return"))
    }

    @Test
    fun `per view adapter observers are released`() {
        val book = source("ChangeBookSourceDialog.kt")
        val chapter = source("ChangeChapterSourceDialog.kt")
        val bookDestroy = book.section(
            "override fun onDestroyView()",
            "private fun showTitle()",
        )
        val chapterDestroy = chapter.section(
            "override fun onDestroyView()",
            "private fun showTitle()",
        )

        assertTrue(bookDestroy.contains("unregisterAdapterDataObserver"))
        assertTrue(bookDestroy.contains("binding.recyclerView.adapter = null"))
        assertTrue(bookDestroy.contains("searchFinishDialog?.dismiss()"))
        assertTrue(bookDestroy.contains("waitDialog?.dismiss()"))
        assertTrue(chapterDestroy.contains("unregisterAdapterDataObserver"))
        assertTrue(chapterDestroy.contains("binding.recyclerView.adapter = null"))
        assertTrue(chapterDestroy.contains("binding.recyclerViewToc.adapter = null"))
        assertTrue(chapterDestroy.contains("searchFinishDialog?.dismiss()"))
        assertTrue(chapter.contains("addCallback(viewLifecycleOwner)"))
    }

    @Test
    fun `pending result can be inspected before one-time delivery`() {
        val event = PendingEvent("result")

        assertEquals("result", event.peek())
        assertEquals("result", event.peek())
        assertEquals("result", event.take())
        assertNull(event.peek())
        assertNull(event.take())
    }

    @Test
    fun `search prompt stays pending until the dialog finishes`() {
        listOf(
            source("ChangeBookSourceDialog.kt"),
            source("ChangeChapterSourceDialog.kt"),
        ).forEach { dialog ->
            val prompt = if (dialog.contains("private fun showChangeSourceLoading")) {
                dialog.section(
                    "private fun showEmptySearchGroupDialog(",
                    "private fun showChangeSourceLoading",
                )
            } else {
                dialog.section(
                    "private fun showEmptySearchGroupDialog(",
                    "private val startStopMenuItem",
                )
            }
            assertTrue(prompt.contains("if (event.peek() != true)"))
            assertTrue(prompt.contains("onCancelled { event.take() }"))
            assertTrue(prompt.contains("if (searchFinishDialog === dialog)"))
        }
    }

    @Test
    fun `content errors clear the replayed toc state`() {
        val observer = source("ChangeChapterSourceDialog.kt").section(
            "viewModel.contentResult.observe(owner)",
            "viewModel.changeSourceResult.observe(owner)",
        )
        val error = observer.section(
            "is ChapterContentResult.Error",
            "null -> Unit",
        )

        assertTrue(error.contains("binding.clToc.gone()"))
        assertTrue(error.contains("viewModel.clearToc()"))
    }

    @Test
    fun `chapter automation advances only while the current view is resumed`() {
        val dialog = source("ChangeChapterSourceDialog.kt")
        val observer = dialog.section(
            "viewModel.automationState.observe(owner)",
            "viewModel.batchCacheResult.observe(owner)",
        )
        val viewModel = source("ChangeChapterSourceViewModel.kt")
        val runner = viewModel.section(
            "fun runNextAutomationIfReady()",
            "fun cacheAutomationSelection",
        )

        assertTrue(observer.contains("withStateAtLeast(RESUMED)"))
        assertTrue(
            observer.indexOf("withStateAtLeast(RESUMED)") <
                    observer.indexOf("viewModel.runNextAutomationIfReady()")
        )
        assertFalse(runner.contains("delay("))
        assertFalse(runner.substringAfter("{").contains("runNextAutomationIfReady()"))
    }

    @Test
    fun `chapter automation freezes its target and rejects blank content`() {
        val dialog = source("ChangeChapterSourceDialog.kt")
        val openToc = dialog.section("override fun openToc", "private fun showTocState")
        val viewModel = source("ChangeChapterSourceViewModel.kt")
        val loadToc = viewModel.section("fun loadToc", "fun clearToc")
        val cache = viewModel.section("private fun cacheContents", "fun automationRangeDefaults")
        val stop = viewModel.section("fun stopAutomation", "private fun cacheAutomationPositions")
        val renderState = dialog.section(
            "private fun showAutomationState",
            "private val automationMenuItem",
        )

        assertTrue(openToc.contains("viewModel.isAutomationActive"))
        assertTrue(openToc.contains("batchCaching"))
        assertTrue(loadToc.contains("isAutomationActive"))
        assertTrue(loadToc.contains("batchCaching.value == true"))
        assertTrue(cache.contains("mergedContent.isBlank()"))
        assertTrue(stop.contains("cacheCommitStarted"))
        assertTrue(stop.contains("requestStopAfterCurrent()"))
        assertTrue(renderState.contains("is ChapterSourceAutomationPause.ContentError"))
        assertTrue(renderState.contains("else ->"))
        assertTrue(renderState.contains("tocAdapter.clearSelection()"))
    }

    private fun source(fileName: String): String {
        return appSource("book/changesource/$fileName")
    }

    private fun appSource(relativePath: String): String {
        return projectFile("src/main/java/io/legado/app/ui/$relativePath")
            .readText()
            .replace("\r\n", "\n")
    }

    private fun String.section(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        val end = indexOf(endMarker, start + startMarker.length)
        require(start >= 0 && end > start) {
            "Missing section $startMarker .. $endMarker"
        }
        return substring(start, end)
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
