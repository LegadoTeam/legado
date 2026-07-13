package io.legado.app.service

internal fun resolveExportChapterContent(content: String?, isVolume: Boolean): String? {
    return when {
        content != null -> content
        isVolume -> ""
        else -> null
    }
}
