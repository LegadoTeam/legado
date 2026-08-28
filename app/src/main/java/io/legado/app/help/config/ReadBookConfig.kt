package io.legado.app.help.config

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.annotation.Keep
import androidx.core.graphics.toColorInt
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.constant.PunctuationCompressMode
import io.legado.app.help.DefaultData
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFolderReplace
import io.legado.app.utils.externalCache
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.getFile
import io.legado.app.utils.getMeanColor
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.hexString
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.resizeAndRecycle
import splitties.init.appCtx
import java.io.File
import androidx.core.graphics.drawable.toDrawable

internal const val UNDERLINE_MODE_OFF = 0
internal const val UNDERLINE_MODE_SOLID = 1
internal const val UNDERLINE_MODE_DASHED = 2
internal const val UNDERLINE_MODE_DOTTED = 3
internal const val UNDERLINE_MODE_DOUBLE = 4
internal const val UNDERLINE_MODE_WAVY = 5
internal const val UNDERLINE_MODE_DOUBLE_DASHED = 6
internal const val DEFAULT_UNDERLINE_WIDTH = 1f
internal const val MIN_UNDERLINE_WIDTH = 0f
internal const val MAX_UNDERLINE_WIDTH = 10f
internal const val DEFAULT_UNDERLINE_DISTANCE = 4f
internal const val MIN_UNDERLINE_DISTANCE = 0f
internal const val MAX_UNDERLINE_DISTANCE = 30f
private const val UNDERLINE_CONFIG_VERSION = 1

internal fun underlineConfigReferencesChanged(
    previous: List<ReadBookConfig.Config>,
    current: List<ReadBookConfig.Config>,
): Boolean {
    if (previous.size != current.size) return true
    return previous.indices.any { previous[it] !== current[it] }
}

private fun migrateLegacyUnderline(
    config: ReadBookConfig.Config,
    raw: JsonObject,
): ReadBookConfig.Config {
    if (config.underlineConfigVersion == 0 && raw.get("underline")?.isJsonPrimitive == true) {
        val value = raw.getAsJsonPrimitive("underline")
        if (value.isBoolean) {
            config.underlineMode = if (value.asBoolean) UNDERLINE_MODE_SOLID else UNDERLINE_MODE_OFF
        }
    }
    return config
}

internal fun parseReadConfigArray(json: String): Result<List<ReadBookConfig.Config>> = runCatching {
    val configs = GSON.fromJsonArray<ReadBookConfig.Config>(json).getOrThrow()
    val raw = JsonParser.parseString(json).asJsonArray
    configs.mapIndexed { index, config ->
        raw.get(index).takeIf { it.isJsonObject }?.asJsonObject
            ?.let { migrateLegacyUnderline(config, it) }
            ?: config
    }
}

internal fun parseReadConfigObject(json: String): Result<ReadBookConfig.Config> = runCatching {
    val config = GSON.fromJsonObject<ReadBookConfig.Config>(json).getOrThrow()
    val raw = JsonParser.parseString(json).asJsonObject
    migrateLegacyUnderline(config, raw)
}

/**
 * Converts the old per-style underline field into one shared, backup-friendly value.
 * New fields live on every Config so existing readConfig.json archives remain readable.
 */
internal fun normalizeUnderlineConfigs(
    configs: List<ReadBookConfig.Config>,
    legacyIndex: Int = 0,
) {
    if (configs.isEmpty()) return
    val preferred = configs.getOrNull(legacyIndex)
    val source = preferred?.takeIf {
        it.underlineConfigVersion >= UNDERLINE_CONFIG_VERSION
    } ?: configs.firstOrNull { it.underlineConfigVersion >= UNDERLINE_CONFIG_VERSION }
        ?: preferred?.takeIf { it.underlineMode != UNDERLINE_MODE_OFF }
        ?: configs.firstOrNull { it.underlineMode != UNDERLINE_MODE_OFF }
        ?: configs.first()
    val hasNewConfig = source.underlineConfigVersion >= UNDERLINE_CONFIG_VERSION
    val mode = source.underlineMode.coerceIn(UNDERLINE_MODE_OFF, UNDERLINE_MODE_DOUBLE_DASHED)
    val color = if (hasNewConfig) source.underlineColor else 0
    val colorSet = hasNewConfig && source.underlineColorSet
    val width = if (hasNewConfig) {
        source.underlineWidth.coerceIn(MIN_UNDERLINE_WIDTH, MAX_UNDERLINE_WIDTH)
    } else {
        DEFAULT_UNDERLINE_WIDTH
    }
    val distance = if (hasNewConfig) {
        source.underlineDistance.coerceIn(MIN_UNDERLINE_DISTANCE, MAX_UNDERLINE_DISTANCE)
    } else {
        DEFAULT_UNDERLINE_DISTANCE
    }
    val bodyEnabled = if (hasNewConfig) source.underlineBodyEnabled else true
    val titleEnabled = if (hasNewConfig) source.underlineTitleEnabled else true
    configs.forEach {
        it.underlineMode = mode
        it.underlineColor = color
        it.underlineColorSet = colorSet
        it.underlineWidth = width
        it.underlineDistance = distance
        it.underlineBodyEnabled = bodyEnabled
        it.underlineTitleEnabled = titleEnabled
        it.underlineConfigVersion = UNDERLINE_CONFIG_VERSION
    }
}

/**
 * 阅读界面配置
 */
@Suppress("ConstPropertyName")
@Keep
object ReadBookConfig {
    const val configFileName = "readConfig.json"
    const val shareConfigFileName = "shareReadConfig.json"
    val configFilePath = FileUtils.getPath(appCtx.filesDir, configFileName)
    val shareConfigFilePath = FileUtils.getPath(appCtx.filesDir, shareConfigFileName)
    val configList: ArrayList<Config> = arrayListOf()
    lateinit var shareConfig: Config
    private var underlineConfigInitialized = false
    private var normalizedUnderlineConfigRefs: List<Config> = emptyList()
    private var normalizedShareConfig: Config? = null

    private fun underlineConfigs(): List<Config> {
        val configs = configList.toMutableList()
        if (::shareConfig.isInitialized && configs.none { it === shareConfig }) {
            configs += shareConfig
        }
        return configs
    }

    private fun underlineConfigSourcesChanged(): Boolean {
        if (underlineConfigReferencesChanged(normalizedUnderlineConfigRefs, configList)) {
            return true
        }
        val share = if (::shareConfig.isInitialized && configList.none { it === shareConfig }) {
            shareConfig
        } else {
            null
        }
        return share !== normalizedShareConfig
    }

    private fun normalizeUnderlineConfig() {
        val shareNeedsInit = ::shareConfig.isInitialized &&
            shareConfig.underlineConfigVersion < UNDERLINE_CONFIG_VERSION
        if (underlineConfigInitialized &&
            !configList.any { it.underlineConfigVersion < UNDERLINE_CONFIG_VERSION } &&
            !shareNeedsInit &&
            !underlineConfigSourcesChanged()
        ) {
            return
        }
        normalizeUnderlineConfigs(
            underlineConfigs(),
            appCtx.getPrefInt(PreferKey.readStyleSelect)
        )
        underlineConfigInitialized = true
        normalizedUnderlineConfigRefs = configList.toList()
        normalizedShareConfig = if (::shareConfig.isInitialized && configList.none { it === shareConfig }) {
            shareConfig
        } else {
            null
        }
    }

    private inline fun updateUnderlineConfig(update: Config.() -> Unit) {
        normalizeUnderlineConfig()
        underlineConfigs().forEach(update)
    }

    private fun underlineConfig(): Config? {
        normalizeUnderlineConfig()
        return configList.firstOrNull()
            ?: if (::shareConfig.isInitialized) shareConfig else null
    }

    var durConfig
        get() = getConfig(styleSelect)
        set(value) {
            configList[styleSelect] = value
            underlineConfigInitialized = false
            if (shareLayout) {
                shareConfig = value
            }
        }

    var isComic: Boolean = false
    var bg: Drawable? = null
    var bgMeanColor: Int = 0
    val textColor: Int get() = durConfig.curTextColor()
    val textAccentColor: Int get() = durConfig.curTextAccentColor()
    var isNineBgImg = false

    init {
        initConfigs()
        initShareConfig()
    }

    @Synchronized
    fun getConfig(index: Int): Config {
        if (configList.size < 5) {
            resetAll()
        }
        return configList.getOrNull(index) ?: configList[0]
    }

    fun initConfigs() {
        // A restored/imported config can contain the legacy per-style underline value.
        underlineConfigInitialized = false
        normalizedUnderlineConfigRefs = emptyList()
        normalizedShareConfig = null
        val configFile = File(configFilePath)
        var configs: List<Config>? = null
        if (configFile.exists()) {
            try {
                val json = configFile.readText()
                configs = parseReadConfigArray(json).getOrThrow()
            } catch (e: Exception) {
                AppLog.put("读取排版配置文件出错", e)
            }
        }
        (configs ?: DefaultData.readConfigs).let {
            configList.clear()
            configList.addAll(it)
        }
    }

    fun initShareConfig() {
        underlineConfigInitialized = false
        normalizedUnderlineConfigRefs = emptyList()
        normalizedShareConfig = null
        val configFile = File(shareConfigFilePath)
        var c: Config? = null
        if (configFile.exists()) {
            try {
                val json = configFile.readText()
                c = parseReadConfigObject(json).getOrThrow()
            } catch (e: Exception) {
                e.printOnDebug()
            }
        }
        shareConfig = c ?: configList.getOrNull(5) ?: Config()
        normalizeUnderlineConfig()
    }

    fun upBg(width: Int, height: Int) {
        val drawable = durConfig.curBgDrawable(width, height)
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            bgMeanColor = drawable.bitmap.getMeanColor()
        } else if (drawable is ColorDrawable) {
            bgMeanColor = drawable.color
        }
        val tmp = bg
        bg = drawable
        if (tmp is BitmapDrawable) { //太快执行，可能还正在被使用，延时防崩溃
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                tmp.bitmap?.recycle()
            }
        }
    }

    fun save() {
        Coroutine.async {
            saveNow()
        }
    }

    @Synchronized
    internal fun saveNow() {
        normalizeUnderlineConfig()
        GSON.toJson(configList).let {
            FileUtils.delete(configFilePath)
            FileUtils.createFileIfNotExist(configFilePath).writeText(it)
        }
        GSON.toJson(shareConfig).let {
            FileUtils.delete(shareConfigFilePath)
            FileUtils.createFileIfNotExist(shareConfigFilePath).writeText(it)
        }
    }

    fun getAllPicBgStr(): ArrayList<String> {
        val list = arrayListOf<String>()
        (configList + shareConfig).forEach {
            if (it.bgType == 2) {
                list.add(it.bgStr)
            }
            if (it.bgTypeNight == 2) {
                list.add(it.bgStrNight)
            }
            if (it.bgTypeEInk == 2) {
                list.add(it.bgStrEInk)
            }
        }
        return list
    }

    fun deleteDur(): Boolean {
        if (configList.size > 5) {
            val removeIndex = styleSelect
            configList.removeAt(removeIndex)
            if (removeIndex <= readStyleSelect) {
                readStyleSelect -= 1
            }
            if (removeIndex <= comicStyleSelect) {
                comicStyleSelect -= 1
            }
            return true
        }
        return false
    }

    fun clearBgAndCache() {
        val bgs = hashSetOf<String>()
        (configList + shareConfig).forEach { config ->
            repeat(3) {
                config.getBgPath(it)?.let { path ->
                    bgs.add(path)
                }
            }
        }
        appCtx.externalFiles.getFile("bg").listFiles()?.forEach {
            if (!bgs.contains(it.absolutePath)) {
                it.delete()
            }
        }
        FileUtils.delete(appCtx.externalCache.getFile("readConfig"))
        val configZipPath = FileUtils.getPath(appCtx.externalCache, "readConfig.zip")
        FileUtils.delete(configZipPath)
    }

    private fun resetAll() {
        DefaultData.readConfigs.let {
            configList.clear()
            configList.addAll(it)
            save()
        }
    }

    //配置写入读取
    var readBodyToLh = appCtx.getPrefBoolean(PreferKey.readBodyToLh, true)
    var autoReadSpeed = appCtx.getPrefInt(PreferKey.autoReadSpeed, 10)
        set(value) {
            field = value
            appCtx.putPrefInt(PreferKey.autoReadSpeed, value)
        }
    var styleSelect: Int
        get() = if (isComic) comicStyleSelect else readStyleSelect
        set(value) {
            if (isComic) {
                comicStyleSelect = value
            } else {
                readStyleSelect = value
            }
        }
    var readStyleSelect = appCtx.getPrefInt(PreferKey.readStyleSelect)
        set(value) {
            field = value
            if (appCtx.getPrefInt(PreferKey.readStyleSelect) != value) {
                appCtx.putPrefInt(PreferKey.readStyleSelect, value)
            }
        }
    var comicStyleSelect = appCtx.getPrefInt(PreferKey.comicStyleSelect, readStyleSelect)
        set(value) {
            field = value
            if (appCtx.getPrefInt(PreferKey.comicStyleSelect) != value) {
                appCtx.putPrefInt(PreferKey.comicStyleSelect, value)
            }
        }
    var shareLayout = appCtx.getPrefBoolean(PreferKey.shareLayout)
        set(value) {
            field = value
            if (appCtx.getPrefBoolean(PreferKey.shareLayout) != value) {
                appCtx.putPrefBoolean(PreferKey.shareLayout, value)
            }
        }

    /**
     * 两端对齐
     */
    val textFullJustify get() = appCtx.getPrefBoolean(PreferKey.textFullJustify, true)

    /**
     * 底部对齐
     */
    val textBottomJustify get() = appCtx.getPrefBoolean(PreferKey.textBottomJustify, true)

    /**
     * 段首标点悬挂
     */
    val hangingPunctuation get() = appCtx.getPrefBoolean(PreferKey.hangingPunctuation, false)

    /**
     * 标点挤压
     */
    val punctuationCompress
        get() = PunctuationCompressMode.fromKey(
            appCtx.getPrefString(PreferKey.punctuationCompress)
        )

    var hideStatusBar = appCtx.getPrefBoolean(PreferKey.hideStatusBar)
    var hideNavigationBar = appCtx.getPrefBoolean(PreferKey.hideNavigationBar)
    var useZhLayout = appCtx.getPrefBoolean(PreferKey.useZhLayout)

    val config get() = if (shareLayout) shareConfig else durConfig

    var bgAlpha: Int
        get() = config.bgAlpha
        set(value) {
            config.bgAlpha = value
        }

    var pageAnim: Int
        get() = config.curPageAnim()
        set(@PageAnim.Anim value) {
            config.setCurPageAnim(value)
        }

    var textFont: String
        get() = config.textFont
        set(value) {
            config.textFont = value
        }

    var titleFont: String
        get() = config.titleFont
        set(value) {
            config.titleFont = value
        }

    val resolvedTitleFont: String
        get() = config.titleFont.ifEmpty { config.textFont }

    var textBold: Int
        get() = config.textBold
        set(value) {
            config.textBold = value
        }

    var textSize: Int
        get() = config.textSize
        set(value) {
            config.textSize = value
        }

    var letterSpacing: Float
        get() = config.letterSpacing
        set(value) {
            config.letterSpacing = value
        }

    var lineSpacingExtra: Int
        get() = config.lineSpacingExtra
        set(value) {
            config.lineSpacingExtra = value
        }

    var titleLineSpacingExtra: Int
        get() = config.titleLineSpacingExtra
        set(value) {
            config.titleLineSpacingExtra = value
        }

    var paragraphSpacing: Int
        get() = config.paragraphSpacing
        set(value) {
            config.paragraphSpacing = value
        }

    /**
     * 标题位置 0:居左 1:居中 2:隐藏 3:居右
     */
    var titleMode: Int
        get() = config.titleMode
        set(value) {
            config.titleMode = value
        }
    var titleSize: Int
        get() = config.titleSize
        set(value) {
            config.titleSize = value
        }

    /**
     * 是否标题居中
     */
    val isMiddleTitle get() = titleMode == 1

    val isRightTitle get() = titleMode == 3

    var titleColor: Int
        get() = config.titleColor
        set(value) {
            config.titleColor = value
        }

    val titleTextColor: Int
        get() = titleColor.takeIf { it != 0 } ?: textColor

    var splitChapterTitle: Boolean
        get() = config.splitChapterTitle
        set(value) {
            config.splitChapterTitle = value
        }

    var titleNumberSize: Int
        get() = config.titleNumberSize
        set(value) {
            config.titleNumberSize = value
        }

    var titleNumberColor: Int
        get() = config.titleNumberColor
        set(value) {
            config.titleNumberColor = value
        }

    val titleNumberTextColor: Int
        get() = titleNumberColor.takeIf { it != 0 } ?: textColor

    var titleNumberSpacing: Int
        get() = config.titleNumberSpacing
        set(value) {
            config.titleNumberSpacing = value
        }

    var titleTopSpacing: Int
        get() = config.titleTopSpacing
        set(value) {
            config.titleTopSpacing = value
        }

    var titleBottomSpacing: Int
        get() = config.titleBottomSpacing
        set(value) {
            config.titleBottomSpacing = value
        }

    var paragraphIndent: String
        get() = config.paragraphIndent
        set(value) {
            config.paragraphIndent = value
        }

    var underlineMode: Int
        get() = underlineConfig()?.underlineMode ?: 0
        set(value) {
            updateUnderlineConfig { underlineMode = value.coerceIn(UNDERLINE_MODE_OFF, UNDERLINE_MODE_DOUBLE_DASHED) }
        }

    var underlineColor: Int
        get() = underlineConfig()?.underlineColor ?: 0
        set(value) {
            updateUnderlineConfig {
                underlineColor = value
                underlineColorSet = true
            }
        }

    val underlineColorSet: Boolean
        get() = underlineConfig()?.underlineColorSet == true

    var underlineWidth: Float
        get() = underlineConfig()?.underlineWidth ?: DEFAULT_UNDERLINE_WIDTH
        set(value) {
            updateUnderlineConfig {
                underlineWidth = value.coerceIn(MIN_UNDERLINE_WIDTH, MAX_UNDERLINE_WIDTH)
            }
        }

    var underlineDistance: Float
        get() = underlineConfig()?.underlineDistance ?: DEFAULT_UNDERLINE_DISTANCE
        set(value) {
            updateUnderlineConfig {
                underlineDistance = value.coerceIn(MIN_UNDERLINE_DISTANCE, MAX_UNDERLINE_DISTANCE)
            }
        }

    var underlineBodyEnabled: Boolean
        get() = underlineConfig()?.underlineBodyEnabled ?: true
        set(value) {
            updateUnderlineConfig { underlineBodyEnabled = value }
        }

    var underlineTitleEnabled: Boolean
        get() = underlineConfig()?.underlineTitleEnabled ?: true
        set(value) {
            updateUnderlineConfig { underlineTitleEnabled = value }
        }

    var reviewIconColor: Int
        get() = config.reviewIconColor
        set(value) {
            config.reviewIconColor = value
        }

    var reviewIconSvg: String
        get() = config.reviewIconSvg
        set(value) {
            config.reviewIconSvg = value
        }

    var reviewIconScale: Int
        get() = config.reviewIconScale
        set(value) {
            config.reviewIconScale = value.coerceIn(50, 200)
        }

    var paddingBottom: Int
        get() = config.paddingBottom
        set(value) {
            config.paddingBottom = value
        }

    var paddingLeft: Int
        get() = config.paddingLeft
        set(value) {
            config.paddingLeft = value
        }

    var paddingRight: Int
        get() = config.paddingRight
        set(value) {
            config.paddingRight = value
        }

    var paddingTop: Int
        get() = config.paddingTop
        set(value) {
            config.paddingTop = value
        }

    var headerPaddingBottom: Int
        get() = config.headerPaddingBottom
        set(value) {
            config.headerPaddingBottom = value
        }

    var headerPaddingLeft: Int
        get() = config.headerPaddingLeft
        set(value) {
            config.headerPaddingLeft = value
        }

    var headerPaddingRight: Int
        get() = config.headerPaddingRight
        set(value) {
            config.headerPaddingRight = value
        }

    var headerPaddingTop: Int
        get() = config.headerPaddingTop
        set(value) {
            config.headerPaddingTop = value
        }

    var footerPaddingBottom: Int
        get() = config.footerPaddingBottom
        set(value) {
            config.footerPaddingBottom = value
        }

    var footerPaddingLeft: Int
        get() = config.footerPaddingLeft
        set(value) {
            config.footerPaddingLeft = value
        }

    var footerPaddingRight: Int
        get() = config.footerPaddingRight
        set(value) {
            config.footerPaddingRight = value
        }

    var footerPaddingTop: Int
        get() = config.footerPaddingTop
        set(value) {
            config.footerPaddingTop = value
        }

    var showHeaderLine: Boolean
        get() = config.showHeaderLine
        set(value) {
            config.showHeaderLine = value
        }

    var showFooterLine: Boolean
        get() = config.showFooterLine
        set(value) {
            config.showFooterLine = value
        }

    fun getExportConfig(): Config {
        normalizeUnderlineConfig()
        val exportConfig = durConfig.copy()
        if (shareLayout) {
            exportConfig.textFont = shareConfig.textFont
            exportConfig.titleFont = shareConfig.titleFont
            exportConfig.textBold = shareConfig.textBold
            exportConfig.textSize = shareConfig.textSize
            exportConfig.letterSpacing = shareConfig.letterSpacing
            exportConfig.lineSpacingExtra = shareConfig.lineSpacingExtra
            exportConfig.titleLineSpacingExtra = shareConfig.titleLineSpacingExtra
            exportConfig.paragraphSpacing = shareConfig.paragraphSpacing
            exportConfig.titleMode = shareConfig.titleMode
            exportConfig.titleSize = shareConfig.titleSize
            exportConfig.titleColor = shareConfig.titleColor
            exportConfig.splitChapterTitle = shareConfig.splitChapterTitle
            exportConfig.titleNumberSize = shareConfig.titleNumberSize
            exportConfig.titleNumberColor = shareConfig.titleNumberColor
            exportConfig.titleNumberSpacing = shareConfig.titleNumberSpacing
            exportConfig.titleTopSpacing = shareConfig.titleTopSpacing
            exportConfig.titleBottomSpacing = shareConfig.titleBottomSpacing
            exportConfig.underlineMode = shareConfig.underlineMode
            exportConfig.underlineColor = shareConfig.underlineColor
            exportConfig.underlineColorSet = shareConfig.underlineColorSet
            exportConfig.underlineWidth = shareConfig.underlineWidth
            exportConfig.underlineDistance = shareConfig.underlineDistance
            exportConfig.underlineBodyEnabled = shareConfig.underlineBodyEnabled
            exportConfig.underlineTitleEnabled = shareConfig.underlineTitleEnabled
            exportConfig.underlineConfigVersion = shareConfig.underlineConfigVersion
            exportConfig.paddingBottom = shareConfig.paddingBottom
            exportConfig.paddingLeft = shareConfig.paddingLeft
            exportConfig.paddingRight = shareConfig.paddingRight
            exportConfig.paddingTop = shareConfig.paddingTop
            exportConfig.headerPaddingBottom = shareConfig.headerPaddingBottom
            exportConfig.headerPaddingLeft = shareConfig.headerPaddingLeft
            exportConfig.headerPaddingRight = shareConfig.headerPaddingRight
            exportConfig.headerPaddingTop = shareConfig.headerPaddingTop
            exportConfig.footerPaddingBottom = shareConfig.footerPaddingBottom
            exportConfig.footerPaddingLeft = shareConfig.footerPaddingLeft
            exportConfig.footerPaddingRight = shareConfig.footerPaddingRight
            exportConfig.footerPaddingTop = shareConfig.footerPaddingTop
            exportConfig.showHeaderLine = shareConfig.showHeaderLine
            exportConfig.showFooterLine = shareConfig.showFooterLine
            exportConfig.tipHeaderLeft = shareConfig.tipHeaderLeft
            exportConfig.tipHeaderMiddle = shareConfig.tipHeaderMiddle
            exportConfig.tipHeaderRight = shareConfig.tipHeaderRight
            exportConfig.tipFooterLeft = shareConfig.tipFooterLeft
            exportConfig.tipFooterMiddle = shareConfig.tipFooterMiddle
            exportConfig.tipFooterRight = shareConfig.tipFooterRight
            exportConfig.tipHeaderLeftTemplate = shareConfig.tipHeaderLeftTemplate
            exportConfig.tipHeaderMiddleTemplate = shareConfig.tipHeaderMiddleTemplate
            exportConfig.tipHeaderRightTemplate = shareConfig.tipHeaderRightTemplate
            exportConfig.tipFooterLeftTemplate = shareConfig.tipFooterLeftTemplate
            exportConfig.tipFooterMiddleTemplate = shareConfig.tipFooterMiddleTemplate
            exportConfig.tipFooterRightTemplate = shareConfig.tipFooterRightTemplate
            exportConfig.tipTextSize = shareConfig.tipTextSize
            exportConfig.tipColor = shareConfig.tipColor
            exportConfig.headerMode = shareConfig.headerMode
            exportConfig.footerMode = shareConfig.footerMode
            exportConfig.reviewIconColor = shareConfig.reviewIconColor
            exportConfig.reviewIconSvg = shareConfig.reviewIconSvg
            exportConfig.reviewIconScale = shareConfig.reviewIconScale
        }
        return exportConfig
    }

    fun import(byteArray: ByteArray): Config {
        val configZipPath = FileUtils.getPath(appCtx.externalCache, "readConfig.zip")
        FileUtils.delete(configZipPath)
        val zipFile = FileUtils.createFileIfNotExist(configZipPath)
        zipFile.writeBytes(byteArray)
        val configDir = appCtx.externalCache.getFile("readConfig")
        configDir.createFolderReplace()
        ZipUtils.unZipToPath(zipFile, configDir)
        val configFile = configDir.getFile(configFileName)
        val config: Config = parseReadConfigObject(configFile.readText()).getOrThrow()
        fun importFont(fontName: String): String {
            if (fontName.isEmpty()) return ""
            val fontPath =
                FileUtils.getPath(appCtx.externalFiles, "font", fontName)
            val fontFile = configDir.getFile(fontName)
            return if (fontFile.exists()) {
                if (!FileUtils.exist(fontPath)) {
                    fontFile.copyTo(File(fontPath))
                }
                fontPath
            } else {
                ""
            }
        }
        config.textFont = importFont(config.textFont)
        config.titleFont = importFont(config.titleFont)
        if (config.bgType == 2) {
            val bgName = FileUtils.getName(config.bgStr)
            config.bgStr = bgName
            val bgPath = FileUtils.getPath(appCtx.externalFiles, "bg", bgName)
            if (!FileUtils.exist(bgPath)) {
                val bgFile = configDir.getFile(bgName)
                if (bgFile.exists()) {
                    bgFile.copyTo(File(bgPath))
                }
            }
            config.bgStr = bgPath
        } else if (config.bgType == 0) {
            config.bgStr.toColorInt()
        }
        if (config.bgTypeNight == 2) {
            val bgName = FileUtils.getName(config.bgStrNight)
            config.bgStrNight = bgName
            val bgPath = FileUtils.getPath(appCtx.externalFiles, "bg", bgName)
            if (!FileUtils.exist(bgPath)) {
                val bgFile = configDir.getFile(bgName)
                if (bgFile.exists()) {
                    bgFile.copyTo(File(bgPath))
                }
            }
            config.bgStrNight = bgPath
        } else if (config.bgTypeNight == 0) {
            config.bgStrNight.toColorInt()
        }
        if (config.bgTypeEInk == 2) {
            val bgName = FileUtils.getName(config.bgStrEInk)
            config.bgStrEInk = bgName
            val bgPath = FileUtils.getPath(appCtx.externalFiles, "bg", bgName)
            if (!FileUtils.exist(bgPath)) {
                val bgFile = configDir.getFile(bgName)
                if (bgFile.exists()) {
                    bgFile.copyTo(File(bgPath))
                }
            }
            config.bgStrEInk = bgPath
        } else if (config.bgTypeEInk == 0) {
            config.bgStrEInk.toColorInt()
        }
        config.curTextColor()
        config.curTextAccentColor()
        return config
    }

    @Keep
    data class ReviewIconSvgTemplate(
        val name: String = "",
        val svg: String = "",
    )

    @Keep
    data class Config(
        var name: String = "",
        var bgStr: String = "#EEEEEE",//白天背景
        var bgStrNight: String = "#000000",//夜间背景
        var bgStrEInk: String = "#FFFFFF",//EInk背景
        var bgAlpha: Int = 100,//背景透明度
        var bgType: Int = 0,//白天背景类型 0:颜色, 1:assets图片, 2其它图片
        var bgTypeNight: Int = 0,//夜间背景类型
        var bgTypeEInk: Int = 0,//EInk背景类型
        private var darkStatusIcon: Boolean = true,//白天是否暗色状态栏
        private var darkStatusIconNight: Boolean = false,//晚上是否暗色状态栏
        private var darkStatusIconEInk: Boolean = true,
        private var textColor: String = "#3E3D3B",//白天文字颜色
        private var textColorNight: String = "#ADADAD",//夜间文字颜色
        private var textColorEInk: String = "#000000",
        private var textAccentColor: String = "#E53935",//白天强调文字颜色
        private var textAccentColorNight: String = "#FE4D55",//夜间强调文字颜色
        private var textAccentColorEInk: String = "#000000",
        private var pageAnim: Int = 0,//翻页动画
        private var pageAnimEInk: Int = 4,
        var textFont: String = "",//字体
        var titleFont: String = "",//标题字体, 空值跟随正文字体
        var textBold: Int = 0,//是否粗体字 0:正常, 1:粗体, 2:细体
        var textSize: Int = 20,//文字大小
        var letterSpacing: Float = 0.1f,//字间距
        var lineSpacingExtra: Int = 12,//行间距
        var titleLineSpacingExtra: Int = 0,//章节名行距相对字体行高的偏移，单位0.1
        var paragraphSpacing: Int = 2,//段距
        var titleMode: Int = 0,//标题位置 0:居左 1:居中 2:隐藏 3:居右
        var titleSize: Int = 0,
        var titleColor: Int = 0,
        var splitChapterTitle: Boolean = false,
        var titleNumberSize: Int = 0,
        var titleNumberColor: Int = 0,
        var titleNumberSpacing: Int = 0,
        var titleTopSpacing: Int = 0,
        var titleBottomSpacing: Int = 0,
        var paragraphIndent: String = "　　",//段落缩进
        var underlineMode: Int = 0, //下划线（旧字段，保留兼容）
        var underlineColor: Int = 0,
        var underlineColorSet: Boolean = false,
        var underlineWidth: Float = DEFAULT_UNDERLINE_WIDTH,
        var underlineDistance: Float = DEFAULT_UNDERLINE_DISTANCE,
        var underlineBodyEnabled: Boolean = true,
        var underlineTitleEnabled: Boolean = true,
        var underlineConfigVersion: Int = 0,
        var reviewIconColor: Int = 0, //段评内置图标颜色(0=跟随主题)
        var reviewIconSvg: String = "",
        var reviewIconSvgTemplates: List<ReviewIconSvgTemplate> = emptyList(),
        var reviewIconScale: Int = 100,
        var paddingBottom: Int = 6,
        var paddingLeft: Int = 16,
        var paddingRight: Int = 16,
        var paddingTop: Int = 6,
        var headerPaddingBottom: Int = 0,
        var headerPaddingLeft: Int = 16,
        var headerPaddingRight: Int = 16,
        var headerPaddingTop: Int = 0,
        var footerPaddingBottom: Int = 6,
        var footerPaddingLeft: Int = 16,
        var footerPaddingRight: Int = 16,
        var footerPaddingTop: Int = 6,
        var showHeaderLine: Boolean = false,
        var showFooterLine: Boolean = true,
        var tipHeaderLeft: Int = ReadTipConfig.time,
        var tipHeaderMiddle: Int = ReadTipConfig.none,
        var tipHeaderRight: Int = ReadTipConfig.battery,
        var tipFooterLeft: Int = ReadTipConfig.chapterTitle,
        var tipFooterMiddle: Int = ReadTipConfig.none,
        var tipFooterRight: Int = ReadTipConfig.pageAndTotal,
        var tipHeaderLeftTemplate: String? = null,
        var tipHeaderMiddleTemplate: String? = null,
        var tipHeaderRightTemplate: String? = null,
        var tipFooterLeftTemplate: String? = null,
        var tipFooterMiddleTemplate: String? = null,
        var tipFooterRightTemplate: String? = null,
        var tipTextSize: Int = 12,
        var tipColor: Int = 0,
        var tipDividerColor: Int = -1,
        var headerMode: Int = 0,
        var footerMode: Int = 0
    ) {

        @Transient
        private var textColorIntEInk = -1

        @Transient
        private var textColorIntNight = -1

        @Transient
        private var textColorInt = -1

        @Transient
        private var initColorInt = false

        private fun initColorInt() {
            textColorIntEInk = textColorEInk.toColorInt()
            textColorIntNight = textColorNight.toColorInt()
            textColorInt = textColor.toColorInt()
            initColorInt = true
        }

        @Transient
        private var textAccentColorIntEInk = -1

        @Transient
        private var textAccentColorIntNight = -1

        @Transient
        private var textAccentColorInt = -1

        @Transient
        private var initAccentColorInt = false

        private fun initAccentColorInt() {
            textAccentColorIntEInk = textAccentColorEInk.toColorInt()
            textAccentColorIntNight = textAccentColorNight.toColorInt()
            textAccentColorInt = textAccentColor.toColorInt()
            initAccentColorInt = true
        }

        fun setCurTextColor(color: Int) {
            when {
                AppConfig.isEInkMode -> {
                    textColorEInk = "#${color.hexString}"
                    textColorIntEInk = color
                }

                AppConfig.isNightTheme -> {
                    textColorNight = "#${color.hexString}"
                    textColorIntNight = color
                }

                else -> {
                    textColor = "#${color.hexString}"
                    textColorInt = color
                }
            }
        }

        fun curTextColor(): Int {
            if (!initColorInt) {
                initColorInt()
            }
            return when {
                AppConfig.isEInkMode -> textColorIntEInk
                AppConfig.isNightTheme -> textColorIntNight
                else -> textColorInt
            }
        }

        fun setCurTextAccentColor(color: Int) {
            when {
                AppConfig.isEInkMode -> {
                    textAccentColorEInk = "#${color.hexString}"
                    textAccentColorIntEInk = color
                }

                AppConfig.isNightTheme -> {
                    textAccentColorNight = "#${color.hexString}"
                    textAccentColorIntNight = color
                }

                else -> {
                    textAccentColor = "#${color.hexString}"
                    textAccentColorInt = color
                }
            }
        }

        fun curTextAccentColor(): Int {
            if (!initAccentColorInt) {
                initAccentColorInt()
            }
            return when {
                AppConfig.isEInkMode -> textAccentColorIntEInk
                AppConfig.isNightTheme -> textAccentColorIntNight
                else -> textAccentColorInt
            }
        }

        fun setCurStatusIconDark(isDark: Boolean) {
            when {
                AppConfig.isEInkMode -> darkStatusIconEInk = isDark
                AppConfig.isNightTheme -> darkStatusIconNight = isDark
                else -> darkStatusIcon = isDark
            }
        }

        fun curStatusIconDark(): Boolean {
            return when {
                AppConfig.isEInkMode -> darkStatusIconEInk
                AppConfig.isNightTheme -> darkStatusIconNight
                else -> darkStatusIcon
            }
        }

        fun setCurPageAnim(@PageAnim.Anim anim: Int) {
            when {
                AppConfig.isEInkMode -> pageAnimEInk = anim
                else -> pageAnim = anim
            }
        }

        fun curPageAnim(): Int {
            return when {
                AppConfig.isEInkMode -> pageAnimEInk
                else -> pageAnim
            }
        }

        fun setCurBg(bgType: Int, bg: String) {
            when {
                AppConfig.isEInkMode -> {
                    bgTypeEInk = bgType
                    bgStrEInk = bg
                }

                AppConfig.isNightTheme -> {
                    bgTypeNight = bgType
                    bgStrNight = bg
                }

                else -> {
                    this.bgType = bgType
                    bgStr = bg
                }
            }
        }

        fun curBgStr(): String {
            return when {
                AppConfig.isEInkMode -> bgStrEInk
                AppConfig.isNightTheme -> bgStrNight
                else -> bgStr
            }
        }

        fun curBgType(): Int {
            return when {
                AppConfig.isEInkMode -> bgTypeEInk
                AppConfig.isNightTheme -> bgTypeNight
                else -> bgType
            }
        }

        fun curBgDrawable(width: Int, height: Int): Drawable {
            val curBgStr = curBgStr()
            isNineBgImg = curBgStr.endsWith(".9.png")
            if (width == 0 || height == 0) {
                return appCtx.getCompatColor(R.color.background).toDrawable()
            }
            var bgDrawable: Drawable? = null
            val resources = appCtx.resources
            try {
                bgDrawable = when (curBgType()) {
                    0 -> curBgStr.toColorInt().toDrawable()
                    1 -> {
                        val path = "bg" + File.separator + curBgStr
                        val bitmap = BitmapUtils.decodeAssetsBitmap(appCtx, path, width, height)
                        bitmap?.resizeAndRecycle(width, height)?.toDrawable(resources)
                    }

                    else -> {
                        val path = curBgStr.let {
                            if (it.contains(File.separator)) it
                            else FileUtils.getPath(appCtx.externalFiles, "bg", it)
                        }
                        if (isNineBgImg) {
                            BitmapUtils.decodeNinePatchDrawable(path)
                        } else {
                            val bitmap = BitmapUtils.decodeBitmap(path, width, height)
                            bitmap?.resizeAndRecycle(width, height)?.toDrawable(resources)
                        }
                    }
                }
            } catch (e: OutOfMemoryError) {
                e.printOnDebug()
            } catch (e: Exception) {
                e.printOnDebug()
            }
            return bgDrawable ?: appCtx.getCompatColor(R.color.background).toDrawable()
        }

        fun getBgPath(bgIndex: Int): String? {
            val bgType = when (bgIndex) {
                0 -> bgType
                1 -> bgTypeNight
                2 -> bgTypeEInk
                else -> error("unknown bgIndex: $bgIndex")
            }
            if (bgType != 2) {
                return null
            }
            val bgStr = when (bgIndex) {
                0 -> bgStr
                1 -> bgStrNight
                2 -> bgStrEInk
                else -> error("unknown bgIndex: $bgIndex")
            }
            val path = if (bgStr.contains(File.separator)) {
                bgStr
            } else {
                FileUtils.getPath(appCtx.externalFiles, "bg", bgStr)
            }
            return path
        }

        fun putReviewIconSvgTemplate(name: String, svg: String) {
            val normalizedName = name.trim()
            val normalizedSvg = svg.trim()
            if (normalizedName.isEmpty() || normalizedSvg.isEmpty()) return
            val templates = reviewIconSvgTemplates.associateByTo(
                linkedMapOf<String, ReviewIconSvgTemplate>()
            ) { it.svg.trim() }
            templates[normalizedSvg] = ReviewIconSvgTemplate(normalizedName, normalizedSvg)
            reviewIconSvgTemplates = templates.values.toList()
        }

        fun removeReviewIconSvgTemplate(svg: String) {
            val normalizedSvg = svg.trim()
            reviewIconSvgTemplates = reviewIconSvgTemplates.filterNot {
                it.svg.trim() == normalizedSvg
            }
        }

        fun toMap() = mapOf(
            "name" to name,
            "bgStr" to bgStr,
            "bgStrNight" to bgStrNight,
            "bgStrEInk" to bgStrEInk,
            "bgAlpha" to bgAlpha,
            "bgType" to bgType,
            "bgTypeNight" to bgTypeNight,
            "bgTypeEInk" to bgTypeEInk,
            "darkStatusIcon" to darkStatusIcon,
            "darkStatusIconNight" to darkStatusIconNight,
            "darkStatusIconEInk" to darkStatusIconEInk,
            "textColor" to textColor,
            "textColorNight" to textColorNight,
            "textColorEInk" to textColorEInk,
            "textColorInt" to textColorInt,
            "textColorIntNight" to textColorIntNight,
            "textColorIntEInk" to textColorIntEInk,
            "textAccentColor" to textAccentColor,
            "textAccentColorNight" to textAccentColorNight,
            "textAccentColorEInk" to textAccentColorEInk,
            "textAccentColorInt" to textAccentColorInt,
            "textAccentColorIntNight" to textAccentColorIntNight,
            "textAccentColorIntEInk" to textAccentColorIntEInk,
            "pageAnim" to pageAnim,
            "pageAnimEInk" to pageAnimEInk,
            "textFont" to textFont,
            "titleFont" to titleFont,
            "textBold" to textBold,
            "textSize" to textSize,
            "letterSpacing" to letterSpacing,
            "lineSpacingExtra" to lineSpacingExtra,
            "titleLineSpacingExtra" to titleLineSpacingExtra,
            "paragraphSpacing" to paragraphSpacing,
            "titleMode" to titleMode,
            "titleSize" to titleSize,
            "titleColor" to titleColor,
            "splitChapterTitle" to splitChapterTitle,
            "titleNumberSize" to titleNumberSize,
            "titleNumberColor" to titleNumberColor,
            "titleNumberSpacing" to titleNumberSpacing,
            "titleTopSpacing" to titleTopSpacing,
            "titleBottomSpacing" to titleBottomSpacing,
            "paragraphIndent" to paragraphIndent,
            "underlineMode" to underlineMode,
            "underlineColor" to underlineColor,
            "underlineColorSet" to underlineColorSet,
            "underlineWidth" to underlineWidth,
            "underlineDistance" to underlineDistance,
            "underlineBodyEnabled" to underlineBodyEnabled,
            "underlineTitleEnabled" to underlineTitleEnabled,
            "underlineConfigVersion" to underlineConfigVersion,
            "reviewIconColor" to reviewIconColor,
            "reviewIconSvg" to reviewIconSvg,
            "reviewIconSvgTemplates" to reviewIconSvgTemplates,
            "reviewIconScale" to reviewIconScale,
            "paddingBottom" to paddingBottom,
            "paddingLeft" to paddingLeft,
            "paddingRight" to paddingRight,
            "paddingTop" to paddingTop,
            "headerPaddingBottom" to headerPaddingBottom,
            "headerPaddingLeft" to headerPaddingLeft,
            "headerPaddingRight" to headerPaddingRight,
            "headerPaddingTop" to headerPaddingTop,
            "footerPaddingBottom" to footerPaddingBottom,
            "footerPaddingLeft" to footerPaddingLeft,
            "footerPaddingRight" to footerPaddingRight,
            "footerPaddingTop" to footerPaddingTop,
            "showHeaderLine" to showHeaderLine,
            "showFooterLine" to showFooterLine,
            "tipHeaderLeft" to tipHeaderLeft,
            "tipHeaderMiddle" to tipHeaderMiddle,
            "tipHeaderRight" to tipHeaderRight,
            "tipFooterLeft" to tipFooterLeft,
            "tipFooterMiddle" to tipFooterMiddle,
            "tipFooterRight" to tipFooterRight,
            "tipTextSize" to tipTextSize,
            "tipColor" to tipColor,
            "tipDividerColor" to tipDividerColor,
            "headerMode" to headerMode,
            "footerMode" to footerMode
        )

    }

}
