package io.legado.app.help.book

import io.legado.app.data.entities.ReadRecordBook
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadRecordIndexTest {

    @Test
    fun `same name with a different author is not the same book`() {
        val index = ReadRecordIndex.of(listOf(ReadRecordBook("剑来", "烽火戏诸侯")))

        assertTrue(index.contains("剑来", "烽火戏诸侯"))
        assertFalse(index.contains("剑来", "佚名"))
        assertFalse(index.contains("剑来2", "烽火戏诸侯"))
    }

    @Test
    fun `records of the same name from several devices are matched separately`() {
        val index = ReadRecordIndex.of(
            listOf(
                ReadRecordBook("剑来", "烽火戏诸侯"),
                ReadRecordBook("剑来", "另一个作者")
            )
        )

        assertTrue(index.contains("剑来", "烽火戏诸侯"))
        assertTrue(index.contains("剑来", "另一个作者"))
        assertFalse(index.contains("剑来", "第三个作者"))
    }

    @Test
    fun `a record without author still matches by name`() {
        val index = ReadRecordIndex.of(listOf(ReadRecordBook("剑来", "")))

        assertTrue(index.contains("剑来", "烽火戏诸侯"))
        assertTrue(index.contains("剑来", ""))
        assertFalse(index.contains("雪中悍刀行", "烽火戏诸侯"))
    }

    @Test
    fun `a search result without author falls back to the name`() {
        val index = ReadRecordIndex.of(listOf(ReadRecordBook("剑来", "烽火戏诸侯")))

        assertTrue(index.contains("剑来", ""))
        assertTrue(index.contains("剑来", "  "))
    }

    @Test
    fun `an empty index matches nothing`() {
        val index = ReadRecordIndex.of(emptyList())

        assertTrue(index.isEmpty)
        assertFalse(index.contains("剑来", "烽火戏诸侯"))
        assertFalse(index.contains("", ""))
    }
}
