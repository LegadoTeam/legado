package io.legado.app.help

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSharePassphraseImportPolicyTest {

    @Test
    fun resumeCheckRequiresPrivacyConsentAndOnlyMainActivity() {
        assertTrue(SourceSharePassphraseImportPolicy.shouldScheduleOnResume(true, 1))
        assertFalse(SourceSharePassphraseImportPolicy.shouldScheduleOnResume(false, 1))
        assertFalse(SourceSharePassphraseImportPolicy.shouldScheduleOnResume(true, 2))
        assertFalse(SourceSharePassphraseImportPolicy.shouldScheduleOnResume(true, 0))
    }

    @Test
    fun delayedReadRequiresActiveUnsavedActivity() {
        assertTrue(
            SourceSharePassphraseImportPolicy.canReadClipboard(
                privacyPolicyOk = true,
                isFinishing = false,
                isResumed = true,
                isFragmentStateSaved = false
            )
        )
        assertFalse(
            SourceSharePassphraseImportPolicy.canReadClipboard(
                privacyPolicyOk = false,
                isFinishing = false,
                isResumed = true,
                isFragmentStateSaved = false
            )
        )
        assertFalse(
            SourceSharePassphraseImportPolicy.canReadClipboard(
                privacyPolicyOk = true,
                isFinishing = true,
                isResumed = true,
                isFragmentStateSaved = false
            )
        )
        assertFalse(
            SourceSharePassphraseImportPolicy.canReadClipboard(
                privacyPolicyOk = true,
                isFinishing = false,
                isResumed = false,
                isFragmentStateSaved = false
            )
        )
        assertFalse(
            SourceSharePassphraseImportPolicy.canReadClipboard(
                privacyPolicyOk = true,
                isFinishing = false,
                isResumed = true,
                isFragmentStateSaved = true
            )
        )
    }
}
