package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordBook
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.data.entities.ReadRecordAuthors
import io.legado.app.data.entities.mergeRestoredReadRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadRecordDao {

    @get:Query("select * from readRecord")
    val all: List<ReadRecord>

    @Query(
        """select distinct bookName, author from readRecord
            order by bookName collate localized, author collate localized"""
    )
    fun flowBooks(): Flow<List<ReadRecordBook>>

    @get:Query(
        """
        select history.bookName, sum(history.readTime) as readTime,
            max(history.lastRead) as lastRead, history.author,
            snapshot.lastChapterTitle, snapshot.lastChapterIndex,
            snapshot.lastChapterPos, snapshot.coverUrl
        from readRecord history
        join readRecord snapshot on snapshot.bookName = history.bookName and snapshot.author = history.author
            and snapshot.deviceId = (select deviceId from readRecord
                where bookName = history.bookName and author = history.author
                order by lastRead desc, deviceId limit 1)
        group by history.bookName, history.author
        order by history.bookName collate localized, history.author collate localized"""
    )
    val allShow: List<ReadRecordShow>

    @get:Query("select sum(readTime) from readRecord")
    val allTime: Long

    @Query(
        """
        select history.bookName, sum(history.readTime) as readTime,
            max(history.lastRead) as lastRead, history.author,
            snapshot.lastChapterTitle, snapshot.lastChapterIndex,
            snapshot.lastChapterPos, snapshot.coverUrl
        from readRecord history
        join readRecord snapshot on snapshot.bookName = history.bookName and snapshot.author = history.author
            and snapshot.deviceId = (select deviceId from readRecord
                where bookName = history.bookName and author = history.author
                order by lastRead desc, deviceId limit 1)
        group by history.bookName, history.author
        having history.bookName like '%' || :searchKey || '%'
            or history.author like '%' || :searchKey || '%'
        order by history.bookName collate localized, history.author collate localized"""
    )
    fun search(searchKey: String): List<ReadRecordShow>

    @Query("select readTime from readRecord where deviceId = :deviceId and bookName = :bookName and author = :author")
    fun getReadTime(deviceId: String, bookName: String, author: String): Long?

    @Query("select * from readRecord where deviceId = :deviceId and bookName = :bookName and author = :author")
    fun getRecord(deviceId: String, bookName: String, author: String): ReadRecord?

    @Query("""update readRecord set coverUrl = :coverUrl
        where deviceId = :deviceId and bookName = :bookName and author = :author and coverUrl = :expected""")
    fun updateCoverIfUnchanged(deviceId: String, bookName: String, author: String, expected: String, coverUrl: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg readRecord: ReadRecord)

    @Update
    fun update(vararg record: ReadRecord)

    @Delete
    fun delete(vararg record: ReadRecord)

    @Query("delete from readRecord")
    fun clear()

    @Query("delete from readRecord where bookName = :bookName and author = :author")
    fun deleteByBook(bookName: String, author: String)

    @Query("select * from readRecord where bookName = :bookName and author = :author")
    fun getRecords(bookName: String, author: String): List<ReadRecord>

    /** A user removes an obsolete label; the legacy row's undivided duration stays intact. */
    @Transaction
    fun removeLegacyAuthor(bookName: String, author: String, removedAuthor: String) {
        val authors = ReadRecordAuthors.decode(author)
        if (!ReadRecordAuthors.isCombined(author) || authors.size < 2 || removedAuthor !in authors) return
        val remaining = (authors - removedAuthor).reduce(ReadRecordAuthors::merge)
        mergeAuthorRecords(bookName, author, remaining)
    }

    /** The first subsequent reading supplies the author for this title's unknown history. */
    @Transaction
    fun mergeUnknownAuthor(bookName: String, author: String) {
        if (author.isBlank() || ReadRecordAuthors.isCombined(author)) return
        mergeAuthorRecords(bookName, "", author)
    }

    private fun mergeAuthorRecords(bookName: String, author: String, remaining: String) {
        getRecords(bookName, author).forEach { original ->
            val renamed = original.copy(author = remaining)
            val current = getRecord(original.deviceId, bookName, remaining)
            val saved = if (current == null) renamed else {
                mergeRestoredReadRecord(current, renamed, localDevice = true).copy(
                    readTime = Math.addExact(current.readTime, renamed.readTime),
                )
            }
            delete(original)
            insert(saved)
        }
    }
}
