package io.legado.app.model.localBook

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import io.legado.app.data.entities.Book
import io.legado.app.help.book.getLocalUri
import splitties.init.appCtx
import java.io.InputStream
import java.util.Collections
import java.util.IdentityHashMap

data class PdfOutlineNode(
    val id: Int,
    val parentId: Int?,
    val depth: Int,
    val title: String,
    val pageIndex: Int?,
)

/** Navigation metadata only; existing PDF reading segments and saved positions are unchanged. */
object PdfOutline {
    fun read(book: Book): List<PdfOutlineNode> =
        requireNotNull(appCtx.contentResolver.openInputStream(book.getLocalUri())).use(::read)

    internal fun read(input: InputStream): List<PdfOutlineNode> {
        PDFBoxResourceLoader.init(appCtx)
        val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(appCtx.cacheDir)
        return PDDocument.load(input, memory).use { document ->
            val first = document.documentCatalog.documentOutline?.firstChild
                ?: return@use emptyList()
            val pages = IdentityHashMap<COSDictionary, Int>()
            document.pages.forEachIndexed { index, page -> pages[page.cosObject] = index }
            val seen = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
            val pending = ArrayDeque<PendingNode>()
            val nodes = ArrayList<PdfOutlineNode>()
            pending.add(PendingNode(first, null, 0))
            // ponytail: bound malformed/huge outline trees; raise limits only with large-file evidence.
            while (pending.isNotEmpty() && nodes.size < 10_000) {
                val (item, parentId, depth) = pending.removeLast()
                if (!seen.add(item.cosObject)) continue
                item.nextSibling?.let { pending.add(PendingNode(it, parentId, depth)) }
                val id = nodes.size
                val page = runCatching { item.findDestinationPage(document) }.getOrNull()
                nodes.add(PdfOutlineNode(id, parentId, depth, item.title.orEmpty(), page?.let {
                    pages[it.cosObject]
                }))
                if (depth < 64) {
                    item.firstChild?.let { pending.add(PendingNode(it, id, depth + 1)) }
                }
            }
            nodes
        }
    }

    private data class PendingNode(val item: PDOutlineItem, val parentId: Int?, val depth: Int)
}
