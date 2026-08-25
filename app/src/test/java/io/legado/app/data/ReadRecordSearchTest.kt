package io.legado.app.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadRecordSearchTest {

    @Test
    fun `read record search matches book name or aggregated author`() {
        val source = sequenceOf(
            File("src/main/java/io/legado/app/data/dao/ReadRecordDao.kt"),
            File("app/src/main/java/io/legado/app/data/dao/ReadRecordDao.kt"),
        ).first(File::isFile).readText().replace(Regex("\\s+"), " ")
        assertTrue(
            source.contains(
                "group by bookName having bookName like '%' || :searchKey || '%' " +
                    "or group_concat(author, char(31)) like '%' || :searchKey || '%'"
            )
        )
    }
}
