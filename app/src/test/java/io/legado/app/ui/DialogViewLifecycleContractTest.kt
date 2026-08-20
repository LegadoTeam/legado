package io.legado.app.ui

import io.legado.app.ui.book.read.ContentDraftState
import io.legado.app.ui.book.read.ContentEditTarget
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
        val created = source.section("override fun onFragmentCreated", "override fun onViewStateRestored")
        val restored = source.section("override fun onViewStateRestored", "override fun onSaveInstanceState")
        val savedState = source.section("override fun onSaveInstanceState", "override fun onDestroyView")
        val cancel = source.section("override fun onCancel", "private fun save")
        val viewModel = source.section("class ContentEditViewModel", "\n    }\n\n}")
        val resetMenu = source.section("R.id.menu_reset", "R.id.menu_copy_all")
        val editTitle = source.section("private fun editTitle", "override fun onCancel")
        val save = source.section("private fun save", "class ContentEditViewModel")
        val activity = source("book/read/ReadBookActivity.kt")
        val readView = source("book/read/page/ReadView.kt")

        assertTrue(created.contains("val owner = viewLifecycleOwner"))
        assertTrue(created.contains("val contentView = binding.contentView"))
        assertTrue(created.contains("editTarget.bookUrl"))
        assertTrue(created.contains("editTarget.chapterIndex"))
        assertTrue(created.contains("editTarget.chapterPos"))
        assertTrue(created.contains("contentLiveData.observe(owner)"))
        assertTrue(created.contains("titleLiveData.observe(owner)"))
        assertTrue(created.contains("return@observe"))
        assertTrue(created.contains("owner.lifecycle.currentState.isAtLeast"))
        assertTrue(created.contains("contentView.post"))
        assertFalse(created.contains("binding.contentView.post"))
        assertFalse(created.contains("contentView.doAfterTextChanged"))
        assertFalse(created.contains("withStateAtLeast"))
        assertTrue(restored.contains("super.onViewStateRestored(savedInstanceState)"))
        assertTrue(restored.contains("viewModel.restoreDraft"))
        assertTrue(restored.contains("savedInstanceState.getBoolean(STATE_HAS_CHANGES)"))
        assertTrue(restored.contains("viewModel.draftText?.let"))
        assertTrue(restored.contains("contentView.doAfterTextChanged"))
        assertTrue(restored.contains("viewModel.initContent(editTarget)"))
        assertTrue(savedState.contains("outState.putBoolean(STATE_HAS_DRAFT, viewModel.hasDraft)"))
        assertTrue(savedState.contains("outState.putBoolean(STATE_HAS_CHANGES, viewModel.hasChanges)"))
        assertTrue(cancel.contains("if (viewModel.hasChanges) save()"))
        assertTrue(viewModel.contains("private var contentTask"))
        assertTrue(viewModel.contains("private var pendingReset"))
        assertTrue(viewModel.contains("if (!reset && (draftState.hasDraft || contentTask?.isActive == true))"))
        assertTrue(viewModel.contains("draft = draftState.newRequest()"))
        assertTrue(viewModel.contains("if (contentTask?.isActive == true)"))
        assertTrue(viewModel.contains("pendingReset = request"))
        assertTrue(viewModel.contains("draftState.applyLoaded(request.draft"))
        assertTrue(viewModel.contains("contentLiveData.value = content"))
        assertTrue(viewModel.contains("startContent(next)"))
        assertTrue(viewModel.contains("request.target.bookUrl"))
        assertTrue(viewModel.contains("request.target.chapterIndex"))
        assertTrue(resetMenu.contains("viewModel.initContent(editTarget, true)"))
        assertFalse(resetMenu.contains("ReadBook.loadContent"))
        assertTrue(source.contains("editTitleDialog?.dismiss()"))
        assertTrue(source.contains("override fun onDestroyView()"))
        assertTrue(editTitle.contains("if (editTitleDialog != null) return"))
        assertTrue(editTitle.contains("Coroutine.async"))
        assertTrue(editTitle.contains("withContext(Main)"))
        assertTrue(editTitle.contains("editTarget.matches("))
        assertTrue(editTitle.contains("editTarget.chapterIndex"))
        assertTrue(editTitle.contains("viewModel.titleLiveData.value = title"))
        assertTrue(editTitle.contains("val title = alertBinding.editView.text.toString()"))
        assertFalse(created.contains("\n            lifecycleScope.launch"))
        assertFalse(editTitle.contains("binding.toolBar.title"))
        assertFalse(editTitle.contains("viewLifecycleOwner.lifecycleScope.launch"))
        assertFalse(editTitle.contains("\n                lifecycleScope.launch"))
        assertTrue(editTitle.contains("if (editTitleDialog === dialog)"))
        assertTrue(save.contains("it.bookUrl == editTarget.bookUrl"))
        assertTrue(save.contains("appDb.bookDao.getBook(editTarget.bookUrl)"))
        assertTrue(save.contains("editTarget.bookUrl, editTarget.chapterIndex"))
        assertTrue(save.contains("editTarget.matches("))
        assertFalse(save.contains("val chapterIndex = ReadBook.durChapterIndex"))
        assertTrue(activity.contains("ContentEditDialog.newInstance()"))
        assertTrue(readView.contains("ContentEditDialog.newInstance()"))
        assertFalse(activity.contains("showDialogFragment(ContentEditDialog())"))
        assertFalse(readView.contains("showDialogFragment(ContentEditDialog())"))
    }

    @Test
    fun `content editor target does not follow the global reader chapter`() {
        val target = ContentEditTarget("book-a", chapterIndex = 3, chapterPos = 120)

        assertTrue(target.matches("book-a", 3))
        assertFalse(target.matches("book-a", 4))
        assertFalse(target.matches("book-b", 3))
    }

    @Test
    fun `newer content request invalidates an older result`() {
        val state = ContentDraftState()
        state.restore("original")
        val older = state.newRequest()
        val newer = state.newRequest()

        assertNull(state.applyLoaded(older, "older content"))
        assertEquals("newer content", state.applyLoaded(newer, "newer content"))
        assertEquals("newer content", state.text)
    }

    @Test
    fun `stale content result does not replace an edited draft`() {
        val state = ContentDraftState()
        state.restore("original")
        val request = state.newRequest()

        state.update("edited draft")

        assertNull(state.applyLoaded(request, "loaded content"))
        assertEquals("edited draft", state.text)
    }

    @Test
    fun `content result applies when the draft has not changed`() {
        val state = ContentDraftState()
        state.restore("edited draft")
        val request = state.newRequest()

        assertEquals("reset content", state.applyLoaded(request, "reset content"))
        assertEquals("reset content", state.text)
        assertFalse(state.hasChanges)
    }

    @Test
    fun `content draft only changes after a real edit`() {
        val state = ContentDraftState()
        state.restore("loaded content")

        assertFalse(state.hasChanges)

        state.update("edited content")
        assertTrue(state.hasChanges)

        state.update("loaded content")
        assertFalse(state.hasChanges)
    }

    @Test
    fun `editing before content loads is still a change`() {
        val state = ContentDraftState()

        state.update("early edit")

        assertTrue(state.hasChanges)
    }

    @Test
    fun `restored dirty draft remains changed`() {
        val state = ContentDraftState()

        state.restore("restored edit", hasChanges = true)

        assertTrue(state.hasChanges)
    }

    @Test
    fun `restored draft is kept as the authoritative text`() {
        val state = ContentDraftState()

        assertTrue(state.restore("restored draft"))
        assertFalse(state.restore("older framework state"))

        assertEquals("restored draft", state.text)
        assertTrue(state.hasDraft)
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

    @Test
    fun `text dialog search is help only and rejects stale posted scrolls`() {
        val source = source("widget/dialog/TextDialog.kt")
        val layout = projectFile("src/main/res/layout/dialog_text_view.xml").readText()
        val markdownSetup = source.section(
            "Mode.MD.name -> {",
            "viewLifecycleOwner.lifecycleScope.launch",
        )
        val scroll = source.section(
            "private fun scrollToCurrentMatch()",
            "private fun renderMarkdown",
        )

        assertTrue(markdownSetup.contains("if (showToc)"))
        assertTrue(markdownSetup.contains("setupSearch(savedInstanceState)"))
        assertTrue(scroll.indexOf("val offset =") < scroll.indexOf("textView.post"))
        assertTrue(scroll.contains("searchRanges.getOrNull(searchIndex)?.first != offset"))
        assertTrue(scroll.contains("!textView.isAttachedToWindow"))
        assertTrue(source.contains("outState.putString(STATE_SEARCH_QUERY, searchQuery)"))
        assertTrue(source.contains("if (position != 0 && searchQuery.isNotBlank())"))
        assertTrue(source.contains("if (searchQuery.isNotBlank() && selectedSection != 0)"))
        assertTrue(source.contains("else if (renderJob?.isActive != true)"))
        assertTrue(layout.contains("android:saveEnabled=\"false\""))
    }

    @Test
    fun `text dialog retints icons when overflow opens`() {
        val source = source("widget/dialog/TextDialog.kt")

        assertTrue(source.contains("binding.toolBar.installMd3OverflowMenu("))
        assertTrue(source.contains("onOpenCustomMenu = { it.applyOpenTint(requireContext()) }"))
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
