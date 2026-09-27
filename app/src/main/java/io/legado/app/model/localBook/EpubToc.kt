package io.legado.app.model.localBook

import me.ag2s.epublib.domain.TOCReference

/** The EPUB owns navigation identities; persisted chapter indexes continue to identify reading content. */
data class EpubTocNode(
    val id: Int,
    val parentId: Int?,
    val depth: Int,
    val title: String,
    val href: String?,
)

internal fun epubTocNodes(references: List<TOCReference>): List<EpubTocNode> {
    data class Pending(val reference: TOCReference, val parentId: Int?, val depth: Int)
    val pending = ArrayDeque<Pending>()
    references.asReversed().forEach { pending.addLast(Pending(it, null, 0)) }
    return buildList {
        while (pending.isNotEmpty()) {
            val (reference, parentId, depth) = pending.removeLast()
            val id = size
            add(EpubTocNode(id, parentId, depth, reference.title.orEmpty(),
                reference.resource?.let { reference.completeHref }))
            reference.children.orEmpty().asReversed().forEach {
                pending.addLast(Pending(it, id, depth + 1))
            }
        }
    }
}
