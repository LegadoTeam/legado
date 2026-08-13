package io.legado.app.model.webBook

import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.BookAuthorIdentity

/**
 * Search-result merge (RFC-003). Delegates identity to [BookAuthorIdentity].
 */
object SearchBookMerge {

    fun effectiveAuthor(raw: String?): String = BookAuthorIdentity.effectiveAuthor(raw)

    fun sameBookForMerge(
        existing: SearchBook,
        incoming: SearchBook,
        peers: List<SearchBook>,
    ): Boolean = BookAuthorIdentity.sameSearchBook(existing, incoming, peers)

    /** Fold [incoming] into [target]: origins + keep real author. */
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
     * Rebuild display rows from raw per-source hits (RFC-003 §4.6).
     * Peers for each title = all raw hits with that trimmed name.
     */
    fun rebuildFromRawHits(rawHits: List<SearchBook>): List<SearchBook> {
        if (rawHits.isEmpty()) return emptyList()
        val merged = arrayListOf<SearchBook>()
        for (hit in rawHits) {
            val working = hit.copy()
            val namePeers = rawHits.filter {
                BookAuthorIdentity.equalName(it.name, working.name)
            }
            var absorbed = false
            for (existing in merged) {
                if (!BookAuthorIdentity.equalName(existing.name, working.name)) continue
                if (sameBookForMerge(existing, working, namePeers)) {
                    absorb(existing, working)
                    absorbed = true
                    break
                }
            }
            if (!absorbed) {
                merged.add(working)
            }
        }
        return merged
    }
}
