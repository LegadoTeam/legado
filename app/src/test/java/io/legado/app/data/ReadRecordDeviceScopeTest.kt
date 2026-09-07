package io.legado.app.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadRecordDeviceScopeTest {

    @Test
    fun `active readers reload own row while display still aggregates`() {
        val dao = projectFile("src/main/java/io/legado/app/data/dao/ReadRecordDao.kt")
        val normalizedDao = dao.replace(Regex("\\s+"), " ")
        assertTrue(
            dao.contains(
                "select readTime from readRecord where deviceId = :deviceId and bookName = :bookName"
            )
        )
        assertTrue(dao.contains("fun getReadTime(deviceId: String, bookName: String, author: String)"))
        assertTrue(
            normalizedDao.contains(
                "select history.bookName, sum(history.readTime) as readTime, " +
                    "max(history.lastRead) as lastRead, history.author"
            )
        )

        listOf(
            "src/main/java/io/legado/app/model/ReadBook.kt" to
                "getRecord(AppConst.androidId, book.name, book.author)",
            "src/main/java/io/legado/app/model/ReadManga.kt" to
                "getRecord(AppConst.androidId, book.name, book.author)",
            "src/main/java/io/legado/app/model/AudioPlay.kt" to
                "ReadRecord(\n            deviceId = AppConst.androidId,",
        ).forEach { (path, call) ->
            assertTrue(path, projectFile(path).contains(call))
        }
    }

    @Test
    fun `restore maps blank legacy device ids to the current device`() {
        val restore = projectFile("src/main/java/io/legado/app/help/storage/Restore.kt")
        assertTrue(restore.contains("readRecord.deviceId.isBlank()"))
        assertTrue(restore.contains("readRecord.copy(deviceId = androidId)"))
        assertTrue(restore.contains("restoredRecord.deviceId == androidId"))
        assertTrue(
            restore.contains(
                "getRecord(restoredRecord.deviceId, restoredRecord.bookName, restoredRecord.author)"
            )
        )
    }

    @Test
    fun `manga resume excludes time spent in background`() {
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/book/manga/ReadMangaActivity.kt"
        )
        assertTrue(methodBody(activity, "onPause").contains("ReadManga.upReadTime()"))
        assertTrue(
            methodBody(activity, "onResume")
                .contains("ReadManga.readStartTime = System.currentTimeMillis()")
        )

        val manga = projectFile("src/main/java/io/legado/app/model/ReadManga.kt")
        val upReadTime = methodBody(manga, "upReadTime")
        assertTrue(upReadTime.contains("val currentBook = book?.copy() ?: return"))
        assertTrue(upReadTime.contains("val elapsed = (now - readStartTime).coerceAtLeast(0)"))
        assertTrue(upReadTime.contains("readStartTime = now"))
        assertTrue(upReadTime.contains("readRecord.copy()"))
        assertTrue(upReadTime.contains("record.saveWithCover(snapshotBook)"))
        assertTrue(upReadTime.indexOf("val elapsed = (now - readStartTime).coerceAtLeast(0)") <
            upReadTime.indexOf("executor.execute"))
        assertTrue(upReadTime.indexOf("readStartTime = now") <
            upReadTime.indexOf("executor.execute"))
    }

    private fun methodBody(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        if (start < 0) return ""
        val bodyStart = source.indexOf('{', start)
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(bodyStart, index + 1)
            }
        }
        return ""
    }

    private fun projectFile(pathInApp: String): String {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()
    }
}
