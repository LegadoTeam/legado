package io.legado.app.data.entities

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadRecordShowTest {

    @Test
    fun `display author decodes and deduplicates multi-device values`() {
        val authors = ReadRecordAuthors.merge("作者乙", "作者甲")
        val record = ReadRecordShow(
            bookName = "书名",
            readTime = 1L,
            lastRead = 2L,
            author = "作者甲${ReadRecordAuthors.AGGREGATE_SEPARATOR}$authors",
        )

        assertEquals("作者乙、作者甲", record.displayAuthor)
    }

    @Test
    fun `display author stays empty for legacy records`() {
        assertEquals("", ReadRecordShow("书名", 1L, 2L).displayAuthor)
        assertEquals("", ReadRecordAuthors.display(ReadRecordAuthors.AGGREGATE_SEPARATOR))
    }
}
