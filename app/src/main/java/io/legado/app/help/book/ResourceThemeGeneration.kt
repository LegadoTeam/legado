package io.legado.app.help.book

import android.content.Context
import splitties.init.appCtx

/** Resource provenance is local cache state, so it must not be restored with reading preferences. */
internal object ResourceThemeGeneration {
    private val prefs get() = appCtx.getSharedPreferences("resource_theme_generation", Context.MODE_PRIVATE)

    @Synchronized
    fun current(): Long = prefs.getLong("generation", 0L)

    @Synchronized
    fun changed() {
        prefs.edit().putLong("generation", current() + 1L).apply()
    }

    @Synchronized
    fun observeSystemNight(night: Boolean, followsSystem: Boolean) {
        if (prefs.contains("systemNight") && prefs.getBoolean("systemNight", night) != night && followsSystem) {
            changed()
        }
        prefs.edit().putBoolean("systemNight", night).apply()
    }

    @Synchronized
    fun <T> publish(generation: Long, block: () -> T): T {
        check(current() == generation) { "主题已变化，请重新刷新" }
        return block()
    }
}
