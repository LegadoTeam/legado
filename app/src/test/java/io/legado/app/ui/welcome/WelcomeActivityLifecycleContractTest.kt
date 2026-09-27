package io.legado.app.ui.welcome

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WelcomeActivityLifecycleContractTest {

    private val source by lazy {
        projectFile(
            "src/main/java/io/legado/app/ui/welcome/WelcomeActivity.kt"
        ).readText().replace("\r\n", "\n")
    }

    @Test
    fun `delayed main launch belongs to activity lifecycle`() {
        val onActivityCreated = section(
            "override fun onActivityCreated",
            "override fun setupSystemBar",
        )

        assertTrue(source.contains("private var startMainJob: Job? = null"))
        assertTrue(onActivityCreated.contains("startMainJob = lifecycleScope.launch"))
        assertTrue(onActivityCreated.contains("delay(welcomeShowTime.toLong())"))
        val delayIndex = onActivityCreated.indexOf("delay(welcomeShowTime.toLong())")
        assertTrue(onActivityCreated.indexOf("startMainActivity()", delayIndex) > delayIndex)
        assertFalse(source.contains("postDelayed"))
    }

    @Test
    fun `finishing welcome cancels delayed main launch first`() {
        val finish = section("override fun finish()", "override fun upBackgroundImage")

        assertTrue(finish.contains("startMainJob?.cancel()"))
        assertTrue(finish.indexOf("startMainJob?.cancel()") < finish.indexOf("super.finish()"))
    }

    @Test
    fun `welcome content visibility does not depend on custom background`() {
        val background = section("override fun upBackgroundImage()", "private fun startMainActivity")
        val customBackground = background.indexOf("if (getPrefBoolean(PreferKey.customWelcome))")

        assertTrue(customBackground > 0)
        listOf(
            "binding.tvLegado.visible(showText)",
            "binding.ivBook.visible(showIcon)",
            "binding.tvGzh.visible(showText)",
        ).forEach { visibilityCall ->
            assertTrue(background.indexOf(visibilityCall) in 0 until customBackground)
        }
    }

    private fun section(startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, start)
        require(start >= 0 && end > start)
        return source.substring(start, end)
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
