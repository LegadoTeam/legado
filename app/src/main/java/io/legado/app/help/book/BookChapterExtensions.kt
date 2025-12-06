@file:Suppress("unused")

package io.legado.app.help.book

import io.legado.app.data.entities.BookChapter
import io.legado.app.help.RuleBigDataHelp.getDanmakuFile

fun BookChapter.getDanmaku(): Any? { //读取弹幕数据
    return variableMap["danmaku"] ?: getDanmakuFile(bookUrl, url)
}