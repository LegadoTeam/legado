package io.legado.app.ui.login

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourceLoginWebViewLayoutTest {

    @Test
    fun `web login root paints the runtime theme background`() {
        val xml = readProjectFile("src/main/res/layout/fragment_web_view_login.xml")
        val fragment = readProjectFile(
            "src/main/java/io/legado/app/ui/login/WebViewLoginFragment.kt"
        )

        assertFalse(
            "Web login must not override the user-selected background with a static color",
            xml.contains("android:background=\"@color/background\"")
        )
        assertTrue(
            "Web login must paint an opaque runtime theme background",
            fragment.contains("binding.root.setBackgroundColor(requireContext().backgroundColor)")
        )
    }

    private fun readProjectFile(pathInApp: String): String {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()
    }
}
