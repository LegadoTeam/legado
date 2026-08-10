package io.legado.app.ui.book.changesource

import org.junit.Assert.assertFalse
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
            .section("private fun initLiveData()", "private val startStopMenuItem")
        val chapter = source("ChangeChapterSourceDialog.kt")
            .section("private fun initLiveData()", "private val startStopMenuItem")

        listOf(book, chapter).forEach { initLiveData ->
            assertTrue(initLiveData.contains("val owner = viewLifecycleOwner"))
            assertTrue(initLiveData.contains("owner.lifecycleScope.launch"))
            assertTrue(initLiveData.contains("owner.lifecycle.currentStateFlow"))
            assertFalse(initLiveData.contains("\n        lifecycleScope.launch"))
        }
        assertTrue(book.contains("owner.repeatOnLifecycle(STARTED)"))
    }

    @Test
    fun `per view callbacks and adapter observers are released`() {
        val book = source("ChangeBookSourceDialog.kt")
        val chapter = source("ChangeChapterSourceDialog.kt")
        val bookDestroy = book.section(
            "override fun onDestroyView()",
            "private fun bindSearchFinishCallback",
        )
        val chapterDestroy = chapter.section(
            "override fun onDestroyView()",
            "private fun bindSearchFinishCallback",
        )

        listOf(book, chapter).forEach { dialog ->
            val callback = dialog.section(
                "private fun bindSearchFinishCallback()",
                "private fun showTitle()",
            )
            assertTrue(callback.contains("val owner = viewLifecycleOwner"))
            assertTrue(callback.contains("owner.lifecycleScope.launch"))
        }
        assertTrue(bookDestroy.contains("unregisterAdapterDataObserver"))
        assertTrue(bookDestroy.contains("binding.recyclerView.adapter = null"))
        assertTrue(bookDestroy.contains("waitDialog?.dismiss()"))
        assertTrue(chapterDestroy.contains("unregisterAdapterDataObserver"))
        assertTrue(chapterDestroy.contains("binding.recyclerView.adapter = null"))
        assertTrue(chapterDestroy.contains("binding.recyclerViewToc.adapter = null"))
        assertTrue(chapter.contains("addCallback(viewLifecycleOwner)"))
    }

    @Test
    fun `chapter callbacks separate durable work from view rendering`() {
        val source = source("ChangeChapterSourceDialog.kt")
        val initBatch = source.section("private fun initBatchMode()", "private fun initLiveData()")
        val openToc = source.section("override fun openToc", "override val oldBookUrl")
        val clickChapter = source.section("override fun clickChapter", "override fun selectionChanged")
        val cache = source.section("private fun cacheSelectedChapters()", "private fun advanceOriginalChapter")
        val cacheSuccess = cache.section("success = {", "error =")

        assertTrue(initBatch.contains("val owner = viewLifecycleOwner"))
        assertTrue(initBatch.contains("owner.lifecycleScope.launch"))
        assertTrue(openToc.contains("val owner = viewLifecycleOwner"))
        assertTrue(openToc.contains("owner.lifecycleScope.launch"))
        assertTrue(clickChapter.contains("callBack?.replaceContent(content)"))
        assertTrue(clickChapter.contains("owner.lifecycleScope.launch"))
        assertTrue(
            clickChapter.indexOf("callBack?.replaceContent(content)") <
                    clickChapter.indexOf("owner.lifecycleScope.launch")
        )
        assertTrue(cacheSuccess.contains("batchCaching = false"))
        assertTrue(cacheSuccess.contains("callBack?.contentCached"))
        assertTrue(cacheSuccess.contains("owner?.lifecycleScope?.launch"))
        assertTrue(
            cacheSuccess.indexOf("batchCaching = false") <
                    cacheSuccess.indexOf("callBack?.contentCached")
        )
        assertTrue(
            cacheSuccess.indexOf("callBack?.contentCached") <
                    cacheSuccess.indexOf("owner?.lifecycleScope?.launch")
        )
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
