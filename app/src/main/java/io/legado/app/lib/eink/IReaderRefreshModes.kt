package io.legado.app.lib.eink

/** Values used by the stock 4.1.0 refresh-mode converters, not settings UI indexes. */
internal object IReaderRefreshModes {
    const val FULL = 98
    const val DEFAULT = 99
    const val IMAGE = 4196
    const val GLD = 4197
    const val FAST = 135268
    const val TOP_SPEED = 69732

    fun isKnown(mode: Int): Boolean = when (mode) {
        FULL, DEFAULT, IMAGE, GLD, FAST, TOP_SPEED -> true
        else -> false
    }
}

/**
 * Converts the stock read_refresh_pattern setting to an EPDC post mode.
 *
 * DeviceManager reads /dev/pix_fmt: 0x400 selects the 4-bit converter, while 0x500
 * identifies the other documented waveform. Unknown or inaccessible formats retain
 * the established mode 99 rather than guessing a device-specific fast waveform.
 */
internal fun iReaderRefreshMode(pattern: Int?, waveform: String?): Int {
    if (pattern == 4097) return IReaderRefreshModes.FULL
    if (waveform?.trim()?.lowercase() != "0x500") return IReaderRefreshModes.DEFAULT
    return when (pattern) {
        4100 -> IReaderRefreshModes.FAST
        4101 -> IReaderRefreshModes.TOP_SPEED
        4102 -> IReaderRefreshModes.GLD
        4104 -> IReaderRefreshModes.IMAGE
        else -> IReaderRefreshModes.DEFAULT
    }
}
