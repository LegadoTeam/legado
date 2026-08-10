package io.legado.app.ui

import io.legado.app.ui.book.read.ContentDraftState
import io.legado.app.ui.book.read.PendingContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DialogViewLifecycleContractTest {

    @Test
    fun `dialog data loaders are cancelled with their views`() {
        val cover = source("config/CoverRuleConfigDialog.kt")
            .section("private fun initData()", "\n    }")
        val search = source("book/search/SearchScopeDialog.kt")

        assertTrue(cover.contains("viewLifecycleOwner.lifecycleScope.launch"))
        assertFalse(cover.contains("\n        lifecycleScope.launch"))

        val initData = search.section("private fun initData()", "@SuppressLint")
        val upBookSource = search.section("private fun upBookSource", "inner class RecyclerAdapter")
        assertTrue(initData.contains("viewLifecycleOwner.lifecycleScope.launch"))
        assertTrue(upBookSource.contains("viewLifecycleOwner.lifecycleScope.launch"))
        assertTrue(upBookSource.contains("viewLifecycleOwner.lifecycle"))
        assertFalse(upBookSource.contains("\n        sourceFlowJob = lifecycleScope.launch"))
    }

    @Test
    fun `content editor callbacks only update the current view`() {
        val source = source("book/read/ContentEditDialog.kt")
        val created = source.section("override fun onFragmentCreated", "override fun onDestroyView")
        val viewModel = source.section("class ContentEditViewModel", "\n    }\n\n}")
        val resetMenu = source.section("R.id.menu_reset", "R.id.menu_copy_all")
        val editTitle = source.section("private fun editTitle", "override fun onCancel")

        assertTrue(created.contains("val owner = viewLifecycleOwner"))
        assertTrue(created.contains("val contentView = binding.contentView"))
        assertTrue(created.contains("viewModel.draftText?.let(contentView::setText)"))
        assertTrue(created.contains("contentView.doAfterTextChanged"))
        assertTrue(created.contains("contentLiveData.observe(owner)"))
        assertTrue(created.contains("titleLiveData.observe(owner)"))
        assertTrue(created.contains("withStateAtLeast(Lifecycle.State.RESUMED)"))
        assertTrue(created.contains("val content = event.take(viewModel.draftRevision)"))
        assertTrue(created.contains("owner.lifecycle.currentState.isAtLeast"))
        assertTrue(created.contains("contentView.post"))
        assertFalse(created.contains("binding.contentView.post"))
        assertTrue(created.contains("if (savedInstanceState == null) viewModel.initContent()"))
        assertTrue(viewModel.contains("private var contentTask"))
        assertTrue(viewModel.contains("if (!reset && contentLiveData.value != null) return"))
        assertTrue(viewModel.contains("if (contentTask?.isActive == true)"))
        assertTrue(viewModel.contains("val draftRevision = draftState.snapshot()"))
        assertTrue(viewModel.contains("draftState.applyLoaded(draftRevision"))
        assertTrue(viewModel.contains("if (reset) {\n                    ReadBook.loadContent"))
        assertTrue(viewModel.contains("contentLiveData.value = PendingContent("))
        assertTrue(resetMenu.contains("viewModel.initContent(true)"))
        assertFalse(resetMenu.contains("ReadBook.loadContent"))
        assertTrue(source.contains("editTitleDialog?.dismiss()"))
        assertTrue(source.contains("override fun onDestroyView()"))
        assertTrue(editTitle.contains("if (editTitleDialog != null) return"))
        assertTrue(editTitle.contains("val bookUrl = chapter.bookUrl"))
        assertTrue(editTitle.contains("val chapterIndex = chapter.index"))
        assertTrue(editTitle.contains("Coroutine.async"))
        assertTrue(editTitle.contains("withContext(Main)"))
        assertTrue(editTitle.contains("ReadBook.book?.bookUrl == bookUrl"))
        assertTrue(editTitle.contains("ReadBook.durChapterIndex == chapterIndex"))
        assertTrue(editTitle.contains("viewModel.titleLiveData.value = title"))
        assertTrue(editTitle.contains("val title = alertBinding.editView.text.toString()"))
        assertFalse(created.contains("\n            lifecycleScope.launch"))
        assertFalse(editTitle.contains("binding.toolBar.title"))
        assertFalse(editTitle.contains("viewLifecycleOwner.lifecycleScope.launch"))
        assertFalse(editTitle.contains("\n                lifecycleScope.launch"))
        assertTrue(editTitle.contains("if (editTitleDialog === dialog)"))
    }

    @Test
    fun `content result is delivered only once across recreated views`() {
        val result = PendingContent("original", revision = 0)

        assertEquals("original", result.take(currentRevision = 0))
        assertNull(result.take(currentRevision = 0))
    }

    @Test
    fun `stale content result does not replace an edited draft`() {
        val state = ContentDraftState()
        state.initialize("original")
        val request = state.snapshot()

        state.update("edited draft")

        assertNull(state.applyLoaded(request, "loaded content"))
        assertEquals("edited draft", state.text)
    }

    @Test
    fun `content result applies when the draft has not changed`() {
        val state = ContentDraftState()
        state.initialize("edited draft")
        val request = state.snapshot()

        assertEquals("reset content", state.applyLoaded(request, "reset content"))
        assertEquals("reset content", state.text)
    }

    @Test
    fun `published content is ignored after a later draft edit`() {
        val state = ContentDraftState()
        state.initialize("original")
        val request = state.snapshot()
        val loaded = requireNotNull(state.applyLoaded(request, "loaded content"))
        val result = PendingContent(loaded, state.snapshot())

        state.update("later edit")

        assertNull(result.take(state.snapshot()))
        assertEquals("later edit", state.text)
    }

    @Test
    fun `text dialog countdown is cancelled with the view`() {
        val source = source("widget/dialog/TextDialog.kt")
        val countdown = source.section("if (time > 0)", "} else {")

        assertTrue(countdown.contains("val owner = viewLifecycleOwner"))
        assertTrue(countdown.contains("owner.lifecycleScope.launch"))
        assertTrue(countdown.contains("val badgeView = binding.badgeView"))
        assertTrue(countdown.contains("badgeView.setBadgeCount"))
        assertFalse(countdown.contains("view.post"))
    }

    private fun source(relativePath: String): String {
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
