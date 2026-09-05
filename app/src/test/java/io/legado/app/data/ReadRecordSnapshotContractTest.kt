package io.legado.app.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadRecordSnapshotContractTest {

    @Test
    fun `read record schema has nullable snapshot fields and migration`() {
        val record = projectFile("src/main/java/io/legado/app/data/entities/ReadRecord.kt")
        assertTrue(record.contains("var lastChapterTitle: String? = null"))
        assertTrue(record.contains("var lastChapterIndex: Int = -1"))
        assertTrue(record.contains("var lastChapterPos: Int = 0"))
        assertTrue(record.contains("var coverUrl: String? = null"))

        val database = projectFile("src/main/java/io/legado/app/data/AppDatabase.kt")
        assertTrue(database.contains("version = 105"))
        assertTrue(database.contains("AutoMigration(from = 104, to = 105)"))
    }

    @Test
    fun `all reading modes write current book snapshot`() {
        assertTrue(
            projectFile("src/main/java/io/legado/app/model/ReadBook.kt")
                .contains("readRecord.updateSnapshot(currentBook, durChapterIndex, durChapterPos)")
        )
        assertTrue(
            projectFile("src/main/java/io/legado/app/model/ReadManga.kt")
                .contains("readRecord.updateSnapshot(currentBook, durChapterIndex, durChapterPos)")
        )
        val audio = projectFile("src/main/java/io/legado/app/model/AudioPlay.kt")
        assertTrue(audio.contains("fun updateSnapshot(book: Book, chapterIndex: Int, chapterPos: Int)"))
        assertTrue(audio.contains("readTimeTracker.updateSnapshot(it, durChapterIndex, durChapterPos)"))
        assertTrue(audio.contains("book?.let { readTimeTracker.updateSnapshot(it, durChapterIndex, durChapterPos) }"))
    }

    @Test
    fun `deletion snapshots bookshelf data and restore prefers latest record`() {
        val record = projectFile("src/main/java/io/legado/app/data/entities/ReadRecord.kt")
        assertTrue(record.contains("fun Book.saveReadRecordSnapshot()"))
        assertTrue(record.contains("appDb.readRecordDao.getRecord(AppConst.androidId, name)"))

        val book = projectFile("src/main/java/io/legado/app/data/entities/Book.kt")
        assertTrue(book.contains("saveReadRecordSnapshot()"))
        val bookshelf = projectFile(
            "src/main/java/io/legado/app/ui/book/manage/BookshelfManageViewModel.kt"
        )
        assertTrue(bookshelf.contains("books.forEach { it.saveReadRecordSnapshot() }"))
        val main = projectFile("src/main/java/io/legado/app/ui/main/MainViewModel.kt")
        assertTrue(main.contains("appDb.bookDao.getNotShelfBooks().forEach { it.saveReadRecordSnapshot() }"))
        assertTrue(main.contains("appDb.bookDao.deleteNotShelfBook()"))
        val dao = projectFile("src/main/java/io/legado/app/data/dao/BookDao.kt")
        assertTrue(dao.contains("fun getNotShelfBooks(): List<Book>"))

        val restore = projectFile("src/main/java/io/legado/app/help/storage/Restore.kt")
        assertTrue(restore.contains("current.readTime == normalizedRecord.readTime"))
        assertTrue(restore.contains("current.lastRead < normalizedRecord.lastRead"))
    }

    @Test
    fun `history backup keeps read record file enabled`() {
        val backup = projectFile("src/main/java/io/legado/app/help/storage/Backup.kt")
        assertTrue(backup.contains("addAll(listOf(\"readRecord.json\", \"searchHistory.json\"))"))
        assertTrue(backup.contains("writeListToJson(appDb.readRecordDao.all, \"readRecord.json\""))
    }

    private fun projectFile(pathInApp: String): String {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()
    }
}
