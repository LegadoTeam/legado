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
        assertTrue(dao.contains("fun getReadTime(deviceId: String, bookName: String)"))
        assertTrue(
            normalizedDao.contains(
                "select bookName, sum(readTime) as readTime, max(lastRead) as lastRead, " +
                    "group_concat(author, char(31)) as author " +
                    "from readRecord group by bookName"
            )
        )

        listOf(
            "src/main/java/io/legado/app/model/ReadBook.kt" to
                "getReadTime(readRecord.deviceId, book.name)",
            "src/main/java/io/legado/app/model/ReadManga.kt" to
                "getReadTime(readRecord.deviceId, book.name)",
            "src/main/java/io/legado/app/model/AudioPlay.kt" to
                "getReadTime(record.deviceId, record.bookName)",
        ).forEach { (path, call) ->
            assertTrue(path, projectFile(path).contains(call))
        }
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
        assertTrue(upReadTime.contains("val author = book?.author ?: return"))
        assertTrue(upReadTime.contains("val elapsed = now - readStartTime"))
        assertTrue(upReadTime.contains("readStartTime = now"))
        assertTrue(upReadTime.contains("readRecord.copy()"))
        assertTrue(upReadTime.contains("appDb.readRecordDao.insert(record)"))
        assertTrue(upReadTime.indexOf("val elapsed = now - readStartTime") <
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
