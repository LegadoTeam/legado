package io.legado.app.help

internal object SourceSharePassphraseImportPolicy {

    fun shouldScheduleOnResume(privacyPolicyOk: Boolean, activityCount: Int): Boolean {
        return privacyPolicyOk && activityCount == 1
    }

    fun canAwaitWindowFocus(
        privacyPolicyOk: Boolean,
        activityCount: Int,
        isFinishing: Boolean,
        isResumed: Boolean,
        isFragmentStateSaved: Boolean
    ): Boolean {
        return shouldScheduleOnResume(privacyPolicyOk, activityCount) &&
            !isFinishing && isResumed && !isFragmentStateSaved
    }

    fun canReadClipboard(
        privacyPolicyOk: Boolean,
        activityCount: Int,
        isFinishing: Boolean,
        isResumed: Boolean,
        isFragmentStateSaved: Boolean,
        hasWindowFocus: Boolean
    ): Boolean {
        return canAwaitWindowFocus(
            privacyPolicyOk,
            activityCount,
            isFinishing,
            isResumed,
            isFragmentStateSaved
        ) && hasWindowFocus
    }
}
