package io.legado.app.data.entities

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookSourcePartLoginEntryTest {

    private val partSource =
        File("src/main/java/io/legado/app/data/entities/BookSourcePart.kt").readText()
    private val normalizedPartSource = partSource.replace(Regex("\\s+"), " ")
    private val databaseSource =
        File("src/main/java/io/legado/app/data/AppDatabase.kt").readText()

    @Test
    fun `login entry covers js form sources`() {
        assertTrue(
            normalizedPartSource.contains(
                "or (mainJs is not null and trim(mainJs) <> '' " +
                    "and loginUi is not null " +
                    "and replace(replace(replace(replace(loginUi, ' ', ''), char(9), ''), char(10), ''), char(13), '') " +
                    "not in ('', '[]'))) hasLoginUrl"
            )
        )
    }

    @Test
    fun `database view change has migration`() {
        assertTrue(databaseSource.contains("version = 95"))
        assertTrue(databaseSource.contains("AutoMigration(from = 94, to = 95)"))
    }
}
