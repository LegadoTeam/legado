package io.legado.app.help.book

import io.legado.app.data.entities.SearchBook

/**
 * Shared author identity for search display merge and lossless shelf add.
 *
 * Placeholder / empty authors are effective-empty. Same trimmed title may merge
 * weak↔real only when peers have exactly one distinct real author.
 */
object BookAuthorIdentity {

    val PLACEHOLDER_AUTHORS: Set<String> = setOf(
        "未知", "无", "佚名", "无名氏", "无名", "未知作者", "作者未知", "作者不详", "不详",
        "暂无", "暂无作者", "匿名", "none", "null", "unknown", "n/a", "na",
    )

    fun effectiveAuthor(raw: String?): String {
        val a = raw?.trim().orEmpty()
        if (a.isEmpty()) return ""
        if (a.lowercase() in PLACEHOLDER_AUTHORS) return ""
        return a
    }

    fun isWeakAuthor(raw: String?): Boolean = effectiveAuthor(raw).isEmpty()

    fun equalName(a: String?, b: String?): Boolean =
        a.orEmpty().trim() == b.orEmpty().trim()

    fun distinctRealAuthors(rawAuthors: Iterable<String?>): Set<String> {
        val out = linkedSetOf<String>()
        for (raw in rawAuthors) {
            val e = effectiveAuthor(raw)
            if (e.isNotEmpty()) out.add(e)
        }
        return out
    }

    fun soleRealAuthor(rawAuthors: Iterable<String?>): String? {
        val s = distinctRealAuthors(rawAuthors)
        return s.singleOrNull()
    }

    /**
     * @param peerRawAuthors all same-title peer raw authors (full set — never just the pair).
     */
    fun sameBook(
        nameA: String,
        authorA: String?,
        nameB: String,
        authorB: String?,
        peerRawAuthors: Iterable<String?>,
    ): Boolean {
        if (!equalName(nameA, nameB)) return false
        val a1 = effectiveAuthor(authorA)
        val a2 = effectiveAuthor(authorB)
        if (a1 == a2) return true
        if (a1.isNotEmpty() && a2.isNotEmpty()) return false
        val sole = soleRealAuthor(peerRawAuthors) ?: return false
        return (a1.isEmpty() || a1 == sole) && (a2.isEmpty() || a2 == sole)
    }

    fun sameSearchBook(
        a: SearchBook,
        b: SearchBook,
        sameNamePeers: List<SearchBook>,
    ): Boolean = sameBook(
        a.name,
        a.author,
        b.name,
        b.author,
        sameNamePeers.map { it.author },
    )
}
