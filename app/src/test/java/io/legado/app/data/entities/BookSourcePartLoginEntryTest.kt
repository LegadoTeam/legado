package io.legado.app.data.entities

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookSourcePartLoginEntryTest {

    private val partSource =
        File("src/main/java/io/legado/app/data/entities/BookSourcePart.kt").readText()
    private val databaseSource =
        File("src/main/java/io/legado/app/data/AppDatabase.kt").readText()

    @Test
    fun `login entry covers js form sources`() {
        assertTrue(partSource.contains("trim(mainJs)"))
        assertTrue(partSource.contains("trim(loginUi)"))
        assertTrue(partSource.contains("replace(trim(loginUi), ' ', '') <> '[]'"))
    }

    @Test
    fun `database view change has migration`() {
        assertTrue(databaseSource.contains("version = 92"))
        assertTrue(databaseSource.contains("AutoMigration(from = 91, to = 92)"))
    }
}
