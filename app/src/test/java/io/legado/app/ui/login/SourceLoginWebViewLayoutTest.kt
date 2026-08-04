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

    @Test
    fun `web view roots stay above system bars and keyboard`() {
        val sources = listOf(
            readProjectFile("src/main/java/io/legado/app/ui/login/WebViewLoginFragment.kt"),
            readProjectFile("src/main/java/io/legado/app/ui/browser/WebViewActivity.kt")
        )

        sources.forEach { source ->
            assertTrue(source.contains("setOnApplyWindowInsetsListenerCompat"))
            assertTrue(
                source.contains(
                    "WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()"
                )
            )
            assertTrue(source.contains("bottomPadding = windowInsets.getInsets(typeMask).bottom"))
        }
    }

    private fun readProjectFile(pathInApp: String): String {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()
    }
}
