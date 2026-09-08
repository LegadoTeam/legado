package io.legado.app.ui.config

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoverConfigRefreshContractTest {
    @Test
    fun `default cover preference refreshes cover model and bookshelf`() {
        val source = File("src/main/java/io/legado/app/ui/config/CoverConfigFragment.kt").readText()
        val block = source.substringAfter("when (key) {").substringBefore("PreferKey.defaultCover,")
        assertTrue(block.contains("PreferKey.useDefaultCover"))
        assertTrue(block.contains("BookCover.upDefaultCover()"))
        assertTrue(block.contains("EventBus.BOOKSHELF_REFRESH"))
    }
}
