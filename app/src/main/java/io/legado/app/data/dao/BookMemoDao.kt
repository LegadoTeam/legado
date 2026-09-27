package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.legado.app.data.entities.BookMemo
import kotlinx.coroutines.flow.Flow

@Dao
interface BookMemoDao {
    @Query("select * from book_memos where bookUrl = :bookUrl")
    fun get(bookUrl: String): BookMemo?

    @Query("select * from book_memos where bookUrl = :bookUrl")
    fun flow(bookUrl: String): Flow<BookMemo?>

    @Query("select book_memos.* from book_memos inner join books using (bookUrl)")
    fun all(): List<BookMemo>

    @Query("select exists(select 1 from books where bookUrl = :bookUrl)")
    fun hasBook(bookUrl: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(memo: BookMemo)

    @Transaction
    fun save(bookUrl: String, content: String, now: Long = System.currentTimeMillis()) {
        check(hasBook(bookUrl)) { "The memo's book no longer exists" }
        val previous = get(bookUrl)
        // Keep an empty, dated entry when clearing so an older backup cannot restore the old text.
        insert(BookMemo(bookUrl, content, maxOf(now, (previous?.updatedAt ?: 0L) + 1)))
    }

    @Transaction
    fun restore(memos: List<BookMemo>) {
        memos.forEach { memo ->
            if (hasBook(memo.bookUrl) && memo.updatedAt > (get(memo.bookUrl)?.updatedAt ?: -1L)) {
                insert(memo)
            }
        }
    }
}
