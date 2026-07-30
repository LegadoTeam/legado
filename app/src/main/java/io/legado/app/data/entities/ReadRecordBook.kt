package io.legado.app.data.entities

/**
 * 阅读记录中的书籍身份,同一本书在多个设备上会有多条记录
 */
data class ReadRecordBook(
    var bookName: String,
    var author: String
)
