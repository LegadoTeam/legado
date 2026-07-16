package io.legado.app.help

internal object SourceSharePassphraseImportPolicy {

    fun shouldScheduleOnResume(privacyPolicyOk: Boolean, activityCount: Int): Boolean {
        return privacyPolicyOk && activityCount == 1
    }

    fun canReadClipboard(
        privacyPolicyOk: Boolean,
        isFinishing: Boolean,
        isResumed: Boolean,
        isFragmentStateSaved: Boolean
    ): Boolean {
        return privacyPolicyOk && !isFinishing && isResumed && !isFragmentStateSaved
    }
}
