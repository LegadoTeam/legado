package io.legado.app.model

import io.legado.app.data.entities.BookChapter

/**
 * 批量下载的章节领取簿。
 *
 * 手动缓存和阅读预下载共用同一个 CacheBookModel 的 onDownloadSet 作为"进行中"标记。
 * 批量任务必须原子领取:只拿还没被别的任务占用的章节,结束时也只归还自己这次拿到的,
 * 否则会清掉另一个任务持有的标记,让同一章被重复下载。
 *
 * 这里的两个方法都不自带同步,调用方须在持有 CacheBookModel 锁的前提下调用。
 */
internal object BatchChapterClaim {

    /**
     * 逐章 test-and-set:[MutableSet.add] 返回 false 说明已被其它任务占用,跳过。
     * @return 本次实际领取到的章节
     */
    fun claim(
        chapters: List<BookChapter>,
        onDownloadSet: MutableSet<Int>,
        waitDownloadSet: MutableSet<Int>
    ): List<BookChapter> {
        val claimed = ArrayList<BookChapter>(chapters.size)
        chapters.forEach { chapter ->
            if (onDownloadSet.add(chapter.index)) {
                waitDownloadSet.remove(chapter.index)
                claimed.add(chapter)
            }
        }
        return claimed
    }

    /** 只归还本次领取且尚未走完成回调的章节 */
    fun release(claimed: List<BookChapter>, onDownloadSet: MutableSet<Int>) {
        claimed.forEach { onDownloadSet.remove(it.index) }
    }
}
