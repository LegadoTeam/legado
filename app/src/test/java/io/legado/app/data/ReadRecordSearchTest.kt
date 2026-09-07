package io.legado.app.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadRecordSearchTest {

    @Test
    fun `read record search keeps different authors separate`() {
        val source = sequenceOf(
            File("src/main/java/io/legado/app/data/dao/ReadRecordDao.kt"),
            File("app/src/main/java/io/legado/app/data/dao/ReadRecordDao.kt"),
        ).first(File::isFile).readText().replace(Regex("\\s+"), " ")
        assertTrue(
            source.contains(
                "group by history.bookName, history.author having history.bookName like '%' || :searchKey || '%' " +
                    "or history.author like '%' || :searchKey || '%'"
            )
        )
    }
}
