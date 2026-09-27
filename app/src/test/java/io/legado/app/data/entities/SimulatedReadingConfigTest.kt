package io.legado.app.data.entities

import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class SimulatedReadingConfigTest {

    @Test
    fun `read config stores LocalDate as ISO string`() {
        val config = Book.ReadConfig(startDate = LocalDate.of(2026, 8, 30))

        val json = GSON.toJson(config)
        val restored = GSON.fromJson(json, Book.ReadConfig::class.java)

        assertEquals(
            "2026-08-30",
            GSON.toJsonTree(config).asJsonObject.get("startDate").asString,
        )
        assertEquals(config.startDate, restored.startDate)
    }

    @Test
    fun `read config accepts legacy LocalDate object`() {
        val restored = GSON.fromJson(
            """{"startDate":{"year":2024,"month":7,"day":8}}""",
            Book.ReadConfig::class.java,
        )

        assertEquals(LocalDate.of(2024, 7, 8), restored.startDate)
    }

    @Test
    fun `invalid legacy LocalDate becomes absent`() {
        val restored = GSON.fromJson(
            """{"readSimulating":true,"startDate":{"year":1,"month":"bad","day":0}}""",
            Book.ReadConfig::class.java,
        )

        assertNull(restored.startDate)
        val book = Book(readConfig = restored, totalChapterNum = 20)
        assertEquals(3, book.simulatedTotalChapterNum())
    }

    @Test
    fun `invalid ISO LocalDate becomes absent`() {
        val restored = GSON.fromJson(
            """{"startDate":"0001-00-00"}""",
            Book.ReadConfig::class.java,
        )

        assertNull(restored.startDate)
    }
}
