package io.legado.app.help

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourceSharePassphraseImportPolicyTest {

    @Test
    fun resumeCheckRequiresPrivacyConsentAndOnlyMainActivity() {
        assertTrue(SourceSharePassphraseImportPolicy.shouldScheduleOnResume(true, 1))
        assertFalse(SourceSharePassphraseImportPolicy.shouldScheduleOnResume(false, 1))
        assertFalse(SourceSharePassphraseImportPolicy.shouldScheduleOnResume(true, 2))
        assertFalse(SourceSharePassphraseImportPolicy.shouldScheduleOnResume(true, 0))
    }

    @Test
    fun focusRetryRequiresOnlyActiveMainActivity() {
        assertTrue(canAwaitWindowFocus())
        assertFalse(canAwaitWindowFocus(activityCount = 2))
        assertFalse(canAwaitWindowFocus(isResumed = false))
    }

    @Test
    fun clipboardReadRequiresFocusedActiveUnsavedActivity() {
        assertTrue(canReadClipboard())
        assertFalse(canReadClipboard(privacyPolicyOk = false))
        assertFalse(canReadClipboard(isFinishing = true))
        assertFalse(canReadClipboard(isResumed = false))
        assertFalse(canReadClipboard(isFragmentStateSaved = true))
        assertFalse(canReadClipboard(hasWindowFocus = false))
    }

    @Test
    fun pausedActivityInvalidatesQueuedRead() {
        val source = sequenceOf(
            File("src/main/java/io/legado/app/ui/main/MainActivity.kt"),
            File("app/src/main/java/io/legado/app/ui/main/MainActivity.kt")
        ).firstOrNull(File::isFile)?.readText().orEmpty()
        val onPause = source.section(
            "override fun onPause()",
            "override fun onWindowFocusChanged"
        )
        val delayedRead = source.section(
            "private fun readSourceSharePassphrase(",
            "private fun upBottomBarSkin()"
        )

        assertTrue(onPause.contains("pendingPassphraseRead = false"))
        assertTrue(onPause.contains("passphraseReadGeneration++"))
        assertTrue(delayedRead.contains("generation != passphraseReadGeneration"))
    }

    private fun canAwaitWindowFocus(
        privacyPolicyOk: Boolean = true,
        activityCount: Int = 1,
        isFinishing: Boolean = false,
        isResumed: Boolean = true,
        isFragmentStateSaved: Boolean = false
    ) = SourceSharePassphraseImportPolicy.canAwaitWindowFocus(
        privacyPolicyOk,
        activityCount,
        isFinishing,
        isResumed,
        isFragmentStateSaved
    )

    private fun canReadClipboard(
        privacyPolicyOk: Boolean = true,
        activityCount: Int = 1,
        isFinishing: Boolean = false,
        isResumed: Boolean = true,
        isFragmentStateSaved: Boolean = false,
        hasWindowFocus: Boolean = true
    ) = SourceSharePassphraseImportPolicy.canReadClipboard(
        privacyPolicyOk,
        activityCount,
        isFinishing,
        isResumed,
        isFragmentStateSaved,
        hasWindowFocus
    )

    private fun String.section(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        val end = indexOf(endMarker, start + startMarker.length)
        assertTrue("Missing section start: $startMarker", start >= 0)
        assertTrue("Missing section end: $endMarker", end > start)
        return substring(start, end)
    }
}
