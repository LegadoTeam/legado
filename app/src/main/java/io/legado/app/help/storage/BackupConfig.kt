package io.legado.app.help.storage

import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import splitties.init.appCtx

/**
 * 备份配置
 */
@Suppress("ConstPropertyName")
object BackupConfig {

    private val ignoreConfigPath = FileUtils.getPath(appCtx.filesDir, "restoreIgnore.json")
    val ignoreConfig: HashMap<String, Boolean> by lazy {
        val file = FileUtils.createFileIfNotExist(ignoreConfigPath)
        val json = file.readText()
        GSON.fromJsonObject<HashMap<String, Boolean>>(json).getOrNull() ?: hashMapOf()
    }

    private const val readConfigKey = "readConfig"
    private const val themeConfigKey = "themeConfig"
    private const val coverConfigKey = "coverConfig"
    private const val localBookKey = "localBook"
    private const val cookieIgnoreKey = "ignoreCookies"
    private const val runtimeSourceCacheIgnoreKey = "ignoreSourceVariables"

    internal const val bookshelfContentKey = "backupBookshelf"
    internal const val annotationContentKey = "backupAnnotations"
    internal const val sourceContentKey = "backupSources"
    internal const val ruleContentKey = "backupRules"
    internal const val historyContentKey = "backupHistory"
    internal const val settingContentKey = "backupSettings"
    internal const val persistedCoverContentKey = "backupPersistedCovers"
    internal const val otherCoverContentKey = "backupOtherCovers"
    internal const val backgroundContentKey = "backupBackgrounds"
    internal const val cookieContentKey = "backupCookies"
    internal const val cookieFileName = "cookies.json"
    internal const val runtimeSourceCacheContentKey = "backupSourceVariables"
    internal const val runtimeSourceCacheFileName = "runtimeSourceCache.json"

    val contentKeys = arrayOf(
        bookshelfContentKey,
        annotationContentKey,
        sourceContentKey,
        ruleContentKey,
        historyContentKey,
        settingContentKey,
        persistedCoverContentKey,
        otherCoverContentKey,
        backgroundContentKey,
        cookieContentKey,
        runtimeSourceCacheContentKey,
    )

    val contentTitles = arrayOf(
        appCtx.getString(R.string.backup_content_bookshelf),
        appCtx.getString(R.string.backup_content_annotations),
        appCtx.getString(R.string.backup_content_sources),
        appCtx.getString(R.string.backup_content_rules),
        appCtx.getString(R.string.backup_content_history),
        appCtx.getString(R.string.backup_content_settings),
        appCtx.getString(R.string.backup_content_persisted_covers),
        appCtx.getString(R.string.backup_content_other_covers),
        appCtx.getString(R.string.backup_content_backgrounds),
        appCtx.getString(R.string.backup_content_cookies),
        appCtx.getString(R.string.backup_content_source_variables),
    )

    //配置忽略key
    val ignoreKeys = arrayOf(
        readConfigKey,
        PreferKey.themeMode,
        themeConfigKey,
        coverConfigKey,
        PreferKey.bookshelfLayout,
        PreferKey.showRss,
        PreferKey.threadCount,
        localBookKey,
        cookieIgnoreKey,
        runtimeSourceCacheIgnoreKey,
    )

    //配置忽略标题
    val ignoreTitle = arrayOf(
        appCtx.getString(R.string.read_config),
        appCtx.getString(R.string.theme_mode),
        appCtx.getString(R.string.theme_config),
        appCtx.getString(R.string.cover_config),
        appCtx.getString(R.string.bookshelf_layout),
        appCtx.getString(R.string.show_rss),
        appCtx.getString(R.string.thread_count),
        appCtx.getString(R.string.local_book),
        appCtx.getString(R.string.backup_content_cookies),
        appCtx.getString(R.string.backup_content_source_variables),
    )

    //自动忽略keys
    private val ignorePrefKeys = arrayOf(
        PreferKey.defaultCover,
        PreferKey.defaultCoverDark,
        PreferKey.backupPath,
        PreferKey.defaultBookTreeUri,
        PreferKey.webDavDeviceName,
        PreferKey.webDavBookAutoRestore,
        PreferKey.autoBackup,
        PreferKey.jsSourceApiToken,
        PreferKey.launcherIcon,
        PreferKey.bitmapCacheSize,
        PreferKey.webServiceWakeLock,
        PreferKey.readAloudWakeLock,
        PreferKey.audioPlayWakeLock
    )

    //阅读配置
    private val readPrefKeys = arrayOf(
        PreferKey.readStyleSelect,
        PreferKey.comicStyleSelect,
        PreferKey.shareLayout,
        PreferKey.hideStatusBar,
        PreferKey.hideNavigationBar,
        PreferKey.autoReadSpeed,
        PreferKey.readerMenuConfig,
        PreferKey.showReadTitleChapterNameOnly,
        PreferKey.clickActionTL,
        PreferKey.clickActionTC,
        PreferKey.clickActionTR,
        PreferKey.clickActionML,
        PreferKey.clickActionMC,
        PreferKey.clickActionMR,
        PreferKey.clickActionBL,
        PreferKey.clickActionBC,
        PreferKey.clickActionBR
    )

    private val themePrefKeys = arrayOf(
        PreferKey.cPrimary,
        PreferKey.cAccent,
        PreferKey.cBackground,
        PreferKey.cBBackground,
        PreferKey.bgImage,
        PreferKey.bgImageBlurring,
        PreferKey.tNavBar,
        PreferKey.cNPrimary,
        PreferKey.cNAccent,
        PreferKey.cNBackground,
        PreferKey.cNBBackground,
        PreferKey.bgImageN,
        PreferKey.bgImageNBlurring,
        PreferKey.tNavBarN
    )

    private val coverPrefKeys = arrayOf(
        PreferKey.useDefaultCover,
        PreferKey.loadCoverOnlyWifi,
        PreferKey.coverShowName,
        PreferKey.coverShowAuthor,
        PreferKey.coverShowNameN,
        PreferKey.coverShowAuthorN,
        PreferKey.coverHorizontal,
        PreferKey.coverKeepPunctuation
    )

    fun keyIsNotIgnore(key: String): Boolean {
        return when {
            ignorePrefKeys.contains(key) -> false
            ignoreReadConfig && readPrefKeys.contains(key) -> false
            ignoreThemeConfig && themePrefKeys.contains(key) -> false
            ignoreCoverConfig && coverPrefKeys.contains(key) -> false
            PreferKey.themeMode == key && ignoreThemeMode -> false
            PreferKey.bookshelfLayout == key && ignoreBookshelfLayout -> false
            PreferKey.showRss == key && ignoreShowRss -> false
            PreferKey.threadCount == key && ignoreThreadCount -> false
            else -> true
        }
    }

    val ignoreReadConfig: Boolean
        get() = ignoreConfig[readConfigKey] == true
    private val ignoreThemeMode: Boolean
        get() = ignoreConfig[PreferKey.themeMode] == true
    private val ignoreThemeConfig: Boolean
        get() = ignoreConfig[themeConfigKey] == true
    private val ignoreCoverConfig: Boolean
        get() = ignoreConfig[coverConfigKey] == true
    private val ignoreBookshelfLayout: Boolean
        get() = ignoreConfig[PreferKey.bookshelfLayout] == true
    private val ignoreShowRss: Boolean
        get() = ignoreConfig[PreferKey.showRss] == true
    private val ignoreThreadCount: Boolean
        get() = ignoreConfig[PreferKey.threadCount] == true
    val ignoreLocalBook: Boolean
        get() = ignoreConfig[localBookKey] == true
    val ignoreCookies: Boolean
        get() = ignoreConfig[cookieIgnoreKey] == true

    val ignoreSourceVariables: Boolean
        get() = ignoreConfig[runtimeSourceCacheIgnoreKey] == true

    internal fun contentIsEnabled(key: String): Boolean {
        if (key == cookieContentKey) {
            return ignoreConfig[key] == false
        }
        if (key == runtimeSourceCacheContentKey) {
            return ignoreConfig[key] == false
        }
        return ignoreConfig[key] != true
    }

    fun saveIgnoreConfig() {
        val json = GSON.toJson(ignoreConfig)
        FileUtils.createFileIfNotExist(ignoreConfigPath).writeText(json)
    }

}
