package io.legado.app.help

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JsExtensionsRefreshContractTest {

    @Test
    fun `refresh callbacks live on the shared js extension`() {
        val extensions = File("app/src/main/java/io/legado/app/help/JsExtensions.kt").readText()
        val login = File("app/src/main/java/io/legado/app/ui/login/SourceLoginJsExtensions.kt").readText()

        assertTrue(extensions.contains("fun refreshBookInfo()"))
        assertTrue(extensions.contains("fun refreshBookToc()"))
        assertTrue(extensions.contains("fun refreshContent()"))
        assertTrue(extensions.contains("EventBus.REFRESH_BOOK_INFO"))
        assertTrue(extensions.contains("EventBus.REFRESH_BOOK_TOC"))
        assertTrue(extensions.contains("EventBus.REFRESH_BOOK_CONTENT"))
        assertFalse(login.contains("fun refreshBookInfo()"))
        assertFalse(login.contains("fun refreshBookToc()"))
        assertFalse(login.contains("fun refreshContent()"))
    }
}
