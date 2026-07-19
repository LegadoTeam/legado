package io.legado.app.ui.association

import io.legado.app.data.entities.RssSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RssSourceImportComparisonTest {

    @Test
    fun `only the first import request starts`() {
        val gate = RssSourceImportRequestGate()

        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
    }

    @Test
    fun `splits 901 unique urls and removes duplicate query keys`() {
        val importedSources = (1..901).map {
            RssSource(sourceUrl = "source-$it")
        } + RssSource(sourceUrl = "source-1")
        val requestedUrls = arrayListOf<List<String>>()

        val comparison = compareImportedRssSources(importedSources) { sourceUrls ->
            requestedUrls.add(sourceUrls)
            emptyList()
        }

        assertEquals(listOf(900, 1), requestedUrls.map { it.size })
        assertEquals((1..900).map { "source-$it" }, requestedUrls[0])
        assertEquals(listOf("source-901"), requestedUrls[1])
        assertEquals(importedSources.size, comparison.existingSources.size)
    }

    @Test
    fun `restores import order with unordered missing and duplicate results`() {
        val existingOne = RssSource(sourceUrl = "source-1")
        val existingTwo = RssSource(sourceUrl = "source-2")
        val importedSources = listOf(
            RssSource(sourceUrl = "source-2"),
            RssSource(sourceUrl = "source-1"),
            RssSource(sourceUrl = "missing"),
            RssSource(sourceUrl = "source-2"),
        )

        val comparison = compareImportedRssSources(importedSources) {
            listOf(existingOne, existingTwo)
        }

        assertSame(existingTwo, comparison.existingSources[0])
        assertSame(existingOne, comparison.existingSources[1])
        assertNull(comparison.existingSources[2])
        assertSame(existingTwo, comparison.existingSources[3])
    }

    @Test
    fun `selects missing and newer imports while keeping current or older unselected`() {
        val importedSources = listOf(
            RssSource(sourceUrl = "missing", lastUpdateTime = 5L),
            RssSource(sourceUrl = "newer", lastUpdateTime = 5L),
            RssSource(sourceUrl = "same", lastUpdateTime = 5L),
            RssSource(sourceUrl = "older", lastUpdateTime = 5L),
        )
        val existingSources = listOf(
            RssSource(sourceUrl = "older", lastUpdateTime = 6L),
            RssSource(sourceUrl = "same", lastUpdateTime = 5L),
            RssSource(sourceUrl = "newer", lastUpdateTime = 4L),
        )

        val comparison = compareImportedRssSources(importedSources) { existingSources }

        assertEquals(listOf(true, true, false, false), comparison.selectStatus)
    }

    @Test
    fun `empty imports do not query the database`() {
        var queryCount = 0

        val comparison = compareImportedRssSources(emptyList()) {
            queryCount++
            emptyList()
        }

        assertEquals(0, queryCount)
        assertTrue(comparison.existingSources.isEmpty())
        assertTrue(comparison.selectStatus.isEmpty())
    }

    @Test
    fun `current local source stays unselected`() {
        val imported = RssSource(sourceUrl = "source", lastUpdateTime = 10L)
        val existing = RssSource(sourceUrl = "source", lastUpdateTime = 10L)

        val comparison = compareImportedRssSources(listOf(imported)) { listOf(existing) }

        assertFalse(comparison.selectStatus.single())
    }
}
