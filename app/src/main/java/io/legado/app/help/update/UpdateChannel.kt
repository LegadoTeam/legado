package io.legado.app.help.update

enum class UpdateChannel(val preferenceValue: String) {
    STABLE("stable"),
    LATEST("latest");

    companion object {
        fun defaultForVersion(versionName: String): UpdateChannel =
            if (versionName.contains("-beta.", ignoreCase = true)) LATEST else STABLE

        fun resolve(savedValue: String?, versionName: String): UpdateChannel =
            entries.firstOrNull { it.preferenceValue == savedValue }
                ?: defaultForVersion(versionName)
    }
}
