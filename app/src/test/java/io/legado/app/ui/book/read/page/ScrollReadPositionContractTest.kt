package io.legado.app.ui.book.read.page

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScrollReadPositionContractTest {

    @Test
    fun `initial content waits for the reader layout`() {
        val activity = source("app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
        val onPostCreate = activity.substringAfter("override fun onPostCreate")
            .substringBefore("override fun onNewIntent")
        val layout = onPostCreate.indexOf("binding.readView.doOnLayout")
        val idle = onPostCreate.indexOf("Looper.myQueue().addIdleHandler")
        val init = onPostCreate.indexOf("viewModel.initData(intent)")

        assertTrue(layout >= 0)
        assertTrue(layout < idle)
        assertTrue(idle < init)
    }

    @Test
    fun `scroll reading saves and restores the first visible line`() {
        val activity = source("app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
        val onPause = activity.substringAfter("override fun onPause()")
            .substringBefore("override fun onCompatCreateOptionsMenu")
        assertTrue(onPause.contains("updateScrollReadPosition()"))
        assertTrue(onPause.contains("ReadBook.saveRead()"))
        assertTrue(
            onPause.indexOf("updateScrollReadPosition()") <
                onPause.indexOf("ReadBook.saveRead()")
        )
        assertTrue(activity.contains("binding.readView.getReadPosition()"))
        assertTrue(activity.contains("ReadBook.msg != null || !ReadBook.isLayoutAvailable"))
        assertTrue(activity.contains("ReadBook.durChapterPos = line.chapterPosition"))
        assertTrue(activity.contains("resetPageOffset = ReadBook.isScroll"))
        val configUpdate = activity.substringAfter(
            "observeEvent<ArrayList<Int>>(EventBus.UP_CONFIG)"
        ).substringBefore("observeEvent<Int>(EventBus.ALOUD_STATE)")
        assertTrue(configUpdate.contains("if (5 in values && isInitFinish)"))
        assertTrue(
            configUpdate.indexOf("updateScrollReadPosition()") <
                configUpdate.indexOf("values.forEach")
        )
        assertTrue(
            configUpdate.contains(
                "readPositionVersion = readView.getReadPositionVersion()"
            )
        )

        val pageView = source("app/src/main/java/io/legado/app/ui/book/read/page/PageView.kt")
        assertTrue(pageView.contains("chapterPosition: Int = ReadBook.durChapterPos"))
        assertTrue(pageView.contains("restorePageOffset(chapterPosition)"))

        val contentView = source(
            "app/src/main/java/io/legado/app/ui/book/read/page/ContentTextView.kt"
        )
        assertTrue(contentView.contains("firstOrNull { it.chapterPosition == chapterPos }"))
        assertTrue(
            contentView.contains(
                "chapterPos in it.chapterPosition..<it.chapterPosition + it.charSize"
            )
        )
        assertTrue(contentView.contains("line === textPage.lines.firstOrNull()"))
        assertTrue(contentView.contains("scroll((ChapterProvider.paddingTop - line.lineTop).toInt())"))
        val readPosition = contentView.substringAfter("fun getReadPosition()")
            .substringBefore("fun getReadAloudPos()")
        assertTrue(readPosition.contains("if (textPage.isMsgPage) return null"))
        assertTrue(readPosition.contains("firstOrNull { it.isVisible(offset) }"))
        assertTrue(readPosition.contains("?: textPage.lines.lastOrNull()"))
    }

    private fun source(relativePath: String): String {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, relativePath).readText()
    }
}
