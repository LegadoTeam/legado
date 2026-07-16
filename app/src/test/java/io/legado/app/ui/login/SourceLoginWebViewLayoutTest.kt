package io.legado.app.ui.login

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourceLoginWebViewLayoutTest {

    @Test
    fun `web login root paints an opaque app background`() {
        val xml = readProjectFile("src/main/res/layout/fragment_web_view_login.xml")

        assertTrue(
            "Web login must hide the activity underneath while its content is loading",
            xml.contains("android:background=\"@color/background\"")
        )
    }

    private fun readProjectFile(pathInApp: String): String {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()
    }
}
