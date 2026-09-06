package io.legado.app.ui.rss.read

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadRssSubresourceContractTest {

    @Test
    fun `rss webview proxies cronet resources without buffering media`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/ui/rss/read/ReadRssActivity.kt"
        )

        assertTrue(source.contains("getCronetResource(url, request)"))
        assertTrue(source.contains("AppConfig.isCronet"))
        assertTrue(source.contains("request.method != \"GET\""))
        assertTrue(source.contains("body.byteStream()"))
        assertTrue(source.contains("response.code"))
        assertTrue(source.contains("response.headers.toMultimap()"))
        assertTrue(source.contains("webCookieManager.getCookie(url)"))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
