package io.legado.app.ui.main.bookshelf

import io.legado.app.data.entities.Book
import io.legado.app.help.book.readProgress
import io.legado.app.help.config.BookshelfReadProgressMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BookshelfReadProgressTest {

    @Test
    fun `unopened books do not expose progress`() {
        assertNull(Book(totalChapterNum = 20).readProgress())
    }

    @Test
    fun `single chapter books become complete after reading starts`() {
        val book = Book(totalChapterNum = 1, durChapterPos = 1)
        assertEquals(1f, book.readProgress() ?: 0f, 0f)
    }

    @Test
    fun `chapter index maps to bounded progress`() {
        assertEquals(
            0.5f,
            Book(totalChapterNum = 11, durChapterIndex = 5).readProgress() ?: 0f,
            0f,
        )
        assertEquals(
            1f,
            Book(totalChapterNum = 10, durChapterIndex = 20).readProgress() ?: 0f,
            0f,
        )
    }

    @Test
    fun `all book item layouts provide hidden progress views`() {
        bookItemLayouts.forEach { layout ->
            val document = parseProjectXml("src/main/res/layout/$layout")
            val progress = document.findElementById("@+id/pb_read_progress")
            assertEquals("gone", progress.androidAttribute("visibility"))
            assertEquals("2dp", progress.appAttribute("trackThickness"))

            val percent = document.findElementsById("@+id/tv_read_percent")
            if (layout.startsWith("item_bookshelf_list")) {
                assertEquals(1, percent.size)
                assertEquals("gone", percent.single().androidAttribute("visibility"))
            } else {
                assertTrue(percent.isEmpty())
            }
        }
    }

    @Test
    fun `bookshelf progress modes preserve legacy settings and thickness`() {
        assertEquals(
            BookshelfReadProgressMode.HIDDEN,
            BookshelfReadProgressMode.resolve(null, false),
        )
        assertEquals(
            BookshelfReadProgressMode.STANDARD,
            BookshelfReadProgressMode.resolve(null, true),
        )
        assertEquals(
            BookshelfReadProgressMode.ENHANCED,
            BookshelfReadProgressMode.resolve("2", false),
        )
        assertEquals(
            BookshelfReadProgressMode.STANDARD_THICKNESS_DP,
            BookshelfReadProgressMode.thicknessDp(BookshelfReadProgressMode.HIDDEN),
        )
        assertEquals(
            BookshelfReadProgressMode.STANDARD_THICKNESS_DP,
            BookshelfReadProgressMode.thicknessDp(BookshelfReadProgressMode.STANDARD),
        )
        assertEquals(
            BookshelfReadProgressMode.ENHANCED_THICKNESS_DP,
            BookshelfReadProgressMode.thicknessDp(BookshelfReadProgressMode.ENHANCED),
        )

        val appConfig = projectFile("src/main/java/io/legado/app/help/config/AppConfig.kt").readText()
        assertTrue(appConfig.contains("PreferKey.bookshelfReadProgressMode"))
        assertTrue(appConfig.contains("PreferKey.showBookshelfReadProgress"))
        val renderer = projectFile(
            "src/main/java/io/legado/app/ui/main/bookshelf/BookshelfReadProgress.kt",
        ).readText()
        assertTrue(renderer.contains("BookshelfReadProgressMode"))
        assertTrue(renderer.contains("thicknessDp(AppConfig.bookshelfReadProgressMode)"))
        val restore = projectFile("src/main/java/io/legado/app/help/storage/Restore.kt").readText()
        assertTrue(restore.contains("PreferKey.bookshelfReadProgressMode !in map"))
        assertTrue(restore.contains("BookshelfReadProgressMode.STANDARD"))
    }

    @Test
    fun `group layouts remain free of per-book progress`() {
        groupLayouts.forEach { layout ->
            val document = parseProjectXml("src/main/res/layout/$layout")
            assertFalse(document.findElementsById("@+id/pb_read_progress").isNotEmpty())
        }
    }

    @Test
    fun `bookshelf settings expose display switches in order`() {
        val document = parseProjectXml("src/main/res/layout/dialog_bookshelf_config.xml")
        val progressRow = document.findElementById("@+id/ll_read_progress")
        val progressLabel = progressRow.getElementsByTagName("TextView").item(0) as Element
        val progressSpinner = document.findElementById("@+id/sp_read_progress")
        val unreadSwitch = document.findElementById("@+id/sw_show_unread")
        val lastUpdateSwitch = document.findElementById("@+id/sw_show_last_update_time")
        val waitSwitch = document.findElementById("@+id/sw_show_wait_up_books")
        val fastScrollerSwitch =
            document.findElementById("@+id/sw_show_bookshelf_fast_scroller")
        val recentReadingSwitch = document.findElementById("@+id/sw_show_recent_reading")
        val statsSwitch = document.findElementById("@+id/sw_show_bookshelf_stats")
        val layout = document.findElementById("@+id/ll_layout")
        val sort = document.findElementById("@+id/ll_sort")
        val columnsBottom = document.findElementById("@+id/layout_columns_bottom")
        val bookNameChoice = document.findElementById("@+id/book_name_choice")

        assertEquals("@string/read_progress", progressLabel.androidAttribute("text"))
        assertEquals("@array/bookshelf_read_progress", progressSpinner.androidAttribute("entries"))
        assertEquals(
            "@+id/ll_group_style",
            progressRow.appAttribute("layout_constraintTop_toBottomOf"),
        )
        assertEquals(
            "@+id/ll_read_progress",
            unreadSwitch.appAttribute("layout_constraintTop_toBottomOf"),
        )
        assertEquals(
            "@+id/sw_show_unread",
            lastUpdateSwitch.appAttribute("layout_constraintTop_toBottomOf"),
        )
        assertEquals(
            "@+id/sw_show_last_update_time",
            waitSwitch.appAttribute("layout_constraintTop_toBottomOf"),
        )
        assertEquals(
            "@+id/sw_show_bookshelf_fast_scroller",
            recentReadingSwitch.appAttribute("layout_constraintTop_toBottomOf"),
        )
        assertEquals("@string/recent_reading", recentReadingSwitch.androidAttribute("text"))
        assertEquals(
            "@+id/sw_show_recent_reading",
            statsSwitch.appAttribute("layout_constraintTop_toBottomOf"),
        )
        assertEquals("@string/bookshelf_statistics", statsSwitch.androidAttribute("text"))
        assertEquals(
            "@+id/sw_show_bookshelf_stats",
            layout.appAttribute("layout_constraintTop_toBottomOf"),
        )
        assertEquals(
            "@+id/sw_show_bookshelf_stats",
            sort.appAttribute("layout_constraintTop_toBottomOf"),
        )
        assertEquals("bottom", columnsBottom.appAttribute("barrierDirection"))
        assertEquals(
            "ll_layout,ll_sort",
            columnsBottom.appAttribute("constraint_referenced_ids"),
        )
        assertEquals(
            "@+id/layout_columns_bottom",
            bookNameChoice.appAttribute("layout_constraintTop_toBottomOf"),
        )
        assertEquals(
            "@+id/sw_show_wait_up_books",
            fastScrollerSwitch.appAttribute("layout_constraintTop_toBottomOf"),
        )
        assertFalse(document.findElementsById("@+id/sw_show_read_progress").isNotEmpty())
    }

    @Test
    fun `bookshelf layouts expose the shared header and keep content below it`() {
        val header = parseProjectXml("src/main/res/layout/view_bookshelf_header.xml")
        assertEquals("gone", header.documentElement.androidAttribute("visibility"))
        assertEquals(
            "gone",
            header.findElementById("@+id/continue_reading").androidAttribute("visibility"),
        )
        listOf("tv_shelf_stats", "tv_continue_name", "tv_continue_chapter", "tv_continue_percent")
            .forEach { id -> header.findElementById("@+id/$id") }
        listOf("tv_continue_name", "tv_continue_chapter").forEach { id ->
            val text = header.findElementById("@+id/$id")
            assertEquals("0dp", text.androidAttribute("layout_width"))
            assertEquals("1", text.androidAttribute("layout_weight"))
        }

        val style1 = parseProjectXml("src/main/res/layout/fragment_bookshelf1.xml")
        assertEquals(
            "@layout/view_bookshelf_header",
            style1.findElementById("@+id/shelf_header").getAttribute("layout"),
        )
        val viewPager = style1.findElementById("@+id/view_pager_bookshelf")
        assertEquals("0dp", viewPager.androidAttribute("layout_height"))
        assertEquals("1", viewPager.androidAttribute("layout_weight"))

        val style2 = parseProjectXml("src/main/res/layout/fragment_bookshelf2.xml")
        assertEquals(
            "@+id/shelf_header",
            style2.findElementById("@+id/refresh_layout")
                .appAttribute("layout_constraintTop_toBottomOf"),
        )
        assertEquals(
            "@+id/shelf_header",
            style2.findElementById("@+id/tv_empty_msg")
                .appAttribute("layout_constraintTop_toBottomOf"),
        )
    }

    @Test
    fun `bookshelf header options default off and gate database work`() {
        val appConfig =
            projectFile("src/main/java/io/legado/app/help/config/AppConfig.kt").readText()
        assertTrue(
            appConfig.contains(
                "get() = appCtx.getPrefBoolean(PreferKey.showBookshelfRecentReading, false)",
            ),
        )
        assertTrue(
            appConfig.contains(
                "get() = appCtx.getPrefBoolean(PreferKey.showBookshelfStats, false)",
            ),
        )

        val fragment = projectFile(
            "src/main/java/io/legado/app/ui/main/bookshelf/BaseBookshelfFragment.kt",
        ).readText()
        val disabledGate = fragment.indexOf("if (!showRecentReading && !showBookshelfStats) return")
        val databaseFlow = fragment.indexOf("appDb.bookDao.flowShelfBookCount()")
        assertTrue(disabledGate >= 0)
        assertTrue(databaseFlow > disabledGate)
        assertTrue(fragment.contains("val book = if (showRecentReading)"))
        assertTrue(fragment.contains("val readingCount = if (showBookshelfStats)"))
        assertTrue(
            fragment.contains(
                "if (showBookshelfStats || book != null) View.VISIBLE else View.GONE",
            ),
        )
        val recentSetting = fragment.indexOf(
            "AppConfig.showBookshelfRecentReading = swShowRecentReading.isChecked",
        )
        val statsSetting = fragment.indexOf(
            "AppConfig.showBookshelfStats = swShowBookshelfStats.isChecked",
        )
        assertTrue(recentSetting >= 0)
        assertTrue(fragment.indexOf("recreate = true", recentSetting) in 0..<statsSetting)
        assertTrue(statsSetting >= 0)
        assertTrue(fragment.indexOf("recreate = true", statsSetting) > statsSetting)
    }

    @Test
    fun `continue reading query includes every shelf media type`() {
        val source = projectFile("src/main/java/io/legado/app/data/dao/BookDao.kt").readText()
        val propertyIndex = source.indexOf("val lastReadBookOnShelf")
        val queryStart = source.lastIndexOf("@get:Query(", propertyIndex)
        val query = source.substring(queryStart, propertyIndex)

        assertTrue(query.contains("type & \${BookType.notShelf} = 0"))
        assertFalse(query.replace("BookType.notShelf", "").contains("BookType."))
        assertTrue(query.contains("durChapterIndex > 0 OR durChapterPos > 0"))
        assertTrue(query.contains("durChapterTime DESC limit 1"))
    }

    @Test
    fun `reading count query only includes started shelf books`() {
        val source = projectFile("src/main/java/io/legado/app/data/dao/BookDao.kt").readText()
        val propertyIndex = source.indexOf("val readingCount")
        val queryStart = source.lastIndexOf("@get:Query(", propertyIndex)
        val query = source.substring(queryStart, propertyIndex)

        assertTrue(query.contains("(durChapterIndex > 0 OR durChapterPos > 0)"))
        assertTrue(query.contains("and type & \${BookType.notShelf} = 0"))
        assertFalse(query.replace("BookType.notShelf", "").contains("BookType."))
        assertFalse(query.contains("durChapterTime"))
    }

    private fun parseProjectXml(pathInApp: String): Document {
        return DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(projectFile(pathInApp))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }

    private fun Document.findElementById(id: String): Element =
        findElementsById(id).single()

    private fun Document.findElementsById(id: String): List<Element> {
        return getElementsByTagName("*").let { nodes ->
            (0 until nodes.length)
                .map { nodes.item(it) as Element }
                .filter { it.androidAttribute("id") == id }
        }
    }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(androidNamespace, name)

    private fun Element.appAttribute(name: String): String =
        getAttributeNS(appNamespace, name)

    private companion object {
        const val androidNamespace = "http://schemas.android.com/apk/res/android"
        const val appNamespace = "http://schemas.android.com/apk/res-auto"

        val bookItemLayouts = listOf(
            "item_bookshelf_grid.xml",
            "item_bookshelf_grid2.xml",
            "item_bookshelf_list.xml",
            "item_bookshelf_list2.xml",
        )

        val groupLayouts = listOf(
            "item_bookshelf_grid_group.xml",
            "item_bookshelf_grid_group2.xml",
            "item_bookshelf_list_group.xml",
        )
    }
}
