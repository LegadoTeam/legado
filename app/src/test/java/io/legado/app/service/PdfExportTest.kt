package io.legado.app.service

import io.legado.app.exception.NoStackTraceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PdfExportTest {

    @Test
    fun `text pagination continues long paragraphs across pages`() {
        val tops = intArrayOf(0, 20, 40, 60)
        val bottoms = intArrayOf(18, 38, 58, 78)

        assertEquals(2, findPdfPageLineEnd(tops, bottoms, 0, 39))
        assertEquals(4, findPdfPageLineEnd(tops, bottoms, 2, 40))
        assertEquals(1, findPdfPageLineEnd(tops, bottoms, 0, 1))
        assertEquals(4, findPdfPageLineEnd(tops, bottoms, 4, 100))
    }

    @Test
    fun `mixed content keeps image order and duplicate occurrences`() {
        val blocks = splitPdfContentBlocks(
            "text-a<img src=\"image-a\">text-b<img src=\"image-a\">text-c"
        )

        assertEquals(
            listOf(
                PdfContentBlock.Text("text-a"),
                PdfContentBlock.Image("image-a"),
                PdfContentBlock.Text("text-b"),
                PdfContentBlock.Image("image-a"),
                PdfContentBlock.Text("text-c")
            ),
            blocks
        )
    }

    @Test
    fun `image book keeps raw image order and duplicates while ignoring text`() {
        val blocks = imagePdfContentBlocks(
            "ignored<img src=\"image-a\">more<img src=\"image-a\">",
            isVolume = false
        )

        assertEquals(
            listOf(
                PdfContentBlock.Image("image-a"),
                PdfContentBlock.Image("image-a")
            ),
            blocks
        )
    }

    @Test
    fun `image book requires content and images outside volume chapters`() {
        assertThrows(NoStackTraceException::class.java) {
            imagePdfContentBlocks(null, isVolume = false)
        }
        assertThrows(NoStackTraceException::class.java) {
            imagePdfContentBlocks("text only", isVolume = false)
        }
        assertEquals(emptyList<PdfContentBlock.Image>(), imagePdfContentBlocks(null, true))
        assertEquals(emptyList<PdfContentBlock.Image>(), imagePdfContentBlocks("", true))
    }

    @Test
    fun `bitmap sampling bounds extreme aspect ratios`() {
        assertEquals(64, calculatePdfBitmapSampleSize(1_000, 50_000, 499, 746))
        assertEquals(64, calculatePdfBitmapSampleSize(50_000, 1_000, 499, 746))
        assertEquals(2, calculatePdfBitmapSampleSize(1_000, 1_000, 499, 746))
        assertEquals(1, calculatePdfBitmapSampleSize(400, 400, 499, 746))
    }

    @Test
    fun `failed staged replacement restores the previous export`() {
        val operations = arrayListOf<String>()

        assertThrows(IllegalStateException::class.java) {
            replaceStagedExport(
                current = "target",
                backupCurrent = {
                    operations.add("backup:$it")
                    "backup"
                },
                activateStaged = {
                    operations.add("activate")
                    null
                },
                restoreCurrent = {
                    operations.add("restore:$it")
                    "target"
                },
                deleteBackup = { operations.add("delete:$it") }
            )
        }

        assertEquals(
            listOf("backup:target", "activate", "restore:backup", "delete:backup"),
            operations
        )
    }

    @Test
    fun `failed restore keeps the backup available`() {
        val operations = arrayListOf<String>()

        val error = assertThrows(IllegalStateException::class.java) {
            replaceStagedExport(
                current = "target",
                backupCurrent = {
                    operations.add("backup:$it")
                    "backup"
                },
                activateStaged = {
                    operations.add("activate")
                    null
                },
                restoreCurrent = {
                    operations.add("restore:$it")
                    null
                },
                deleteBackup = { operations.add("delete:$it") }
            )
        }

        assertEquals(listOf("backup:target", "activate", "restore:backup"), operations)
        assertEquals(1, error.suppressed.size)
    }

    @Test
    fun `pdf export stays wired to the cache screen and closes documents`() {
        val service = projectFile(
            "src/main/java/io/legado/app/service/ExportBookService.kt"
        ).readText().replace("\r\n", "\n")
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/book/cache/CacheActivity.kt"
        ).readText().replace("\r\n", "\n")
        val webDav = projectFile("src/main/java/io/legado/app/help/AppWebDav.kt").readText()
        val exportPdf = service.substringAfter("private suspend fun exportPdf(fileDoc")
            .substringBefore("private fun decodePdfBitmap")

        assertTrue(activity.contains("arrayListOf(\"txt\", \"epub\", \"pdf\")"))
        assertTrue(activity.contains("2 -> \"pdf\""))
        assertTrue(service.contains("\"pdf\" -> exportPdf(exportConfig.path, book)"))
        assertTrue(webDav.contains("upload(uri, FileUtils.getMimeType(fileName))"))
        assertTrue(exportPdf.contains("if (book.isPdf)"))
        assertFalse(exportPdf.contains("book.isImage || book.isPdf"))
        assertTrue(exportPdf.contains("getChapterContentForExport(book, exportChapter)"))
        assertTrue(exportPdf.contains("findPdfPageLineEnd("))
        assertTrue(exportPdf.contains("clipRect("))
        assertTrue(exportPdf.contains(".canvas.withSave"))
        assertTrue(exportPdf.contains("minOf(\n                    1f,"))
        assertTrue(exportPdf.contains("imagePdfContentBlocks(rawContent, exportChapter.isVolume)"))
        assertTrue(exportPdf.contains("splitPdfContentBlocks(displayContent)"))
        assertTrue(exportPdf.contains("ImageProvider.cacheImage(book, src, bookSource)"))
        assertTrue(exportPdf.contains("fun drawImage(path: String): Boolean"))
        assertTrue(exportPdf.contains("if (book.isImage && !rendered)"))
        assertTrue(service.contains("return SvgUtils.createBitmap(path, reqWidth, reqHeight)"))
        assertTrue(exportPdf.contains("replaceBook = replaceBook"))
        assertTrue(exportPdf.contains("pending.pdf"))
        val completedPdf = exportPdf.substringAfter("renderPdf(stagingDoc)")
        assertTrue(completedPdf.trimStart().startsWith("currentCoroutineContext().ensureActive()"))
        assertTrue(exportPdf.contains("installPdfExport("))
        assertFalse(exportPdf.contains("fileDoc.find(filename)?.delete()"))
        assertTrue(exportPdf.contains("finally {"))
        assertTrue(exportPdf.contains("pdf.close()"))

        val imageBlock = exportPdf.substringAfter("is PdfContentBlock.Image -> {")
            .substringBefore("\n                                }")
        assertTrue(imageBlock.contains("currentCoroutineContext().ensureActive()"))
        assertTrue(imageBlock.contains("image.exists() && drawImage(image.absolutePath)"))

        val installPdf = service.substringAfter("private fun installPdfExport(")
            .substringBefore("private fun cleanupExportFile")
        assertTrue(installPdf.contains("renameExportFile(staging, filename)"))
        assertTrue(installPdf.contains("renameExportFile(it, filename)"))
        assertFalse(service.contains("private fun copyExportFile"))

        val renameExport = service.substringAfter("private fun renameExportFile(")
            .substringBefore("private fun installPdfExport")
        assertTrue(renameExport.contains("runCatching"))
        assertTrue(renameExport.contains("DocumentsContract.renameDocument("))
        assertTrue(renameExport.contains("FileDoc.fromUri(renamedUri, false)"))
        assertFalse(renameExport.contains("asDocumentFile()"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .first { it.isFile }
    }
}
