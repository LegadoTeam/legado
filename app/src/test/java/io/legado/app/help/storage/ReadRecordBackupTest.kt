package io.legado.app.help.storage

import io.legado.app.data.entities.ReadRecord
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReadRecordBackupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `cover snapshots are omitted without changing live records`() {
        val external = temporary.newFolder("external")
        val backup = temporary.newFolder("backup")
        val cover = File(external, "$readRecordCoverDirectory/example.cover")
        cover.parentFile.mkdirs()
        cover.writeBytes(byteArrayOf(1, 2, 3))
        val record = ReadRecord(bookName = "Book", coverUrl = cover.absolutePath)
        val exported = prepareReadRecordBackup(listOf(record), external, backup, false).single()
        assertNull(exported.coverUrl)
        assertEquals(cover.absolutePath, record.coverUrl)
        assertTrue(cover.isFile)
        assertFalse(File(backup, readRecordCoverDirectory).exists())
    }

    @Test
    fun `selected covers are copied and remapped to a new installation`() {
        val external = temporary.newFolder("external")
        val backup = temporary.newFolder("backup")
        val restored = temporary.newFolder("restored")
        val cover = File(external, "$readRecordCoverDirectory/example.cover")
        cover.parentFile.mkdirs()
        cover.writeBytes(byteArrayOf(1, 2, 3))
        val record = ReadRecord(bookName = "Book", coverUrl = cover.absolutePath)
        val exported = prepareReadRecordBackup(listOf(record), external, backup, true).single()
        assertArrayEquals(cover.readBytes(), File(backup, "$readRecordCoverDirectory/example.cover").readBytes())
        assertEquals(File(restored, "$readRecordCoverDirectory/example.cover").absolutePath,
            remapReadRecordCover(exported.coverUrl, backup, restored))
        assertNull(remapReadRecordCover(File(external, "$readRecordCoverDirectory/missing.cover").absolutePath, backup, restored))
    }

    @Test
    fun `network addresses are preserved without local file access`() {
        val external = temporary.newFolder("external")
        val backup = temporary.newFolder("backup")
        val record = ReadRecord(coverUrl = "https://example.invalid/image?key=value")
        assertEquals(record, prepareReadRecordBackup(listOf(record), external, backup, false).single())
    }

    @Test
    fun `inline image bytes also require the cover backup option`() {
        val external = temporary.newFolder("external")
        val backup = temporary.newFolder("backup")
        val record = ReadRecord(coverUrl = "data:image/png;base64,AQID")
        assertNull(prepareReadRecordBackup(listOf(record), external, backup, false).single().coverUrl)
        assertEquals(record, prepareReadRecordBackup(listOf(record), external, backup, true).single())
    }
}
