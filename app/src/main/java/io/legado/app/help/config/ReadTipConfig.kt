package io.legado.app.help.config

import android.content.Context
import io.legado.app.R
import splitties.init.appCtx

@Suppress("ConstPropertyName")
object ReadTipConfig {

    const val none = 0
    const val chapterTitle = 1
    const val time = 2
    const val battery = 3
    const val batteryPercentage = 10
    const val page = 4
    const val totalProgress = 5
    const val pageAndTotal = 6
    const val bookName = 7
    const val timeBattery = 8
    const val timeBatteryPercentage = 9
    const val totalProgress1 = 11

    val tipValues = arrayOf(
        none, bookName, chapterTitle, time, battery, batteryPercentage, page,
        totalProgress, totalProgress1, pageAndTotal, timeBattery, timeBatteryPercentage
    )
    val tipNames get() = appCtx.resources.getStringArray(R.array.read_tip).toList()

    val tipColorNames get() = appCtx.resources.getStringArray(R.array.tip_color).toList()
    val tipDividerColorNames
        get() = appCtx.resources.getStringArray(R.array.tip_divider_color).toList()

    var tipHeaderLeft: Int
        get() = ReadBookConfig.config.tipHeaderLeft
        set(value) {
            ReadBookConfig.config.tipHeaderLeft = value
        }

    var tipHeaderMiddle: Int
        get() = ReadBookConfig.config.tipHeaderMiddle
        set(value) {
            ReadBookConfig.config.tipHeaderMiddle = value
        }

    var tipHeaderRight: Int
        get() = ReadBookConfig.config.tipHeaderRight
        set(value) {
            ReadBookConfig.config.tipHeaderRight = value
        }

    var tipFooterLeft: Int
        get() = ReadBookConfig.config.tipFooterLeft
        set(value) {
            ReadBookConfig.config.tipFooterLeft = value
        }

    var tipFooterMiddle: Int
        get() = ReadBookConfig.config.tipFooterMiddle
        set(value) {
            ReadBookConfig.config.tipFooterMiddle = value
        }

    var tipFooterRight: Int
        get() = ReadBookConfig.config.tipFooterRight
        set(value) {
            ReadBookConfig.config.tipFooterRight = value
        }

    var tipHeaderLeftTemplate: String?
        get() = ReadBookConfig.config.tipHeaderLeftTemplate
        set(value) {
            ReadBookConfig.config.tipHeaderLeftTemplate = value
        }

    var tipHeaderMiddleTemplate: String?
        get() = ReadBookConfig.config.tipHeaderMiddleTemplate
        set(value) {
            ReadBookConfig.config.tipHeaderMiddleTemplate = value
        }

    var tipHeaderRightTemplate: String?
        get() = ReadBookConfig.config.tipHeaderRightTemplate
        set(value) {
            ReadBookConfig.config.tipHeaderRightTemplate = value
        }

    var tipFooterLeftTemplate: String?
        get() = ReadBookConfig.config.tipFooterLeftTemplate
        set(value) {
            ReadBookConfig.config.tipFooterLeftTemplate = value
        }

    var tipFooterMiddleTemplate: String?
        get() = ReadBookConfig.config.tipFooterMiddleTemplate
        set(value) {
            ReadBookConfig.config.tipFooterMiddleTemplate = value
        }

    var tipFooterRightTemplate: String?
        get() = ReadBookConfig.config.tipFooterRightTemplate
        set(value) {
            ReadBookConfig.config.tipFooterRightTemplate = value
        }

    fun effectiveTemplate(template: String?, legacyTip: Int): String =
        template ?: ReaderInfoTemplate.fromLegacy(legacyTip)

    var headerMode: Int
        get() = ReadBookConfig.config.headerMode
        set(value) {
            ReadBookConfig.config.headerMode = value
        }

    var footerMode: Int
        get() = ReadBookConfig.config.footerMode
        set(value) {
            ReadBookConfig.config.footerMode = value
        }

    var tipColor: Int
        get() = ReadBookConfig.config.tipColor
        set(value) {
            ReadBookConfig.config.tipColor = value
        }

    var tipDividerColor: Int
        get() = ReadBookConfig.config.tipDividerColor
        set(value) {
            ReadBookConfig.config.tipDividerColor = value
        }

    fun getHeaderModes(context: Context): LinkedHashMap<Int, String> {
        return linkedMapOf(
            Pair(0, context.getString(R.string.hide_when_status_bar_show)),
            Pair(1, context.getString(R.string.show)),
            Pair(2, context.getString(R.string.hide))
        )
    }

    fun getFooterModes(context: Context): LinkedHashMap<Int, String> {
        return linkedMapOf(
            Pair(0, context.getString(R.string.show)),
            Pair(1, context.getString(R.string.hide))
        )
    }
}
