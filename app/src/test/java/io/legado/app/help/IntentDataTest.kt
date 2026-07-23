package io.legado.app.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntentDataTest {

    @Test
    fun `automatic keys keep rapid values isolated`() {
        val entries = (0 until 10_000).map { value ->
            IntentData.put(value) to value
        }
        val values = entries.map { (key, _) -> IntentData.get<Int>(key) }

        assertEquals(entries.size, entries.map { it.first }.toSet().size)
        entries.forEachIndexed { index, (_, expected) ->
            assertEquals(expected, values[index])
        }
        entries.forEach { (key, _) ->
            assertNull(IntentData.get<Int>(key))
        }
    }

    @Test
    fun `explicit keys remain caller controlled`() {
        assertEquals("fixed", IntentData.put("fixed", "value"))
        assertEquals("value", IntentData.get<String>("fixed"))
    }
}
