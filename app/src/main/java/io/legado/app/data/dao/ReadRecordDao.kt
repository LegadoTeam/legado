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
        """select distinct bookName, case when author = '' then coalesce(resolvedAuthor, '') else author end as author from readRecord
            order by bookName collate localized, author collate localized"""
    )
    fun flowBooks(): Flow<List<ReadRecordBook>>

    @get:Query(
        """
        select history.bookName, sum(history.readTime) as readTime,
            max(history.lastRead) as lastRead,
            case when history.author = '' then coalesce(history.resolvedAuthor, '') else history.author end as author,
            snapshot.lastChapterTitle, snapshot.lastChapterIndex,
            snapshot.lastChapterPos, snapshot.coverUrl
        from readRecord history
        join readRecord snapshot on snapshot.rowid = (select rowid from readRecord
                where bookName = history.bookName and
                    (case when author = '' then coalesce(resolvedAuthor, '') else author end) =
                    (case when history.author = '' then coalesce(history.resolvedAuthor, '') else history.author end)
                order by lastRead desc, deviceId, author limit 1)
        group by history.bookName,
            case when history.author = '' then coalesce(history.resolvedAuthor, '') else history.author end
        order by history.bookName collate localized, author collate localized"""
    )
    val allShow: List<ReadRecordShow>

    @get:Query("select sum(readTime) from readRecord")
    val allTime: Long

    @Query(
        """
        select history.bookName, sum(history.readTime) as readTime,
            max(history.lastRead) as lastRead,
            case when history.author = '' then coalesce(history.resolvedAuthor, '') else history.author end as author,
            snapshot.lastChapterTitle, snapshot.lastChapterIndex,
            snapshot.lastChapterPos, snapshot.coverUrl
        from readRecord history
        join readRecord snapshot on snapshot.rowid = (select rowid from readRecord
                where bookName = history.bookName and
                    (case when author = '' then coalesce(resolvedAuthor, '') else author end) =
                    (case when history.author = '' then coalesce(history.resolvedAuthor, '') else history.author end)
                order by lastRead desc, deviceId, author limit 1)
        group by history.bookName,
            case when history.author = '' then coalesce(history.resolvedAuthor, '') else history.author end
        having history.bookName like '%' || :searchKey || '%'
            or (case when history.author = '' then coalesce(history.resolvedAuthor, '') else history.author end) like '%' || :searchKey || '%'
        order by history.bookName collate localized, author collate localized"""
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

    @Query("""delete from readRecord where bookName = :bookName and
        (case when author = '' then coalesce(resolvedAuthor, '') else author end) = :author""")
    fun deleteByBook(bookName: String, author: String)

    @Query("select * from readRecord where bookName = :bookName and author = :author")
    fun getRecords(bookName: String, author: String): List<ReadRecord>

    /** A user removes an obsolete label; the legacy row's undivided duration stays intact. */
    @Transaction
    fun removeLegacyAuthor(bookName: String, author: String, removedAuthor: String) {
        val authors = ReadRecordAuthors.decode(author)
        if (!ReadRecordAuthors.isCombined(author) || authors.size < 2 || removedAuthor !in authors) return
        val remaining = (authors - removedAuthor).reduce(ReadRecordAuthors::merge)
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

    /** The first subsequent reading supplies the author for this title's unknown history. */
    @Transaction
    fun resolveUnknownAuthor(bookName: String, author: String) {
        if (author.isBlank() || ReadRecordAuthors.isCombined(author)) return
        assignUnknownAuthor(bookName, author)
    }

    @Query("""update readRecord set resolvedAuthor = :author
        where bookName = :bookName and author = '' and resolvedAuthor is null""")
    fun assignUnknownAuthor(bookName: String, author: String)

}
