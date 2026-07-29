package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(tableName = "readRecord", primaryKeys = ["deviceId", "bookName"])
data class ReadRecord(
    var deviceId: String = "",
    var bookName: String = "",
    /**
     * 书名相同的书籍共用一条记录,作者只用于辅助判断书籍身份,旧记录和旧备份为空
     */
    @ColumnInfo(defaultValue = "")
    var author: String = "",
    @ColumnInfo(defaultValue = "0")
    var readTime: Long = 0L,
    @ColumnInfo(defaultValue = "0")
    var lastRead: Long = System.currentTimeMillis()
)