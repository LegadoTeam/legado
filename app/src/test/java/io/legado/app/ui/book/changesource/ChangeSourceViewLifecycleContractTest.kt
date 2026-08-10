package io.legado.app.ui.book.changesource

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
        assertTrue(bookDestroy.contains("waitDialog?.dismiss()"))
        assertTrue(chapterDestroy.contains("unregisterAdapterDataObserver"))
        assertTrue(chapterDestroy.contains("binding.recyclerView.adapter = null"))
        assertTrue(chapterDestroy.contains("binding.recyclerViewToc.adapter = null"))
        assertTrue(chapter.contains("addCallback(viewLifecycleOwner)"))
    }

    @Test
    fun `pending result is delivered once after a collector gap`() {
        val event = PendingEvent("result")

        assertEquals("result", event.take())
        assertNull(event.take())
    }

    private fun source(fileName: String): String {
        return projectFile("src/main/java/io/legado/app/ui/book/changesource/$fileName")
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
