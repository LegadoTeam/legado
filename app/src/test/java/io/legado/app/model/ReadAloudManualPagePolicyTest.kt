package io.legado.app.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadAloudManualPagePolicyTest {

    @Test
    fun `enabled manual navigation restarts from visible page`() {
        assertTrue(
            ReadAloudManualPagePolicy.shouldRestartFromVisiblePage(
                isReadAloudRunning = true,
                speechDrivenNavigation = false,
                followManualPageTurns = true
            )
        )
    }

    @Test
    fun `disabled or speech driven navigation keeps existing behavior`() {
        assertFalse(
            ReadAloudManualPagePolicy.shouldRestartFromVisiblePage(
                isReadAloudRunning = true,
                speechDrivenNavigation = false,
                followManualPageTurns = false
            )
        )
        assertFalse(
            ReadAloudManualPagePolicy.shouldRestartFromVisiblePage(
                isReadAloudRunning = true,
                speechDrivenNavigation = true,
                followManualPageTurns = true
            )
        )
        assertFalse(
            ReadAloudManualPagePolicy.shouldRestartFromVisiblePage(
                isReadAloudRunning = false,
                speechDrivenNavigation = false,
                followManualPageTurns = true
            )
        )
    }

    @Test
    fun `preference remains opt in and is wired to page navigation`() {
        val preference = readProjectFile("src/main/res/xml/pref_config_aloud.xml")
        val appConfig = readProjectFile("src/main/java/io/legado/app/help/config/AppConfig.kt")
        val readBook = readProjectFile("src/main/java/io/legado/app/model/ReadBook.kt")
        val keyMarker = "android:key=\"readAloudFollowManualPage\""
        val preferenceSection = preference.substringBefore(keyMarker)
            .substringAfterLast("<io.legado.app.lib.prefs.SwitchPreference") +
            keyMarker + preference.substringAfter(keyMarker).substringBefore("/>")

        assertTrue(preferenceSection.contains("android:defaultValue=\"false\""))
        assertTrue(appConfig.contains("PreferKey.readAloudFollowManualPage, false"))
        assertTrue(readBook.contains("prepareReadAloudPageNavigation"))
        assertTrue(
            readBook.lines().count {
                it.contains("restartReadAloudFromVisiblePage = restartReadAloud")
            } >= 3
        )
        assertTrue(readBook.contains("readAloud(!BaseReadAloudService.pause)"))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
