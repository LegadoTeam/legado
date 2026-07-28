package io.legado.app.help

internal object SourceSharePassphraseImportPolicy {

    fun shouldScheduleOnResume(privacyPolicyOk: Boolean): Boolean {
        return privacyPolicyOk
    }

    fun canAwaitWindowFocus(
        privacyPolicyOk: Boolean,
        isFinishing: Boolean,
        isResumed: Boolean,
        isFragmentStateSaved: Boolean
    ): Boolean {
        return shouldScheduleOnResume(privacyPolicyOk) &&
            !isFinishing && isResumed && !isFragmentStateSaved
    }

    fun canReadClipboard(
        privacyPolicyOk: Boolean,
        isFinishing: Boolean,
        isResumed: Boolean,
        isFragmentStateSaved: Boolean,
        hasWindowFocus: Boolean
    ): Boolean {
        return canAwaitWindowFocus(
            privacyPolicyOk,
            isFinishing,
            isResumed,
            isFragmentStateSaved
        ) && hasWindowFocus
    }
}
