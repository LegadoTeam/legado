package io.legado.app.data.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookSourcePartHasJsTest {

    private val partSource = projectFile(
        "src/main/java/io/legado/app/data/entities/BookSourcePart.kt"
    ).readText()
    private val normalizedPartSource = partSource.replace(Regex("\\s+"), " ")
    private val adapterSource = projectFile(
        "src/main/java/io/legado/app/ui/book/source/manage/BookSourceAdapter.kt"
    ).readText()
    private val databaseSource = projectFile(
        "src/main/java/io/legado/app/data/AppDatabase.kt"
    ).readText()
    private val itemLayout = projectFile("src/main/res/layout/item_book_source.xml").readText()
    private val schemaSource = projectFile(
        "schemas/io.legado.app.data.AppDatabase/93.json"
    ).readText()

    @Test
    fun `database view exposes js source state`() {
        assertTrue(
            partSource.contains("(mainJs is not null and trim(mainJs) <> '') hasJs")
        )
        assertTrue(
            normalizedPartSource.contains(
                "eventListener, bookSourceType, " +
                    "(mainJs is not null and trim(mainJs) <> '') hasJs"
            )
        )
        assertFalse(BookSourcePart().hasJs)
        assertTrue(BookSourcePart(hasJs = true).hasJs)
    }

    @Test
    fun `schema history preserves js source projection`() {
        assertTrue(databaseSource.contains("version = 95"))
        assertTrue(databaseSource.contains("AutoMigration(from = 94, to = 95)"))
        assertTrue(schemaSource.contains("\"version\": 93"))
        assertTrue(
            schemaSource.contains("(mainJs is not null and trim(mainJs) <> '') hasJs")
        )
    }

    @Test
    fun `adapter refreshes js badge without leaving hidden spacing`() {
        assertTrue(adapterSource.contains("oldItem.hasJs == newItem.hasJs"))
        assertTrue(adapterSource.contains("payload.putBoolean(\"upJs\", true)"))
        assertEquals(
            2,
            Regex(Regex.escape("tvJsBadge.gone(!item.hasJs)"))
                .findAll(adapterSource)
                .count()
        )
    }

    @Test
    fun `source name is constrained through an initially gone js badge`() {
        assertTrue(
            itemLayout.contains("<io.legado.app.ui.widget.text.AccentTextView")
        )
        assertTrue(itemLayout.contains("android:id=\"@+id/tv_js_badge\""))
        val badgeLayout = itemLayout.substringAfter("android:id=\"@+id/tv_js_badge\"")
            .substringBefore("<io.legado.app.lib.theme.view.ThemeSwitch")
        assertTrue(badgeLayout.contains("android:visibility=\"gone\""))
        assertTrue(itemLayout.contains("app:layout_constraintRight_toLeftOf=\"@id/tv_js_badge\""))
        assertTrue(itemLayout.contains("android:text=\"@string/js_source_badge\""))
    }

    private fun projectFile(pathInApp: String): File {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .first { it.isFile }
    }
}
