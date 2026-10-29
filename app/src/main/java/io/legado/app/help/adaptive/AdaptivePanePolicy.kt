package io.legado.app.help.adaptive

internal object AdaptivePanePolicy {
    const val MIN_WIDTH_DP = 600
    const val MIN_HEIGHT_DP = 480
    fun allowDraggableDivider(extension: Int) = extension >= 6
    fun useTwoPanes(enabled: Boolean, widthDp: Int, heightDp: Int) =
        enabled && widthDp >= MIN_WIDTH_DP && heightDp >= MIN_HEIGHT_DP


    private val fullScreenPrefixes = listOf(".ui.book.read.", ".ui.book.manga.", ".ui.book.audio.",
        ".ui.video.", ".ui.rss.read.", ".ui.book.searchContent.")
    fun isReader(name: String) = fullScreenPrefixes.any { name.startsWith("io.legado.app" + it) }

    fun isDetail(name: String): Boolean {
        if (isReader(name)) return false
        return listOf(".ui.config.", ".ui.book.", ".ui.rss.", ".ui.replace.",
            ".ui.dict.", ".ui.about.", ".ui.main.ai.", ".ui.main.bookshelf.",
            ".ui.main.explore.", ".ui.autoTask.", ".ui.txtTocRule.", ".ui.code.",
            ".ui.file.FileManageActivity").any {
            name.startsWith("io.legado.app" + it)
        }
    }

    const val MAIN_ACTIVITY = "io.legado.app.ui.main.MainActivity"
    fun keepNavigationInTask(enabled: Boolean, caller: String, target: String) =
        enabled && (caller == MAIN_ACTIVITY || isDetail(caller)) && isDetail(target)
}
