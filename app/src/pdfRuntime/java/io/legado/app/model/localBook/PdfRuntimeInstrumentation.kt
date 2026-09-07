package io.legado.app.model.localBook

import android.app.Activity
import android.app.Instrumentation
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.annotation.Keep
import androidx.core.content.FileProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import io.legado.app.BuildConfig
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import java.io.File

@Keep
class PdfRuntimeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        super.onStart()
        try {
            check(!BuildConfig.DEBUG) { "This check must exercise release shrinking" }
            waitForIdleSync()
            verifyOutlinesAndRendering()
            finish(Activity.RESULT_OK, Bundle().apply {
                putString("stream", "PDF_RUNTIME_PASSED: named/direct/compressed outlines, content URI and rendered page\n")
            })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", error.stackTraceToString()) })
        }
    }

    private fun verifyOutlinesAndRendering() {
        PDFBoxResourceLoader.init(targetContext)
        val file = File.createTempFile("pdf-runtime-", ".pdf", targetContext.cacheDir)
        val uri = FileProvider.getUriForFile(targetContext, "${targetContext.packageName}.fileProvider", file)
        val book = Book(bookUrl = uri.toString(), originName = file.name, type = BookType.local or BookType.text,
            durChapterIndex = 2, durChapterPos = 17)
        try {
            PDDocument().use { document ->
                repeat(2) { document.addPage(PDPage()) }
                val outline = PDDocumentOutline()
                document.documentCatalog.documentOutline = outline
                outline.addLast(PDOutlineItem().apply { title = "Direct"; setDestination(document.getPage(1)) })
                document.documentCatalog.cosObject.setItem(COSName.DESTS, COSDictionary().apply {
                    setItem(COSName.getPDFName("named"), COSArray().apply {
                        add(document.getPage(0).cosObject)
                        add(COSName.getPDFName("Fit"))
                    })
                })
                outline.addLast(PDOutlineItem().apply {
                    title = "Named"
                    cosObject.setItem(COSName.DEST, COSName.getPDFName("named"))
                })
                document.save(file)
            }
            check(PdfOutline.read(book).map { it.pageIndex } == listOf(1, 0))
            targetContext.assets.open("pdf-outline-compressed.pdf").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val compressed = PdfOutline.read(book)
            check(compressed.single().title == "Compressed outline" && compressed.single().pageIndex == 0)
            check(book.durChapterIndex == 2 && book.durChapterPos == 17)
            val chapters = PdfFile.getChapterList(book)
            check(chapters.single().url == "pdf_0")
            check(PdfFile.getContent(book, chapters.single())!!.contains("src=\"0\""))
            requireNotNull(PdfFile.getImage(book, "0")).use { stream ->
                val bitmap = requireNotNull(BitmapFactory.decodeStream(stream))
                try { check(bitmap.width > 0 && bitmap.height > 0) } finally { bitmap.recycle() }
            }
        } finally {
            PdfFile.clear(book.bookUrl)
            file.delete()
        }
    }
}
