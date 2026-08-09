package io.legado.app.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 批量下载在 CacheBookModel 里的状态机约定。
 *
 * CacheBookModel 的构造和回调都要 Android 运行时(postEvent / appDb / 缓存目录),
 * 沿用本仓库既有的 CacheBookReadRetryContractTest 做法,以源码断言锁住关键不变量。
 * 纯逻辑部分已分别由 BatchChapterClaimTest 和 BatchContentContextTest 覆盖。
 */
class CacheBookBatchContractTest {

    private val source by lazy {
        listOf(
            File("src/main/java/io/legado/app/model/CacheBook.kt"),
            File("app/src/main/java/io/legado/app/model/CacheBook.kt")
        ).first { it.isFile }.readText().replace("\r\n", "\n")
    }

    private val manualBatch by lazy {
        section("private fun downloadBatch", "private fun onBatchMissing")
    }

    private val readBatch by lazy {
        section("suspend fun downloadBatchAwait", "suspend fun downloadAwait")
    }

    @Test
    fun `both batch paths claim chapters atomically`() {
        assertTrue(manualBatch.contains("BatchChapterClaim.claim("))
        assertTrue(readBatch.contains("BatchChapterClaim.claim("))
    }

    @Test
    fun `batches only ever act on the chapters they claimed`() {
        //领取结果是唯一的事实来源,拿候选列表去下载会碰到别的任务持有的章节
        assertTrue(manualBatch.contains("WebBook.getContentBatchAwait(bookSource, book, claimed)"))
        assertTrue(readBatch.contains("WebBook.getContentBatchAwait(bookSource, book, claimed)"))
        assertFalse(manualBatch.contains("getContentBatchAwait(bookSource, book, batchChapters)"))
        assertFalse(readBatch.contains("getContentBatchAwait(bookSource, book, chapters)"))
    }

    @Test
    fun `read predownload releases only what it claimed and did not complete`() {
        assertTrue(readBatch.contains("BatchChapterClaim.release("))
        assertTrue(readBatch.contains("claimed.filterNot { completed.contains(it.index) }"))
        //无条件按入参归还会清掉手动缓存持有的标记
        assertFalse(readBatch.contains("chapters.forEach { onDownloadSet.remove(it.index) }"))
    }

    @Test
    fun `read predownload hands back chapters it could not claim`() {
        assertTrue(readBatch.contains("val unclaimed = chapters.filterNot"))
        assertTrue(readBatch.contains("return unclaimed + claimed.filter"))
    }

    @Test
    fun `read predownload gives up cleanly when nothing is claimable`() {
        assertTrue(readBatch.contains("if (claimed.size < 2)"))
        assertTrue(readBatch.contains("BatchChapterClaim.release(claimed, onDownloadSet)"))
    }

    @Test
    fun `cancellation propagates and never marks chapters as failed`() {
        assertTrue(readBatch.contains("catch (e: CancellationException)"))
        assertTrue(readBatch.contains("throw e"))
        //取消不是书源失败,不能进批量黑名单
        assertTrue(manualBatch.contains("onBatchCancel(claimed)"))
        val batchCancel = section("private fun onBatchCancel", "suspend fun downloadBatchAwait")
        assertTrue(batchCancel.contains("onDownloadSet.remove(chapter.index)"))
        assertTrue(batchCancel.contains("waitDownloadSet.add(chapter.index)"))
        assertFalse(batchCancel.contains("batchFallbackSet"))
    }

    @Test
    fun `chapters missed by a batch fall back to single chapter download exactly once`() {
        val batchMissing = section("private fun onBatchMissing", "private fun onBatchCancel")
        assertTrue(batchMissing.contains("onDownloadSet.remove(chapter.index)"))
        assertTrue(batchMissing.contains("batchFallbackSet.add(chapter.index)"))
        assertTrue(batchMissing.contains("waitDownloadSet.add(chapter.index)"))
        //回退过的章节不能再被后续批次捞走,否则会反复空跑
        assertTrue(manualBatch.contains("if (batchFallbackSet.contains(chapterIndex)) continue"))
    }

    @Test
    fun `partial batch success keeps saved chapters out of the fallback queue`() {
        //只有 missingIndexes 里的章节回退,已回存的走 onSuccess
        assertTrue(manualBatch.contains("onBatchMissing(claimed.filter { missingIndexes.contains(it.index) })"))
        assertTrue(manualBatch.contains("onSuccess(chapter)"))
        //书源声称回存但读不到内容的,也要退回单章
        assertTrue(manualBatch.contains("missingIndexes.add(chapter.index)"))
        assertTrue(readBatch.contains("missingIndexes.add(chapter.index)"))
    }

    @Test
    fun `stopping clears the batch fallback marks`() {
        val stop = section("fun stop()", "@Synchronized\n        fun addDownload")
        assertTrue(stop.contains("batchFallbackSet.clear()"))
    }

    private fun section(startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, start)
        require(start >= 0 && end > start) { "找不到区间: $startMarker .. $endMarker" }
        return source.substring(start, end)
    }
}
