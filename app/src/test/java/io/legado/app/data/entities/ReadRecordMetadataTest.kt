package io.legado.app.data.entities

import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadRecordMetadataTest {
    @Test fun oldJsonAndBlankAuthorsKeepTheirOriginalIdentity() {
        val old = GSON.fromJson("""{"deviceId":"phone","bookName":"Old","author":"","readTime":100,"resolvedAuthor":"A"}""", ReadRecord::class.java)
        val display = old.withDisplayMetadata()
        assertEquals("Old", display.displayBookName)
        assertEquals("A", display.displayAuthor)
        assertEquals("", display.author)
        assertEquals(0L, display.metadataEditedAt)
    }

    @Test fun restoringOldBackupsCannotUndoAnEditOrDoubleTheLocalDuration() {
        val original = ReadRecord(deviceId = "phone", bookName = "Old", author = "A", readTime = 100, lastRead = 10)
        val edited = original.copy(displayBookName = "New", displayAuthor = "", metadataEditedAt = 20)
        val restored = mergeRestoredReadRecord(mergeRestoredReadRecord(edited, original, true), original, true)
        assertEquals(edited, restored)
        val newBackup = GSON.fromJson(GSON.toJson(edited), ReadRecord::class.java)
        assertEquals(edited, newBackup)
        val updated = mergeRestoredReadRecord(original, newBackup, true)
        assertEquals("New", updated.displayBookName)
        assertEquals("", updated.displayAuthor)
        assertEquals("Old", updated.bookName)
        assertEquals("A", updated.author)
        assertEquals(100L, updated.readTime)
    }
}
