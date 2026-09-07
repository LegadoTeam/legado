package io.legado.app.data.entities

import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HttpTtsCookieJarTest {

    @Test
    fun `auxiliary fields survive import export and editing`() {
        val jsLib = "function sign(text) { return java.md5Encode(text) }"
        val imported = HttpTTS.fromJson(
            """{"name":"test","url":"https://example.com","jsLib":"$jsLib","enabledCookieJar":true}"""
        ).getOrThrow()
        val legacy = HttpTTS.fromJson(
            """{"name":"test","url":"https://example.com"}"""
        ).getOrThrow()
        val roundTrip = HttpTTS.fromJson(GSON.toJson(imported)).getOrThrow()

        assertEquals(jsLib, imported.jsLib)
        assertTrue(imported.enabledCookieJar == true)
        assertNull(legacy.jsLib)
        assertEquals(false, legacy.enabledCookieJar)
        assertEquals(jsLib, roundTrip.jsLib)
        assertTrue(roundTrip.enabledCookieJar == true)

        val source = appFile("src/main/java/io/legado/app/ui/book/read/config/HttpTtsEditDialog.kt")
            .readText()
        val layout = appFile("src/main/res/layout/dialog_http_tts_edit.xml").readText()
        assertTrue(source.contains("tvJsLib.setText(httpTTS.jsLib)"))
        assertTrue(source.contains("jsLib = binding.tvJsLib.text?.toString()"))
        assertTrue(source.contains("cbIsEnableCookie.isChecked = httpTTS.enabledCookieJar == true"))
        assertTrue(source.contains("enabledCookieJar = binding.cbIsEnableCookie.isChecked"))
        assertTrue(layout.contains("android:id=\"@+id/tv_jsLib\""))
        assertTrue(layout.contains("android:id=\"@+id/cb_is_enable_cookie\""))
    }

    private fun appFile(path: String): File =
        listOf(File(path), File("app/$path")).first { it.isFile }
}
