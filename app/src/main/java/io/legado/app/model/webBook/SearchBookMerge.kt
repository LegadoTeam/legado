package io.legado.app.model.webBook

import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.BookAuthorIdentity

/**
 * Search-result display merge. Does not write the bookshelf.
 */
object SearchBookMerge {

    fun effectiveAuthor(raw: String?): String = BookAuthorIdentity.effectiveAuthor(raw)

    fun sameBookForMerge(
        existing: SearchBook,
        incoming: SearchBook,
        peers: List<SearchBook>,
    ): Boolean = BookAuthorIdentity.sameSearchBook(existing, incoming, peers)

    /** Fold [incoming] into [target]: origins + keep real author. Does not change URL. */
    fun absorb(target: SearchBook, incoming: SearchBook) {
        target.addOrigin(incoming.origin)
        val targetAuthor = BookAuthorIdentity.effectiveAuthor(target.author)
        val incomingAuthor = BookAuthorIdentity.effectiveAuthor(incoming.author)
        when {
            targetAuthor.isEmpty() && incomingAuthor.isNotEmpty() ->
                target.author = incomingAuthor
            targetAuthor.isEmpty() && target.author.isNotBlank() ->
                target.author = ""
        }
        if (target.intro.isNullOrBlank() && !incoming.intro.isNullOrBlank()) {
            target.intro = incoming.intro
        }
        if (target.coverUrl.isNullOrBlank() && !incoming.coverUrl.isNullOrBlank()) {
            target.coverUrl = incoming.coverUrl
        }
    }

    /**
     * Rebuild display rows from raw per-source hits.
     * Groups by trimmed name first (O(n)); click target is a real-author hit when one exists.
     */
    fun rebuildFromRawHits(rawHits: List<SearchBook>): List<SearchBook> {
        if (rawHits.isEmpty()) return emptyList()
        val out = arrayListOf<SearchBook>()
        for (hits in rawHits.groupBy { BookAuthorIdentity.canonicalName(it.name) }.values) {
            out.addAll(mergeNameGroup(hits))
        }
        return out
    }

    private fun mergeNameGroup(hits: List<SearchBook>): List<SearchBook> {
        val copies = hits.map { it.copy() }
        val authors = copies.map { it.author }
        val sole = BookAuthorIdentity.soleRealAuthor(authors)
        if (sole != null) {
            val canonical = pickCanonical(copies.filter {
                BookAuthorIdentity.effectiveAuthor(it.author) == sole
            }) ?: pickCanonical(copies) ?: return emptyList()
            for (hit in copies) {
                if (hit === canonical) continue
                absorb(canonical, hit)
            }
            return listOf(canonical)
        }
        val byKey = linkedMapOf<String, ArrayList<SearchBook>>()
        for (hit in copies) {
            val key = BookAuthorIdentity.effectiveAuthor(hit.author)
            byKey.getOrPut(key) { arrayListOf() }.add(hit)
        }
        return byKey.values.map { cluster ->
            val canonical = pickCanonical(cluster) ?: cluster.first()
            for (hit in cluster) {
                if (hit === canonical) continue
                absorb(canonical, hit)
            }
            canonical
        }
    }

    /** Prefer a real-author row so the list author matches the detail URL. */
    private fun pickCanonical(hits: List<SearchBook>): SearchBook? {
        if (hits.isEmpty()) return null
        return hits.maxWithOrNull(
            compareBy<SearchBook> { BookAuthorIdentity.effectiveAuthor(it.author).isNotEmpty() }
                .thenBy { !it.intro.isNullOrBlank() }
                .thenBy { !it.coverUrl.isNullOrBlank() }
                .thenBy { it.origins.size }
        )
    }
}
