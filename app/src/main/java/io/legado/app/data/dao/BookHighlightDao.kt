package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.BookHighlight
import kotlinx.coroutines.flow.Flow

@Dao
interface BookHighlightDao {

    @get:Query(
        """select * from highlights
        order by bookName collate localized, bookAuthor collate localized, chapterIndex, chapterPos, time"""
    )
    val all: List<BookHighlight>

    @Query(
        """select * from highlights
        where bookUrl = :bookUrl
        order by chapterIndex, chapterPos, time"""
    )
    fun getByBook(bookUrl: String): List<BookHighlight>

    @Query(
        """select * from highlights
        where bookUrl = :bookUrl
        order by chapterIndex, chapterPos, time"""
    )
    fun flowByBook(bookUrl: String): Flow<List<BookHighlight>>

    @Query(
        """select * from highlights
        where bookUrl = :bookUrl and (
            chapterName like '%' || :key || '%'
            or bookText like '%' || :key || '%'
            or note like '%' || :key || '%'
        )
        order by chapterIndex, chapterPos, time"""
    )
    fun flowSearch(bookUrl: String, key: String): Flow<List<BookHighlight>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg highlight: BookHighlight)

    @Query(
        """update highlights set layoutTitleLength = :layoutTitleLength
        where bookUrl = :bookUrl and chapterUrl = :chapterUrl
        and layoutTitleLength < 0"""
    )
    fun pinLayoutTitleLength(
        bookUrl: String,
        chapterUrl: String,
        layoutTitleLength: Int
    )

    @Query(
        """update highlights set chapterUrl = :chapterUrl
        where time in (:times) and chapterUrl = ''"""
    )
    fun bindChapterUrl(times: List<Long>, chapterUrl: String)

    @Query(
        """update highlights set bookName = :bookName, bookAuthor = :bookAuthor
        where bookUrl = :bookUrl"""
    )
    fun updateBookMetadata(bookUrl: String, bookName: String, bookAuthor: String)

    @Update
    fun update(highlight: BookHighlight)

    @Delete
    fun delete(vararg highlight: BookHighlight)
}
