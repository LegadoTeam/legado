package io.legado.app.help.config

data class ReaderInfoValues(
    val bookName: String = "",
    val chapterTitle: String = "",
    val time: String = "",
    val battery: Int = 0,
    val page: String = "",
    val totalPages: String = "",
    val readProgress: String = "",
    val chapter: String = "",
    val totalChapters: String = "",
)

sealed interface ReaderInfoPart {
    data class Text(val value: String) : ReaderInfoPart
    data class BatteryIcon(val level: Int) : ReaderInfoPart
}

object ReaderInfoTemplate {
    const val BOOK_NAME = "{书名}"
    const val CHAPTER_TITLE = "{章节}"
    const val TIME = "{时间}"
    const val BATTERY = "{电量}"
    const val BATTERY_ICON = "{电量图标}"
    const val PAGE = "{页码}"
    const val TOTAL_PAGES = "{总页数}"
    const val READ_PROGRESS = "{阅读进度}"
    const val CHAPTER = "{章节序号}"
    const val TOTAL_CHAPTERS = "{章节总数}"

    val placeholders = listOf(
        BOOK_NAME, CHAPTER_TITLE, TIME, BATTERY, BATTERY_ICON,
        PAGE, TOTAL_PAGES, READ_PROGRESS, CHAPTER, TOTAL_CHAPTERS,
    )

    fun parse(template: String, values: ReaderInfoValues): List<ReaderInfoPart> {
        val parts = mutableListOf<ReaderInfoPart>()
        val text = StringBuilder()
        val battery = values.battery.coerceIn(0, 100)
        var index = 0

        fun appendText(value: String) {
            text.append(value)
        }

        fun appendBatteryIcon() {
            if (text.isNotEmpty()) {
                parts.add(ReaderInfoPart.Text(text.toString()))
                text.clear()
            }
            parts.add(ReaderInfoPart.BatteryIcon(battery))
        }

        while (index < template.length) {
            if (template[index] != '{') {
                text.append(template[index])
                index++
                continue
            }

            var depth = 0
            var close = -1
            var containsNestedBraces = false
            var cursor = index
            while (cursor < template.length) {
                when (template[cursor]) {
                    '{' -> {
                        depth++
                        containsNestedBraces = containsNestedBraces || depth > 1
                    }

                    '}' -> {
                        depth--
                        if (depth == 0) {
                            close = cursor
                            break
                        }
                    }
                }
                cursor++
            }
            if (close < 0) {
                text.append('{')
                index++
                continue
            }

            val token = template.substring(index, close + 1)
            val touchesOuterBrace =
                index > 0 && template[index - 1] == '{' ||
                    close + 1 < template.length && template[close + 1] == '}'
            when {
                containsNestedBraces || touchesOuterBrace -> appendText(token)
                token == BOOK_NAME -> appendText(values.bookName)
                token == CHAPTER_TITLE -> appendText(values.chapterTitle)
                token == TIME -> appendText(values.time)
                token == BATTERY -> appendText("$battery%")
                token == BATTERY_ICON -> appendBatteryIcon()
                token == PAGE -> appendText(values.page)
                token == TOTAL_PAGES -> appendText(values.totalPages)
                token == READ_PROGRESS -> appendText(values.readProgress)
                token == CHAPTER -> appendText(values.chapter)
                token == TOTAL_CHAPTERS -> appendText(values.totalChapters)
                else -> appendText(token)
            }
            index = close + 1
        }
        if (text.isNotEmpty()) {
            parts.add(ReaderInfoPart.Text(text.toString()))
        }
        return parts
    }

    fun fromLegacy(tip: Int): String = when (tip) {
        ReadTipConfig.chapterTitle -> CHAPTER_TITLE
        ReadTipConfig.time -> TIME
        ReadTipConfig.battery -> BATTERY_ICON
        ReadTipConfig.batteryPercentage -> BATTERY
        ReadTipConfig.page -> "$PAGE/$TOTAL_PAGES"
        ReadTipConfig.totalProgress -> READ_PROGRESS
        ReadTipConfig.pageAndTotal -> "$PAGE/$TOTAL_PAGES  $READ_PROGRESS"
        ReadTipConfig.bookName -> BOOK_NAME
        ReadTipConfig.timeBattery -> "$TIME  $BATTERY_ICON"
        ReadTipConfig.timeBatteryPercentage -> "$TIME $BATTERY"
        ReadTipConfig.totalProgress1 -> "$CHAPTER/$TOTAL_CHAPTERS"
        else -> ""
    }
}
