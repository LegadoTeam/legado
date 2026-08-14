package io.legado.app.model.webBook

import io.legado.app.data.entities.SearchBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchBookMergeTest {

    private fun book(
        name: String,
        author: String,
        origin: String,
        intro: String? = null,
    ) = SearchBook(
        name = name,
        author = author,
        origin = origin,
        bookUrl = "$origin/$name",
        intro = intro,
    )

    @Test
    fun exactNameAndAuthorStillMerge() {
        val a = book("同时穿越了99个世界", "凤嘲凰", "https://a.com")
        val b = book("同时穿越了99个世界", "凤嘲凰", "https://b.com")
        assertTrue(SearchBookMerge.sameBookForMerge(a, b, listOf(a)))
    }

    @Test
    fun emptyAuthorMergesIntoSoleNonEmptyAuthor() {
        val withAuthor = book("同时穿越了99个世界", "凤嘲凰", "https://a.com")
        val empty = book("同时穿越了99个世界", "", "https://empty.com")
        val peers = listOf(withAuthor, empty)
        assertTrue(SearchBookMerge.sameBookForMerge(withAuthor, empty, peers))
        assertTrue(SearchBookMerge.sameBookForMerge(empty, withAuthor, peers))
    }

    @Test
    fun placeholderYimingMergesLikeEmptyAuthor() {
        val withAuthor = book("同时穿越了99个世界", "凤嘲凰", "https://a.com")
        val yiming = book("同时穿越了99个世界", "佚名", "https://popofree.com")
        val peers = listOf(withAuthor, yiming)
        assertTrue(
            SearchBookMerge.sameBookForMerge(withAuthor, yiming, peers)
        )
        SearchBookMerge.absorb(yiming, withAuthor)
        assertEquals("凤嘲凰", yiming.author)
    }

    @Test
    fun emptyAuthorDoesNotMergeWhenNameHasMultipleAuthors() {
        val a = book("同名书", "作者甲", "https://a.com")
        val b = book("同名书", "作者乙", "https://b.com")
        val empty = book("同名书", "", "https://empty.com")
        val peers = listOf(a, b)
        assertFalse(SearchBookMerge.sameBookForMerge(a, empty, peers))
        assertFalse(SearchBookMerge.sameBookForMerge(b, empty, peers))
        assertFalse(SearchBookMerge.sameBookForMerge(empty, a, peers))
    }

    @Test
    fun differentNonEmptyAuthorsNeverMerge() {
        val a = book("同名书", "作者甲", "https://a.com")
        val b = book("同名书", "作者乙", "https://b.com")
        assertFalse(SearchBookMerge.sameBookForMerge(a, b, listOf(a)))
    }

    @Test
    fun bothEmptyAuthorsMergeByExactAuthorEquality() {
        val a = book("同时穿越了99个世界", "", "https://a.com")
        val b = book("同时穿越了99个世界", "", "https://b.com")
        assertTrue(SearchBookMerge.sameBookForMerge(a, b, listOf(a)))
    }

    @Test
    fun absorbCopiesAuthorAndOriginOntoEmptyTarget() {
        val empty = book("同时穿越了99个世界", "", "https://empty.com", intro = null)
        empty.coverUrl = null
        val rich = book(
            "同时穿越了99个世界",
            "凤嘲凰",
            "https://rich.com",
            intro = "简介……",
        )
        rich.coverUrl = "https://rich.com/cover.jpg"
        SearchBookMerge.absorb(empty, rich)
        assertEquals("凤嘲凰", empty.author)
        assertEquals("简介……", empty.intro)
        assertEquals(null, empty.coverUrl)
        assertTrue(empty.origins.contains("https://rich.com"))
        assertTrue(empty.origins.contains("https://empty.com"))
    }

    @Test
    fun absorbDoesNotCopyCoverFromDifferentOrigin() {
        val target = book("高考后", "七月观天", "https://good.com")
        target.coverUrl = null
        val weak = book("高考后", "佚名", "https://popofree.com")
        weak.coverUrl = "https://popofree.com/cover.jpg"
        SearchBookMerge.absorb(target, weak)
        assertEquals(null, target.coverUrl)
        assertEquals("七月观天", target.author)
    }

    @Test
    fun absorbCopiesCoverWhenOriginMatches() {
        val target = book("高考后", "七月观天", "https://good.com")
        target.coverUrl = null
        val other = book("高考后", "七月观天", "https://good.com")
        other.coverUrl = "https://good.com/cover.jpg"
        SearchBookMerge.absorb(target, other)
        assertEquals("https://good.com/cover.jpg", target.coverUrl)
    }

    @Test
    fun absorbDoesNotOverwriteExistingNonEmptyAuthor() {
        val target = book("同时穿越了99个世界", "凤嘲凰", "https://a.com")
        val other = book("同时穿越了99个世界", "", "https://b.com")
        SearchBookMerge.absorb(target, other)
        assertEquals("凤嘲凰", target.author)
    }

    @Test
    fun emptyDoesNotMergeOnceSecondAuthorAppearsInPeers() {
        // After both authors exist in the bucket, empty must stay separate (rule 1).
        val a = book("同名书", "作者甲", "https://a.com")
        val b = book("同名书", "作者乙", "https://b.com")
        val empty = book("同名书", "", "https://empty.com")
        assertFalse(SearchBookMerge.sameBookForMerge(a, empty, listOf(a, b)))
    }

    @Test
    fun rebuildPrefersRealAuthorUrlWhenWeakArrivesFirst() {
        val yiming = book("高考后", "佚名", "https://popofree.com")
        val real = book("高考后", "七月观天", "https://good.com")
        val merged = SearchBookMerge.rebuildFromRawHits(listOf(yiming, real))
        assertEquals(1, merged.size)
        assertEquals("七月观天", merged.single().author)
        assertEquals("https://good.com/高考后", merged.single().bookUrl)
        assertEquals("https://good.com", merged.single().origin)
        assertTrue(merged.single().origins.contains("https://popofree.com"))
        assertTrue(merged.single().origins.contains("https://good.com"))
    }

    @Test
    fun rebuildMergesPaddedAndTrimmedTitles() {
        val padded = book("高考后 ", "佚名", "https://popofree.com")
        val real = book("高考后", "七月观天", "https://good.com")
        val merged = SearchBookMerge.rebuildFromRawHits(listOf(padded, real))
        assertEquals(1, merged.size)
        assertEquals("七月观天", merged.single().author)
        assertEquals("https://good.com/高考后", merged.single().bookUrl)
    }

    @Test
    fun rebuildKeepsEmptySeparateWhenTwoRealsPresent() {
        val empty = book("同名书", "", "https://empty.com")
        val a = book("同名书", "作者甲", "https://a.com")
        val b = book("同名书", "作者乙", "https://b.com")
        val merged = SearchBookMerge.rebuildFromRawHits(listOf(empty, a, b))
        assertEquals(3, merged.size)
        assertTrue(merged.any { it.author == "作者甲" })
        assertTrue(merged.any { it.author == "作者乙" })
        assertTrue(merged.any { SearchBookMerge.effectiveAuthor(it.author).isEmpty() })
    }

    @Test
    fun rebuildUndoesStickyMergeWhenSecondRealArrivesInRaw() {
        // Raw order: empty, 甲, 乙 — rebuild must not leave empty stuck only on 甲.
        val empty = book("同名书", "佚名", "https://empty.com")
        val a = book("同名书", "作者甲", "https://a.com")
        val b = book("同名书", "作者乙", "https://b.com")
        val merged = SearchBookMerge.rebuildFromRawHits(listOf(empty, a, b))
        assertEquals(3, merged.size)
        val authors = merged.map { SearchBookMerge.effectiveAuthor(it.author) }.toSet()
        assertTrue(authors.contains("作者甲"))
        assertTrue(authors.contains("作者乙"))
        assertTrue(authors.contains(""))
    }
}
