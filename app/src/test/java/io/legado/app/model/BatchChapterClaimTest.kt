package io.legado.app.model

import io.legado.app.data.entities.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手动缓存与阅读预下载共用同一份"进行中"标记(onDownloadSet)时的领取约定。
 *
 * 批量任务必须原子领取:只拿没被占用的章节,结束时只归还自己领到的。
 * 无条件领取/归还会清掉另一个任务持有的标记,让同一章被两个任务重复下载。
 */
class BatchChapterClaimTest {

    private fun chapter(index: Int) = BookChapter(
        url = "/read/$index.html",
        title = "第${index}章",
        index = index,
        bookUrl = "https://example.com/book/1"
    )

    private fun indexesOf(chapters: List<BookChapter>) = chapters.map { it.index }

    @Test
    fun `claim skips chapters already held by another task`() {
        //手动缓存已持有第2章
        val onDownloadSet = linkedSetOf(2)
        val waitDownloadSet = linkedSetOf(1, 2, 3)

        val claimed = BatchChapterClaim.claim(
            listOf(chapter(1), chapter(2), chapter(3)),
            onDownloadSet,
            waitDownloadSet
        )

        assertEquals(listOf(1, 3), indexesOf(claimed))
        assertEquals(setOf(1, 2, 3), onDownloadSet)
        //别人持有的第2章仍留在等待队列里,归它的任务处置
        assertEquals(setOf(2), waitDownloadSet)
    }

    @Test
    fun `release never clears the marker held by another task`() {
        val onDownloadSet = linkedSetOf(2)
        val waitDownloadSet = linkedSetOf(1, 2, 3)
        val claimed = BatchChapterClaim.claim(
            listOf(chapter(1), chapter(2), chapter(3)),
            onDownloadSet,
            waitDownloadSet
        )

        BatchChapterClaim.release(claimed, onDownloadSet)

        assertEquals("只能归还本次领取的章节", setOf(2), onDownloadSet)
    }

    @Test
    fun `overlapping batches never claim the same chapter twice`() {
        val onDownloadSet = linkedSetOf<Int>()
        val waitDownloadSet = linkedSetOf(1, 2, 3, 4)
        val chapters = listOf(chapter(1), chapter(2), chapter(3), chapter(4))

        val manualCache = BatchChapterClaim.claim(chapters, onDownloadSet, waitDownloadSet)
        val readPreDownload = BatchChapterClaim.claim(chapters, onDownloadSet, waitDownloadSet)

        assertEquals(listOf(1, 2, 3, 4), indexesOf(manualCache))
        assertTrue("先到者独占,后到者一章都拿不到", readPreDownload.isEmpty())
    }

    @Test
    fun `releasing an empty claim leaves other markers untouched`() {
        val onDownloadSet = linkedSetOf(1, 2)

        BatchChapterClaim.release(emptyList(), onDownloadSet)

        assertEquals(setOf(1, 2), onDownloadSet)
    }

    @Test
    fun `claim removes only claimed chapters from the wait queue`() {
        val onDownloadSet = linkedSetOf(3)
        val waitDownloadSet = linkedSetOf(1, 2, 3, 4)

        val claimed = BatchChapterClaim.claim(
            listOf(chapter(1), chapter(3)),
            onDownloadSet,
            waitDownloadSet
        )

        assertEquals(listOf(1), indexesOf(claimed))
        //第3章被别人持有,不能从等待队列里替它摘掉;第2、4章本轮没参与
        assertEquals(setOf(2, 3, 4), waitDownloadSet)
    }

    @Test
    fun `claim is idempotent for a task that already holds the chapters`() {
        val onDownloadSet = linkedSetOf<Int>()
        val waitDownloadSet = linkedSetOf(1, 2)
        val chapters = listOf(chapter(1), chapter(2))

        BatchChapterClaim.claim(chapters, onDownloadSet, waitDownloadSet)
        val second = BatchChapterClaim.claim(chapters, onDownloadSet, waitDownloadSet)

        assertTrue(second.isEmpty())
        assertEquals(setOf(1, 2), onDownloadSet)
    }
}
