package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordAuthors
import io.legado.app.data.entities.ReadRecordBook
import io.legado.app.data.entities.ReadRecordShow
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
            max(history.lastRead) as lastRead, group_concat(history.author, char(31)) as author,
            snapshot.lastChapterTitle, snapshot.lastChapterIndex,
            snapshot.lastChapterPos, snapshot.coverUrl
        from readRecord history
        join readRecord snapshot on snapshot.bookName = history.bookName
            and snapshot.deviceId = (select deviceId from readRecord
                where bookName = history.bookName order by lastRead desc, deviceId limit 1)
        group by history.bookName
        order by history.bookName collate localized"""
    )
    val allShow: List<ReadRecordShow>

    @get:Query("select sum(readTime) from readRecord")
    val allTime: Long

    @Query(
        """
        select history.bookName, sum(history.readTime) as readTime,
            max(history.lastRead) as lastRead, group_concat(history.author, char(31)) as author,
            snapshot.lastChapterTitle, snapshot.lastChapterIndex,
            snapshot.lastChapterPos, snapshot.coverUrl
        from readRecord history
        join readRecord snapshot on snapshot.bookName = history.bookName
            and snapshot.deviceId = (select deviceId from readRecord
                where bookName = history.bookName order by lastRead desc, deviceId limit 1)
        group by history.bookName
        having history.bookName like '%' || :searchKey || '%'
            or group_concat(history.author, char(31)) like '%' || :searchKey || '%'
        order by history.bookName collate localized"""
    )
    fun search(searchKey: String): List<ReadRecordShow>

    @Query("select readTime from readRecord where deviceId = :deviceId and bookName = :bookName")
    fun getReadTime(deviceId: String, bookName: String): Long?

    @Query("select * from readRecord where deviceId = :deviceId and bookName = :bookName")
    fun getRecord(deviceId: String, bookName: String): ReadRecord?

    @Query("""update readRecord set coverUrl = :coverUrl
        where deviceId = :deviceId and bookName = :bookName and coverUrl = :expected""")
    fun updateCoverIfUnchanged(deviceId: String, bookName: String, expected: String, coverUrl: String): Int

    @Query("select author from readRecord where deviceId = :deviceId and bookName = :bookName")
    fun getAuthor(deviceId: String, bookName: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertRaw(vararg readRecord: ReadRecord)

    @Transaction
    fun insert(vararg readRecord: ReadRecord) {
        readRecord.forEach { record ->
            val author = ReadRecordAuthors.merge(
                getAuthor(record.deviceId, record.bookName).orEmpty(),
                record.author,
            )
            insertRaw(record.copy(author = author))
        }
    }

    @Update
    fun update(vararg record: ReadRecord)

    @Delete
    fun delete(vararg record: ReadRecord)

    @Query("delete from readRecord")
    fun clear()

    @Query("delete from readRecord where bookName = :bookName")
    fun deleteByName(bookName: String)
}
