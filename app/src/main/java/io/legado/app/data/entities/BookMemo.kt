package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

// No cascading foreign key: replacing a bookshelf row during import must preserve its memo.
@Entity(tableName = "book_memos")
data class BookMemo(
    @PrimaryKey val bookUrl: String,
    val content: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)
