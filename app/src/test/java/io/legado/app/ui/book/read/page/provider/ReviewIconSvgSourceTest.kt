package io.legado.app.ui.book.read.page.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReviewIconSvgSourceTest {

    @Test
    fun `svg icon settings are persisted and exported`() {
        val source = projectFile(
            "src/main/java/io/legado/app/help/config/ReadBookConfig.kt"
        ).readText().normalizeLines()

        assertTrue(source.contains("var reviewIconSvg: String = \"\""))
        assertTrue(
            source.contains(
                "var reviewIconSvgTemplates: List<ReviewIconSvgTemplate> = emptyList()"
            )
        )
        assertTrue(source.contains("var reviewIconScale: Int = 100"))
        assertTrue(source.contains("config.reviewIconScale = value.coerceIn(50, 200)"))
        assertTrue(source.contains("exportConfig.reviewIconSvg = shareConfig.reviewIconSvg"))
        assertTrue(source.contains("exportConfig.reviewIconScale = shareConfig.reviewIconScale"))
        assertFalse(
            source.contains(
                "exportConfig.reviewIconSvgTemplates = shareConfig.reviewIconSvgTemplates"
            )
        )
        assertTrue(source.contains("\"reviewIconSvg\" to reviewIconSvg"))
        assertTrue(source.contains("\"reviewIconSvgTemplates\" to reviewIconSvgTemplates"))
        assertTrue(source.contains("\"reviewIconScale\" to reviewIconScale"))
    }

    @Test
    fun `provider caches rendered svg and keeps the built in fallback`() {
        val provider = projectFile(
            "src/main/java/io/legado/app/ui/book/read/page/provider/ChapterProvider.kt"
        ).readText().normalizeLines()
        val column = projectFile(
            "src/main/java/io/legado/app/ui/book/read/page/entities/column/ReviewColumn.kt"
        ).readText().normalizeLines()

        assertTrue(provider.contains("private const val reviewIconPlaceholder = \"{{count}}\""))
        assertTrue(provider.contains("reviewIconCacheMaxBytes = 1024 * 1024"))
        assertTrue(provider.contains("reviewIconMaxAspectRatio = 4f"))
        assertTrue(provider.contains("reviewIconMaxPageWidthRatio = 0.5f"))
        assertTrue(provider.contains("isReviewIconAspectRatioSupported(aspectRatio)"))
        assertTrue(provider.contains("minOf(width, maxWidth)"))
        assertTrue(provider.contains("SvgUtils.createBitmapFromSvgText"))
        assertTrue(provider.contains("fun clearReviewIconCache()"))
        assertTrue(column.contains("ChapterProvider.getReviewIconBitmap("))
        assertTrue(column.contains("canvas.drawBitmap(bitmap, null, iconRect, null)"))
        assertTrue(column.contains("val drawHeight = minOf(iconHeight"))
        assertTrue(column.contains("ReviewColumnGeometry.centeredTop(it, drawHeight)"))
        assertTrue(column.contains("?: baseLine - drawHeight"))
        assertTrue(
            column.contains(
                "containerHeight = if (textLine.isImage) null else textLine.height"
            )
        )
        assertTrue(
            column.contains(
                "minOf(ChapterProvider.getReviewHeight(false), textLine.height) * 0.9f"
            )
        )
        assertTrue(column.contains("path.reset()"))
    }

    @Test
    fun `legacy image reviews reuse the native renderer and keep image fallback`() {
        val column = projectFile(
            "src/main/java/io/legado/app/ui/book/read/page/entities/column/ImageColumn.kt"
        ).readText().normalizeLines()

        assertTrue(column.contains("parseImageReviewOption(src, click)"))
        assertTrue(column.contains("takeUnless { textLine.isImage }"))
        assertTrue(column.contains("ChapterProvider.getReviewWidth(textLine.isTitle)"))
        assertTrue(column.contains("it.drawToCanvas("))
        assertTrue(column.contains("containerHeight = textLine.height"))
        assertTrue(column.contains("ImageProvider.getImage("))
    }

    @Test
    fun `settings validate svg and refresh current review columns`() {
        val dialog = projectFile(
            "src/main/java/io/legado/app/ui/book/read/config/BgTextConfigDialog.kt"
        ).readText().normalizeLines()
        val adapter = projectFile(
            "src/main/java/io/legado/app/ui/book/read/config/ReviewIconSvgTemplateAdapter.kt"
        ).readText().normalizeLines()
        val layout = projectFile(
            "src/main/res/layout/dialog_read_bg_text.xml"
        ).readText().normalizeLines()

        assertTrue(dialog.contains("isValidReviewIconSvg(newSvg)"))
        assertTrue(dialog.contains("ReadBookConfig.durConfig.reviewIconSvgTemplates"))
        assertTrue(dialog.contains("ReviewIconSvgTemplateAdapter("))
        assertTrue(dialog.contains("GridLayoutManager(requireContext(), 3)"))
        assertTrue(dialog.contains("templateAdapter.setOnItemClickListener"))
        assertTrue(dialog.contains("templateAdapter.setOnItemLongClickListener"))
        assertTrue(adapter.contains("ItemBgImageBinding"))
        assertTrue(adapter.contains("item.svg.replace(\"{{count}}\", \"88\")"))
        assertTrue(adapter.contains("SvgUtils.createBitmapFromSvgText"))
        assertTrue(adapter.contains("ivBg.contentDescription = displayName"))
        assertTrue(adapter.contains("tvName.text = displayName"))
        assertTrue(dialog.contains("ReadBookConfig.reviewIconSvg = svg"))
        assertTrue(dialog.contains("scale !in 50..200"))
        assertTrue(dialog.contains("ChapterProvider.clearReviewIconCache()"))
        assertTrue(dialog.contains("ChapterProvider.refreshReviewColumnsForStyleChange()"))
        assertTrue(layout.contains("@+id/tv_review_icon_svg"))
        assertTrue(layout.contains("@+id/tv_review_icon_size"))
    }

    @Test
    fun `svg text parsing keeps the existing bitmap limits`() {
        val source = projectFile(
            "src/main/java/io/legado/app/utils/SvgUtils.kt"
        ).readText().normalizeLines()

        assertTrue(source.contains("MAX_SVG_TEXT_LENGTH = 512 * 1024"))
        assertTrue(source.contains("fun createBitmapFromSvgText("))
        assertTrue(source.contains("fun getAspectRatioFromSvgText("))
        assertTrue(source.contains("calculateSvgBitmapSize("))
    }

    private fun String.normalizeLines(): String = replace("\r\n", "\n")

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
