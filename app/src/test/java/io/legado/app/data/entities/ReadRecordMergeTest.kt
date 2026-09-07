package io.legado.app.data.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadRecordMergeTest {
    private val current = ReadRecord(
        deviceId = "local", bookName = "Book", readTime = 100, lastRead = 10,
        lastChapterTitle = "Chapter 4", lastChapterIndex = 3, lastChapterPos = 25, coverUrl = "/cover.png",
    )

    @Test
    fun `newer chapter is retained even when its duration is lower`() {
        val incoming = current.copy(readTime = 50, lastRead = 20, lastChapterTitle = "Chapter 5", lastChapterIndex = 4)
        val merged = mergeRestoredReadRecord(current, incoming, true)
        assertEquals(100L, merged.readTime)
        assertEquals(20L, merged.lastRead)
        assertEquals("Chapter 5", merged.lastChapterTitle)
        assertEquals(4, merged.lastChapterIndex)
    }

    @Test
    fun `older backup with more duration cannot rewind snapshot`() {
        val merged = mergeRestoredReadRecord(current, current.copy(readTime = 200, lastRead = 5, lastChapterIndex = 1), true)
        assertEquals(200L, merged.readTime)
        assertEquals(3, merged.lastChapterIndex)
        assertEquals(25, merged.lastChapterPos)
    }

    @Test
    fun `legacy backups do not erase known metadata`() {
        val merged = mergeRestoredReadRecord(current, ReadRecord(bookName = "Book", readTime = 200, lastRead = 30), true)
        assertEquals("Chapter 4", merged.lastChapterTitle)
        assertEquals(3, merged.lastChapterIndex)
        assertEquals("/cover.png", merged.coverUrl)
    }

    @Test
    fun `chapter title is never borrowed from a different index`() {
        val merged = mergeRestoredReadRecord(current, current.copy(lastRead = 30, lastChapterIndex = 8, lastChapterTitle = null), true)
        assertEquals(8, merged.lastChapterIndex)
        assertNull(merged.lastChapterTitle)
    }

    @Test
    fun `remote duration replacement keeps the established device rule`() {
        val merged = mergeRestoredReadRecord(current, current.copy(readTime = 50, lastRead = 20), false)
        assertEquals(50L, merged.readTime)
        assertEquals(20L, merged.lastRead)
    }
}
