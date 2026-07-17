package io.legado.app.help

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JsExtensionsShowBrowserTest {

    @Test
    fun `generic scripts expose browser overloads through foreground activity`() {
        val source = readProjectFile("src/main/java/io/legado/app/help/JsExtensions.kt")

        assertTrue(source.contains("fun showBrowser(url: String)"))
        assertTrue(source.contains("fun showBrowser(url: String, html: String?)"))
        assertTrue(source.contains("fun showBrowser(url: String, html: String?, preloadJs: String?)"))
        assertTrue(source.contains("fun showBrowser("))
        assertTrue(source.contains("LifecycleHelp.getTopActivity() as? AppCompatActivity"))
        assertTrue(source.contains("activity.supportFragmentManager.isStateSaved"))
    }

    @Test
    fun `top activity only tracks resumed activities`() {
        val source = readProjectFile("src/main/java/io/legado/app/help/LifecycleHelp.kt")

        assertTrue(source.contains("fun getTopActivity(): Activity?"))
        assertTrue(source.contains("resumedActivities.add(WeakReference(activity))"))
        assertTrue(
            source.contains(
                "resumedActivities.removeAll { it.get() == null || it.get() === activity }"
            )
        )
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
