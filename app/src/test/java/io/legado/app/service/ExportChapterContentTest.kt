package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExportChapterContentTest {

    @Test
    fun keepsAvailableContentUnchanged() {
        assertEquals("chapter body", resolveExportChapterContent("chapter body", false))
    }

    @Test
    fun skipsOrdinaryChapterWithoutContent() {
        assertNull(resolveExportChapterContent(null, false))
    }

    @Test
    fun keepsVolumeWithoutContentAsEmptySection() {
        assertEquals("", resolveExportChapterContent(null, true))
    }

    @Test
    fun keepsAvailableEmptyContent() {
        assertEquals("", resolveExportChapterContent("", false))
    }

    @Test
    fun keepsLiteralNullContent() {
        assertEquals("null", resolveExportChapterContent("null", false))
    }
}
