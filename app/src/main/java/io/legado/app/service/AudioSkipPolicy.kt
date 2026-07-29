package io.legado.app.service

internal const val MIN_AUDIO_SKIP_REMAINING_MS = 5_000L

internal data class AudioSkipWindow(
    val introEndMs: Long,
    val outroStartMs: Long,
)

internal fun resolveAudioSkipWindow(
    durationMs: Long,
    introSeconds: Int,
    outroSeconds: Int,
): AudioSkipWindow? {
    if (durationMs <= 0L) return null
    val introMs = introSeconds.coerceAtLeast(0).toLong() * 1_000L
    val outroMs = outroSeconds.coerceAtLeast(0).toLong() * 1_000L
    if (durationMs <= introMs + outroMs + MIN_AUDIO_SKIP_REMAINING_MS) return null
    return AudioSkipWindow(
        introEndMs = introMs,
        outroStartMs = durationMs - outroMs,
    )
}
