package io.legado.app.ui.book.toc

import io.legado.app.model.localBook.PdfOutlineNode

internal class PdfOutlineListState(val nodes: List<PdfOutlineNode>, expanded: Boolean) {
    private val byId = nodes.associateBy { it.id }
    private val parents = nodes.mapNotNullTo(hashSetOf()) { it.parentId }
    private val collapsed = hashSetOf<Int>()

    init { setExpanded(expanded) }

    fun setExpanded(expanded: Boolean) {
        collapsed.clear()
        if (!expanded) collapsed.addAll(parents)
    }

    fun toggle(id: Int) {
        if (id in parents && !collapsed.add(id)) collapsed.remove(id)
    }

    fun items(query: String?, reverseOrder: Boolean = false): List<PdfOutlineRow> {
        val search = query?.trim().orEmpty()
        val included = if (search.isEmpty()) null else hashSetOf<Int>().apply {
            nodes.filter { it.title.contains(search, ignoreCase = true) }.forEach { node ->
                var current: PdfOutlineNode? = node
                while (current != null && add(current.id)) current = byId[current.parentId]
            }
        }
        val hidden = hashSetOf<Int>()
        val ordered = if (!reverseOrder) nodes else buildList {
            val children = nodes.groupBy { it.parentId }
            fun visit(parentId: Int?) {
                children[parentId]?.asReversed()?.forEach { node ->
                    add(node)
                    visit(node.id)
                }
            }
            visit(null)
        }
        return ordered.mapNotNull { node ->
            if (included != null && node.id !in included) return@mapNotNull null
            if (included == null && node.parentId in hidden) {
                hidden.add(node.id)
                return@mapNotNull null
            }
            val folded = included == null && node.id in collapsed
            if (folded) hidden.add(node.id)
            PdfOutlineRow(node, node.id in parents, folded, included == null && node.id in parents)
        }
    }
}

internal data class PdfOutlineRow(
    val node: PdfOutlineNode,
    val hasChildren: Boolean,
    val collapsed: Boolean,
    val canToggle: Boolean,
)
