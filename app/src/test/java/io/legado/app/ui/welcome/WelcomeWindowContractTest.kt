package io.legado.app.ui.welcome

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WelcomeWindowContractTest {

    @Test
    fun `welcome bypass skips content and uses transparent window background`() {
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/welcome/WelcomeActivity.kt"
        )
        val base = projectFile("src/main/java/io/legado/app/base/BaseActivity.kt")
        val manifest = projectFile("src/main/AndroidManifest.xml")
        val styles = projectFile("src/main/res/values/styles.xml")
        val config = projectFile(
            "src/main/java/io/legado/app/ui/config/WelcomeConfigFragment.kt"
        )
        assertTrue(activity.contains("override fun shouldCreateContentView()"))
        assertTrue(activity.contains("override fun shouldShowWindowBackground()"))
        assertTrue(activity.contains("FLAG_ACTIVITY_BROUGHT_TO_FRONT"))
        assertTrue(base.contains("if (!shouldCreateContentView()) return"))
        assertTrue(base.contains("Color.TRANSPARENT"))
        assertTrue(manifest.contains("android:name=\".ui.welcome.WelcomeActivity\""))
        assertTrue(manifest.contains("android:theme=\"@style/AppTheme.Welcome\""))
        assertTrue(styles.contains("android:windowBackground\">@android:color/transparent"))
        assertTrue(config.contains("private fun deleteStoredImage(path: String?)"))
        assertTrue(config.contains("deleteStoredImage(getPrefString(preference.key))"))
        assertTrue(config.contains("file.canonicalFile.parentFile == coversDir.canonicalFile"))
        assertTrue(config.contains("if (oldPath != file.absolutePath) deleteStoredImage(oldPath)"))
    }

    private fun projectFile(pathInApp: String): String =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("Missing project file: $pathInApp")
}
