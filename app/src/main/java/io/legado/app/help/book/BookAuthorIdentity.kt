package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook

/**
 * Shared author identity for search + shelf smart-merge (RFC-003).
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

    /** Trimmed title + effective author; 佚名 and empty compare equal. */
    fun sameEffectiveIdentity(a: Book, b: Book): Boolean =
        equalName(a.name, b.name) && effectiveAuthor(a.author) == effectiveAuthor(b.author)

    /** RFC-003 §4.9 — mere durChapterTime without index/pos is not progress. */
    fun hasProgress(book: Book): Boolean =
        book.durChapterIndex > 0 || book.durChapterPos > 0

    /** Distinct non-empty effective authors from raw author strings. */
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

    /**
     * Pick canonical shelf row among same-name candidates (RFC-003 §4.8–4.9).
     * Prefers sole real author, then !notShelf, then recent read.
     */
    fun pickCanonicalShelfBook(candidates: List<Book>): Book? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0]
        val sole = soleRealAuthor(candidates.map { it.author })
        val pool = if (sole != null) {
            val real = candidates.filter { effectiveAuthor(it.author) == sole }
            if (real.isNotEmpty()) real else candidates
        } else {
            candidates
        }
        val visible = pool.filter { !it.isNotShelf }
        val prefer = if (visible.isNotEmpty()) visible else pool
        return prefer.maxWithOrNull(
            compareBy<Book> { hasProgress(it) }
                .thenBy { effectiveAuthor(it.author).isNotEmpty() }
                .thenBy { it.durChapterTime }
                .thenBy { it.durChapterIndex }
                .thenBy { it.durChapterPos }
        )
    }

    /** Whether [retired] may be deleted as non-canonical (never local). */
    fun mayRetireShelfBook(retired: Book, canonical: Book): Boolean {
        if (retired.bookUrl == canonical.bookUrl) return false
        if (retired.isLocal) return false
        if (canonical.isLocal) return false // v1: no local↔web merge-into retire
        return true
    }

    /**
     * Progress winner between two rows (RFC-003 §4.9).
     * Returns true if [a] should keep progress over [b].
     */
    fun preferProgress(a: Book, b: Book): Boolean {
        val aHas = hasProgress(a)
        val bHas = hasProgress(b)
        if (aHas != bHas) return aHas
        if (a.durChapterTime != b.durChapterTime) return a.durChapterTime > b.durChapterTime
        val aReal = effectiveAuthor(a.author).isNotEmpty()
        val bReal = effectiveAuthor(b.author).isNotEmpty()
        if (aReal != bReal) return aReal
        if (a.durChapterIndex != b.durChapterIndex) return a.durChapterIndex > b.durChapterIndex
        return a.durChapterPos >= b.durChapterPos
    }

    fun copyProgress(from: Book, to: Book) {
        to.durChapterIndex = from.durChapterIndex
        to.durChapterPos = from.durChapterPos
        to.durChapterTime = from.durChapterTime
        to.durChapterTitle = from.durChapterTitle
    }

    fun fillBlanksFrom(target: Book, donor: Book) {
        if (target.intro.isNullOrBlank() && !donor.intro.isNullOrBlank()) {
            target.intro = donor.intro
        }
        if (target.coverUrl.isNullOrBlank() && !donor.coverUrl.isNullOrBlank()) {
            target.coverUrl = donor.coverUrl
        }
        if (target.customCoverUrl.isNullOrBlank() && !donor.customCoverUrl.isNullOrBlank()) {
            target.customCoverUrl = donor.customCoverUrl
        }
    }
}
