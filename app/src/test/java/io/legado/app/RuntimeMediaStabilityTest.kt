package io.legado.app

import io.legado.app.data.entities.Book
import io.legado.app.service.buildHttpTtsCacheFileName
import io.legado.app.ui.book.info.normalizeWebFileName
import io.legado.app.ui.widget.image.coverBitmapCacheKey
import io.legado.app.ui.widget.image.coverTitleColumnOffsets
import io.legado.app.ui.widget.image.coverTitleColumnStartY
import io.legado.app.ui.widget.image.coverTitleTextSize
import io.legado.app.ui.widget.image.normalizeCoverText
import io.legado.app.utils.calculateSvgBitmapSize
import io.legado.app.utils.isForegroundServiceStartDenied
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuntimeMediaStabilityTest {

    @Test
    fun webFileNameNormalizesDeclaredSuffix() {
        assertEquals("book.txt", normalizeWebFileName("book.txt", "txt"))
        assertEquals("book.TXT", normalizeWebFileName("book.TXT", ".txt"))
        assertEquals("book.epub", normalizeWebFileName("book.txt", "epub"))
        assertEquals("book.txt", normalizeWebFileName("book", " txt "))
        assertEquals("book.txt", normalizeWebFileName("book.", "txt"))
        assertEquals("book", normalizeWebFileName("book", "..."))
        assertEquals("book", normalizeWebFileName("book", "../txt"))
        assertEquals(
            "Version 2.0.txt",
            normalizeWebFileName("Version 2.0", "txt", replaceExistingSuffix = false)
        )
    }

    @Test
    fun svgBitmapSizeFitsAndFillsTargetBounds() {
        assertEquals(100 to 50, calculateSvgBitmapSize(200, 100, 100, 100))
        assertEquals(200 to 100, calculateSvgBitmapSize(100, 50, 200, 200))
        assertEquals(100 to 67, calculateSvgBitmapSize(150, 100, 100, 100))
        assertEquals(2048 to 2048, calculateSvgBitmapSize(100, 100, 100_000, 100_000))
        assertEquals(4 to 4096, calculateSvgBitmapSize(1, 1000, 100_000, null))
    }

    @Test
    fun customCoverOnlyInheritsIdentityOnTheSameOrigin() {
        val book = Book(
            origin = "https://books.example.com",
            coverUrl = "https://books.example.com/cover.jpg",
        )
        assertEquals(book.origin, book.getCoverSourceOrigin())

        book.customCoverUrl = "https://books.example.com/custom-cover.jpg"
        assertEquals(book.origin, book.getCoverSourceOrigin())

        book.customCoverUrl = "https://images.example.com/cover.jpg"
        assertNull(book.getCoverSourceOrigin())

        book.customCoverUrl = "https://images.example.net/cover.jpg"
        assertNull(book.getCoverSourceOrigin())

        book.persistedCoverUrl = "/data/user/0/io.legado.app/files/covers/local.cover"
        assertEquals(book.persistedCoverUrl, book.getDisplayCover())
        assertNull(book.getCoverSourceOrigin())

        book.persistedCoverUrl = null
        assertEquals(book.customCoverUrl, book.getDisplayCover())
    }

    @Test
    fun emptyCoverSkipsDelayedNetworkFallback() {
        val source = listOf(File("src/main/java"), File("app/src/main/java"))
            .first { it.isDirectory }
            .resolve("io/legado/app/ui/widget/image/CoverImageView.kt")
            .readText()
        val load = source.substringAfter("path: String? = null,")
            .substringBefore("override fun onDetachedFromWindow")
        val emptyPath = load.substringAfter("if (currentPath == null) {")
            .substringBefore("if (BookCover.drawBookName")

        assertTrue(load.contains("currentJob?.cancel()"))
        assertTrue(load.contains("triggerChannel.tryReceive()"))
        assertTrue(load.contains("path?.takeIf { it.isNotBlank() }"))
        assertTrue(load.contains("this.name = currentName"))
        assertTrue(load.contains("this.author = currentAuthor"))
        assertTrue(emptyPath.contains("needNameBitmap.put(currentPath.toString(), true)"))
        assertTrue(emptyPath.contains("ImageLoader.load(context, BookCover.defaultDrawable)"))
        assertTrue(emptyPath.contains("invalidate()"))
        assertTrue(emptyPath.contains("onLoadFinish?.invoke()"))
        assertTrue(emptyPath.contains("return"))
        assertFalse(emptyPath.contains("glideListener"))
    }

    @Test
    fun visibleTextCoverSurvivesSharedCacheEviction() {
        val source = listOf(File("src/main/java"), File("app/src/main/java"))
            .first { it.isDirectory }
            .resolve("io/legado/app/ui/widget/image/CoverImageView.kt")
            .readText()
        val cache = source.substringAfter("companion object {")
            .substringBefore("private val needNameBitmap")
        val generation = source.substringAfter("private fun generateCoverAsync")
            .substringBefore("private fun generateCoverBitmap")
        val localCache = source.substringAfter("private fun getNameBitmap")
            .substringBefore("private fun drawNameAuthor")

        assertTrue(cache.contains("LruCache<String, Bitmap>(33)"))
        assertTrue(source.contains("private var currentNameBitmap: Pair<String, Bitmap>? = null"))
        assertTrue(localCache.contains("currentBitmap?.first == cacheKey"))
        assertTrue(localCache.contains("currentNameBitmap = cacheKey to it"))
        assertTrue(generation.contains("if (getNameBitmap(cacheKey) != null)"))
        assertTrue(generation.contains("nameBitmapCache.put(cacheKey, bitmap)"))
        assertTrue(generation.contains("currentNameBitmap = cacheKey to bitmap"))
        assertTrue(generation.contains("postInvalidate()"))
    }

    @Test
    fun textCoverKeepsOneTitleSizeAcrossColumns() {
        val width = 105f
        val height = 140f
        val largeTextHeight = 18f

        for (characterCount in 4..7) {
            assertEquals(
                width / 7,
                coverTitleTextSize(width, height, characterCount, largeTextHeight),
                0.001f
            )
        }
        assertEquals(
            width / 9,
            coverTitleTextSize(width, height, 8, largeTextHeight),
            0.001f
        )

        val source = listOf(File("src/main/java"), File("app/src/main/java"))
            .first { it.isDirectory }
            .resolve("io/legado/app/ui/widget/image/CoverImageView.kt")
            .readText()
        val titleLoop = source.substringAfter("titleColumns.forEachIndexed")
            .substringBefore("if (!drawAuthor)")

        assertFalse(titleLoop.contains("namePaint.textSize ="))
    }

    @Test
    fun verticalCoverTitleColumnsShareTheLastBaseline() {
        val top = 28f
        val textHeight = 10f
        val columns = coverTitleColumnOffsets(20, 140f, textHeight)
        assertEquals(listOf(9, 8, 3), columns.map { it.size })
        val maxColumnBottom = columns.maxOf { it.last() }
        val starts = columns.map {
            coverTitleColumnStartY(top, maxColumnBottom, it.last())
        }

        assertEquals(listOf(top, top + textHeight, top + textHeight * 6), starts)
        val bottoms = starts.zip(columns).map { (start, offsets) ->
            start + offsets.last()
        }
        bottoms.forEach { assertEquals(bottoms.first(), it, 0.001f) }

        assertEquals(listOf(9, 3), coverTitleColumnOffsets(12, 140f, textHeight).map { it.size })
        val singleColumn = coverTitleColumnOffsets(11, 140f, textHeight).single()
        assertEquals(11, singleColumn.size)
        assertEquals(98f, singleColumn.last(), 0.001f)
        assertTrue(coverTitleColumnOffsets(0, 140f, textHeight).isEmpty())
        assertTrue(coverTitleColumnOffsets(3, 0f, textHeight).isEmpty())
        assertTrue(coverTitleColumnOffsets(3, 140f, 0f).isEmpty())
        assertEquals(top, coverTitleColumnStartY(top, 80f, 90f), 0.001f)
    }

    @Test
    fun coverTextOptionsPreservePunctuationAndSeparateRenderCaches() {
        assertEquals("Title Author", normalizeCoverText("Title, Author", false))
        assertEquals("Title, Author", normalizeCoverText("Title, Author", true))
        assertEquals("Title", normalizeCoverText("  Title  ", true))
        assertNotEquals(
            coverBitmapCacheKey("ab", "c", 105, 140, false, true, 1, 2),
            coverBitmapCacheKey("a", "bc", 105, 140, false, true, 1, 2)
        )
        assertNotEquals(
            coverBitmapCacheKey("Title", "Author", 105, 140, false, true, 1, 2),
            coverBitmapCacheKey("Title", "Author", 105, 140, true, true, 1, 2)
        )
        assertNotEquals(
            coverBitmapCacheKey("Title", "Author", 105, 140, true, true, 1, 2),
            coverBitmapCacheKey("Title", "Author", 105, 140, true, true, 3, 4)
        )
    }

    @Test
    fun horizontalTextCoverHonorsReportedLayoutContract() {
        val source = listOf(File("src/main/java"), File("app/src/main/java"))
            .first { it.isDirectory }
            .resolve("io/legado/app/ui/widget/image/CoverImageView.kt")
            .readText()
        val horizontal = source.substringAfter("private fun drawHorizontalTextCover")
            .substringBefore("fun setHeight")

        assertTrue(horizontal.contains("HORIZONTAL_TITLE_MAX_LINES"))
        assertTrue(horizontal.contains("setMaxLines(HORIZONTAL_TITLE_MAX_LINES)"))
        assertTrue(horizontal.contains("TextUtils.TruncateAt.END"))
        assertTrue(horizontal.contains("titlePaint.measureText(title) > titleWidth"))
        assertTrue(horizontal.contains("textAlign = Paint.Align.RIGHT"))
        assertTrue(horizontal.contains("textSize = viewWidth / 10"))
        assertTrue(horizontal.contains("viewHeight * 0.92f"))

        assertTrue(source.contains("sourceName = name"))
        assertTrue(source.contains("updateNormalizedText()"))
        assertTrue(source.contains("val renderWidth = width"))
        assertTrue(source.contains("val renderHeight = height"))
        assertTrue(source.contains("currentJob?.cancel()"))
        val configSource = File("src/main/java/io/legado/app/ui/config/CoverConfigFragment.kt")
            .takeIf { it.isFile }
            ?: File("app/src/main/java/io/legado/app/ui/config/CoverConfigFragment.kt")
        assertTrue(configSource.readText().contains("postEvent(EventBus.BOOKSHELF_REFRESH, \"\")"))
    }

    @Test
    fun audioCoverUsesTheBookScopedSourceOrigin() {
        val source = listOf(File("src/main/java"), File("app/src/main/java"))
            .first { it.isDirectory }
            .resolve("io/legado/app/ui/book/audio/AudioPlayActivity.kt")
            .readText()
        val upCover = source.substringAfter("private fun upCover(path: String?)")
            .substringBefore("override fun upLyric")

        assertTrue(upCover.contains("val sourceOrigin = AudioPlay.book?.getCoverSourceOrigin()"))
        assertTrue(upCover.contains("BookCover.load(this, path, sourceOrigin = sourceOrigin)"))
        assertTrue(upCover.contains("BookCover.loadBlur(this, path, sourceOrigin = sourceOrigin)"))
        assertFalse(upCover.contains("AudioPlay.bookSource?.bookSourceUrl"))
    }

    @Test
    fun httpTtsCacheKeyTracksSessionInputs() {
        val base = buildHttpTtsCacheFileName(
            "chapter",
            "https://tts.example",
            10,
            "voice=a",
            "Authorization: token-a",
            "content",
        )
        assertEquals(
            base,
            buildHttpTtsCacheFileName(
                "chapter",
                "https://tts.example",
                10,
                "voice=a",
                "Authorization: token-a",
                "content",
            )
        )
        assertNotEquals(
            base,
            buildHttpTtsCacheFileName(
                "chapter",
                "https://tts.example",
                10,
                "voice=b",
                "Authorization: token-a",
                "content",
            )
        )
        assertNotEquals(
            base,
            buildHttpTtsCacheFileName(
                "chapter",
                "https://tts.example",
                10,
                "voice=a",
                "Authorization: token-b",
                "content",
            )
        )
        assertNotEquals(
            buildHttpTtsCacheFileName("chapter", "url", 10, "a", "b-|-c", "content"),
            buildHttpTtsCacheFileName("chapter", "url", 10, "a-|-b", "c", "content"),
        )
    }

    @Test
    fun foregroundServiceDenialIsRecognizedThroughCauses() {
        val denied = ForegroundServiceStartNotAllowedException()
        assertTrue(denied.isForegroundServiceStartDenied())
        assertTrue(IllegalStateException(denied).isForegroundServiceStartDenied())
        assertFalse(IllegalStateException("other").isForegroundServiceStartDenied())
    }

    private class ForegroundServiceStartNotAllowedException : RuntimeException()
}
