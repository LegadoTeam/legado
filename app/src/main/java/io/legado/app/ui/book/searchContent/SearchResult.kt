package io.legado.app.ui.book.searchContent

import android.text.Spanned
import androidx.core.text.HtmlCompat
import io.legado.app.help.config.AppConfig

data class SearchResult(
    val resultCount: Int = 0,
    val resultCountWithinChapter: Int = 0,
    val resultText: String = "",
    val chapterTitle: String = "",
    val query: String = "",
    val pageSize: Int = 0,
    val chapterIndex: Int = 0,
    val pageIndex: Int = 0,
    val queryIndexInResult: Int = 0,
    val queryIndexInChapter: Int = 0,
    val isRegex: Boolean = false
) {
    fun getHtmlCompat(textColor: String, accentColor: String): Spanned {
        if (query.isNotBlank()) {
            if (isRegex) { // 正则表达式高亮处理
                try {
                    val match = Regex(query).find(resultText)
                    if (match != null) {
                        val matchedText = match.value
                        val start = match.range.first
                        val end = match.range.last + 1
                        val leftString = resultText.take(start)
                        val rightString = resultText.substring(end)
                        val html = if (AppConfig.isEInkMode) {
                            // 墨水屏模式：使用下划线
                            buildString {
                                append("<u>${chapterTitle}</u>")
                                append("<br>")
                                append(leftString)
                                append("<u>${matchedText}</u>")
                                append(rightString)
                            }
                        } else {
                            // 普通模式：使用颜色
                            buildString {
                                append(chapterTitle.colorTextForHtml(accentColor))
                                append("<br>")
                                append(leftString.colorTextForHtml(textColor))
                                append(matchedText.colorTextForHtml(accentColor))
                                append(rightString.colorTextForHtml(textColor))
                            }
                        }
                        return HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
                    } else {
                        return getNormalHtml(textColor)
                    }
                } catch (e: Exception) {
                    return getNormalHtml(textColor)
                }
            } else {
                // 普通搜索高亮
                val queryIndexInSurrounding = resultText.indexOf(query)
                if (queryIndexInSurrounding >= 0) {
                    val leftString = resultText.take(queryIndexInSurrounding)
                    val rightString = resultText.substring(queryIndexInSurrounding + query.length)
                    // 检查是否为墨水屏模式
                    val html = if (AppConfig.isEInkMode) {
                        // 墨水屏模式：使用下划线
                        buildString {
                            append("<u>${chapterTitle}</u>")
                            append("<br>")
                            append(leftString)
                            append("<u>${query}</u>")
                            append(rightString)
                        }
                    } else {
                        // 普通模式：使用颜色
                        buildString {
                            append(chapterTitle.colorTextForHtml(accentColor))
                            append("<br>")
                            append(leftString.colorTextForHtml(textColor))
                            append(query.colorTextForHtml(accentColor))
                            append(rightString.colorTextForHtml(textColor))
                        }
                    }
                    return HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
                } else {
                    return getNormalHtml(textColor)
                }
            }
        } else {
            return getNormalHtml(textColor)
        }
    }

    private fun getNormalHtml(textColor: String): Spanned {
        val html = if (AppConfig.isEInkMode) {
            resultText
        } else {
            resultText.colorTextForHtml(textColor)
        }
        return HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
    }

    private fun String.colorTextForHtml(textColor: String) =
        "<font color=#${textColor}>$this</font>"

}
