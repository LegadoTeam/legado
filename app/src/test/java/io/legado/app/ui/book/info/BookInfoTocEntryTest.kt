package io.legado.app.ui.book.info

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookInfoTocEntryTest {

    @Test
    fun `toc entry reports a missing book and preserves the save flow`() {
        val activity = readProjectFile(
            "src/main/java/io/legado/app/ui/book/info/BookInfoActivity.kt"
        )
        val tocEntry = activity
            .substringAfter("tvTocView.setOnClickListener {")
            .substringBefore("tvChangeGroup.setOnClickListener")
        val chapterCheck = tocEntry.indexOf("chapterListData.value.isNullOrEmpty()")
        val bookCheck = tocEntry.indexOf("viewModel.getBook(false)")
        val savedOpen = tocEntry.indexOf("openChapterList()")
        val directOpen = tocEntry.lastIndexOf("openChapterList()")

        assertTrue(chapterCheck >= 0)
        assertTrue(bookCheck > chapterCheck)
        assertTrue(tocEntry.contains("toastOnUi(R.string.book_not_exist)"))
        assertTrue(tocEntry.contains("return@setOnClickListener"))
        assertTrue(tocEntry.contains("viewModel.saveBook(book)"))
        assertTrue(tocEntry.contains("viewModel.saveChapterList"))
        assertTrue(savedOpen >= 0)
        assertTrue(directOpen > savedOpen)
    }

    @Test
    fun `missing book message is localized`() {
        val defaultStrings = readProjectFile("src/main/res/values/strings.xml")
        val chineseStrings = readProjectFile("src/main/res/values-zh/strings.xml")

        assertTrue(
            defaultStrings.contains(
                "<string name=\"book_not_exist\">Book does not exist</string>"
            )
        )
        assertTrue(
            chineseStrings.contains(
                "<string name=\"book_not_exist\">书籍不存在</string>"
            )
        )
    }

    @Test
    fun `toc result preserves normal progress and defers highlight coordinates`() {
        val activity = readProjectFile(
            "src/main/java/io/legado/app/ui/book/info/BookInfoActivity.kt"
        )
        val callback = activity
            .substringAfter("private val tocActivityResult")
            .substringBefore("private val localBookTreeSelect")
        val readFlow = activity
            .substringAfter("private fun readFromChapter(")
            .substringBefore("private fun showWebFileDownloadAlert")
        val normalProgressFlow = readFlow
            .substringAfter("if (!deferHighlightPosition) {")
            .substringBefore("}")
        val notShelfFlow = readFlow
            .substringAfter("if (!viewModel.inBookshelf) {")
            .substringBefore("} else {")
        val shelfFlow = readFlow.substringAfter("} else {")
        assertTrue(callback.contains("index = it[0] as Int"))
        assertTrue(callback.contains("pos = it[1] as Int"))
        assertTrue(callback.contains("changed = it[2] as Boolean"))
        assertTrue(callback.contains("volumeIndex = it[3] as Int"))
        assertTrue(callback.contains("chapterInVolumeIndex = it[4] as Int"))
        assertTrue(callback.contains("HIGHLIGHT_LAYOUT_TITLE_LENGTH_INDEX"))
        assertTrue(callback.contains("HIGHLIGHT_ANCHOR_TEXT_INDEX"))
        assertTrue(normalProgressFlow.contains("book.durChapterIndex = index"))
        assertTrue(normalProgressFlow.contains("book.durChapterPos = pos"))
        assertTrue(normalProgressFlow.contains("book.durVolumeIndex = volumeIndex"))
        assertTrue(normalProgressFlow.contains("book.chapterInVolumeIndex = chapterInVolumeIndex"))
        assertTrue(readFlow.contains("chapterChanged = changed"))
        assertTrue(readFlow.contains("book.addType(BookType.notShelf)"))
        assertTrue(!readFlow.contains("viewModel.saveBook(book)"))
        assertTrue(notShelfFlow.contains("book.save()"))
        assertTrue(notShelfFlow.contains("viewModel.saveChapterList"))
        assertTrue(notShelfFlow.contains("highlightLayoutTitleLength.takeIf"))
        assertTrue(shelfFlow.contains("book.update()"))
        assertTrue(shelfFlow.contains("highlightLayoutTitleLength.takeIf"))
        assertTrue(readFlow.contains("highlightLayoutTitleLength.takeIf { deferHighlightPosition }"))
        assertTrue(readFlow.contains("highlightAnchorText.takeIf { deferHighlightPosition }"))
        assertTrue(readFlow.contains("startReadActivity("))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
