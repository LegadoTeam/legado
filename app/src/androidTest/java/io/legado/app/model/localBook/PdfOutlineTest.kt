package io.legado.app.model.localBook

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.FileProvider
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import io.legado.app.data.entities.Book

@RunWith(AndroidJUnit4::class)
class PdfOutlineTest {
    @Test
    fun resolvesLegacyAndNameTreeDestinationsAndLocalGotoActions() {
        val bytes = PDDocument().use { document ->
            repeat(3) { document.addPage(PDPage()) }
            fun destination(index: Int) = COSArray().apply {
                add(document.getPage(index).cosObject)
                add(COSName.FIT)
            }
            document.documentCatalog.cosObject.setItem(COSName.DESTS, COSDictionary().apply {
                setItem(COSName.getPDFName("legacy"), destination(0))
            })
            document.documentCatalog.cosObject.setItem(COSName.NAMES, COSDictionary().apply {
                setItem(COSName.DESTS, COSDictionary().apply {
                    setItem(COSName.NAMES, COSArray().apply {
                        add(COSString("named"))
                        add(destination(1))
                    })
                })
            })
            val outline = PDDocumentOutline()
            document.documentCatalog.documentOutline = outline
            outline.addLast(PDOutlineItem().apply {
                title = "旧命名目标"
                cosObject.setItem(COSName.DEST, COSName.getPDFName("legacy"))
            })
            outline.addLast(PDOutlineItem().apply {
                title = "名称树目标"
                cosObject.setItem(COSName.DEST, COSString("named"))
            })
            outline.addLast(PDOutlineItem().apply {
                title = "本地跳转"
                cosObject.setItem(COSName.A, COSDictionary().apply {
                    setItem(COSName.S, COSName.getPDFName("GoTo"))
                    setItem(COSName.D, destination(2))
                })
            })
            outline.addLast(PDOutlineItem().apply {
                title = "外部链接不作为页码"
                cosObject.setItem(COSName.A, COSDictionary().apply {
                    setItem(COSName.S, COSName.URI)
                    setString(COSName.URI, "https://example.invalid/")
                })
            })
            ByteArrayOutputStream().also(document::save).toByteArray()
        }
        assertEquals(listOf(0, 1, 2, null), PdfOutline.read(ByteArrayInputStream(bytes)).map { it.pageIndex })
    }

    @Test
    fun readsCompressedObjectsThroughContentUriWithoutChangingSavedProgress() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File.createTempFile("outline-", ".pdf", context.cacheDir)
        try {
            instrumentation.context.assets.open("pdf-outline-compressed.pdf").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", file)
            val book = Book(bookUrl = uri.toString(), originName = file.name,
                durChapterIndex = 2, durChapterPos = 17)
            val nodes = PdfOutline.read(book)
            assertEquals(listOf("Compressed outline"), nodes.map { it.title })
            assertEquals(listOf(0), nodes.map { it.pageIndex })
            assertEquals(2, book.durChapterIndex)
            assertEquals(17, book.durChapterPos)
        } finally { file.delete() }
    }

    @Test
    fun preservesNestedTitlesSharedPagesAndDestinationOrder() {
        val bytes = PDDocument().use { document ->
            repeat(3) { document.addPage(PDPage()) }
            val outline = PDDocumentOutline()
            document.documentCatalog.documentOutline = outline
            val part = PDOutlineItem().apply { title = "第一部分" }
            val child = PDOutlineItem().apply {
                title = "第二页章节"
                setDestination(document.getPage(1))
            }
            val detail = PDOutlineItem().apply {
                title = "同页小节"
                setDestination(document.getPage(1))
            }
            val earlier = PDOutlineItem().apply {
                title = "返回前言"
                setDestination(document.getPage(0))
            }
            outline.addLast(part)
            part.addLast(child)
            child.addLast(detail)
            outline.addLast(earlier)
            ByteArrayOutputStream().also(document::save).toByteArray()
        }
        val nodes = PdfOutline.read(ByteArrayInputStream(bytes))
        assertEquals(listOf("第一部分", "第二页章节", "同页小节", "返回前言"), nodes.map { it.title })
        assertEquals(listOf(0, 1, 2, 0), nodes.map { it.depth })
        assertEquals(listOf(null, 0, 1, null), nodes.map { it.parentId })
        assertEquals(listOf(null, 1, 1, 0), nodes.map { it.pageIndex })
    }

    @Test(timeout = 15_000)
    fun repeatedOutlineDictionaryDoesNotLoop() {
        val bytes = PDDocument().use { document ->
            document.addPage(PDPage())
            val outline = PDDocumentOutline()
            document.documentCatalog.documentOutline = outline
            val item = PDOutlineItem().apply {
                title = "循环目录"
                setDestination(document.getPage(0))
            }
            outline.addLast(item)
            item.cosObject.setItem(COSName.NEXT, item.cosObject)
            ByteArrayOutputStream().also(document::save).toByteArray()
        }
        assertEquals(1, PdfOutline.read(ByteArrayInputStream(bytes)).size)
    }

    @Test
    fun documentWithoutOutlineHasNoNavigationNodes() {
        val bytes = PDDocument().use { document ->
            document.addPage(PDPage())
            ByteArrayOutputStream().also(document::save).toByteArray()
        }
        assertTrue(PdfOutline.read(ByteArrayInputStream(bytes)).isEmpty())
    }
}
