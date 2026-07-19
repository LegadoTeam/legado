package io.legado.app.ui.association

import io.legado.app.data.entities.ReplaceRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplaceRuleImportComparisonTest {

    @Test
    fun `keeps a full query chunk within the parameter limit`() {
        val importedRules = (1L..REPLACE_RULE_QUERY_CHUNK_SIZE.toLong()).map {
            ReplaceRule(id = it)
        }
        val requestedIds = arrayListOf<List<Long>>()

        compareImportedReplaceRules(importedRules) { ids ->
            requestedIds.add(ids)
            emptyList()
        }

        assertEquals(1, requestedIds.size)
        assertEquals(REPLACE_RULE_QUERY_CHUNK_SIZE, requestedIds.single().size)
    }

    @Test
    fun `splits 901 unique ids and removes duplicate query keys`() {
        val importedRules = (1L..901L).map { ReplaceRule(id = it) } + ReplaceRule(id = 1L)
        val requestedIds = arrayListOf<List<Long>>()

        val comparison = compareImportedReplaceRules(importedRules) { ids ->
            requestedIds.add(ids)
            emptyList()
        }

        assertEquals(listOf(900, 1), requestedIds.map { it.size })
        assertEquals((1L..900L).toList(), requestedIds[0])
        assertEquals(listOf(901L), requestedIds[1])
        assertEquals(importedRules.size, comparison.existingRules.size)
    }

    @Test
    fun `restores import order with unordered missing and duplicate results`() {
        val existingOne = ReplaceRule(id = 1L, name = "One")
        val existingTwo = ReplaceRule(id = 2L, name = "Two")
        val importedRules = listOf(
            ReplaceRule(id = 2L),
            ReplaceRule(id = 1L),
            ReplaceRule(id = 3L),
            ReplaceRule(id = 2L),
        )

        val comparison = compareImportedReplaceRules(importedRules) {
            listOf(existingOne, existingTwo)
        }

        assertSame(existingTwo, comparison.existingRules[0])
        assertSame(existingOne, comparison.existingRules[1])
        assertNull(comparison.existingRules[2])
        assertSame(existingTwo, comparison.existingRules[3])
        assertEquals(listOf(false, false, true, false), comparison.selectStatus)
    }

    @Test
    fun `empty imports do not query the database`() {
        var queryCount = 0

        val comparison = compareImportedReplaceRules(emptyList()) {
            queryCount++
            emptyList()
        }

        assertEquals(0, queryCount)
        assertTrue(comparison.existingRules.isEmpty())
        assertTrue(comparison.selectStatus.isEmpty())
    }

    @Test
    fun `existing rules stay unselected even when their content differs`() {
        val importedExisting = ReplaceRule(id = 1L, pattern = "new")
        val importedMissing = ReplaceRule(id = 2L, pattern = "missing")
        val localExisting = ReplaceRule(id = 1L, pattern = "old")

        val comparison = compareImportedReplaceRules(
            listOf(importedExisting, importedMissing)
        ) {
            listOf(localExisting)
        }

        assertFalse(comparison.selectStatus[0])
        assertTrue(comparison.selectStatus[1])
    }
}
