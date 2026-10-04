package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordBook
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.data.entities.ReadRecordAuthors
import io.legado.app.data.entities.withDisplayMetadata
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadRecordDao {

    @get:Query("select * from readRecord")
    val all: List<ReadRecord>

    @Query(
        """select distinct displayBookName as bookName, displayAuthor as author from readRecord
            order by bookName collate localized, author collate localized"""
    )
    fun flowBooks(): Flow<List<ReadRecordBook>>

    @get:Query(
        """
        select history.displayBookName as bookName, sum(history.readTime) as readTime,
            max(history.lastRead) as lastRead,
            history.displayAuthor as author,
            snapshot.lastChapterTitle, snapshot.lastChapterIndex,
            snapshot.lastChapterPos, snapshot.coverUrl
        from readRecord history
        join readRecord snapshot on snapshot.rowid = (select rowid from readRecord
                where displayBookName = history.displayBookName and displayAuthor = history.displayAuthor
                order by lastRead desc, deviceId, bookName, author limit 1)
        group by history.displayBookName, history.displayAuthor
        order by history.displayBookName collate localized, author collate localized"""
    )
    val allShow: List<ReadRecordShow>

    @get:Query("select sum(readTime) from readRecord")
    val allTime: Long

    @Query(
        """
        select history.displayBookName as bookName, sum(history.readTime) as readTime,
            max(history.lastRead) as lastRead,
            history.displayAuthor as author,
            snapshot.lastChapterTitle, snapshot.lastChapterIndex,
            snapshot.lastChapterPos, snapshot.coverUrl
        from readRecord history
        join readRecord snapshot on snapshot.rowid = (select rowid from readRecord
                where displayBookName = history.displayBookName and displayAuthor = history.displayAuthor
                order by lastRead desc, deviceId, bookName, author limit 1)
        group by history.displayBookName, history.displayAuthor
        having history.displayBookName like '%' || :searchKey || '%'
            or history.displayAuthor like '%' || :searchKey || '%'
        order by history.displayBookName collate localized, author collate localized"""
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
    fun insertRecords(vararg readRecord: ReadRecord)

    @Query("""select * from readRecord where bookName = :bookName and author = :author
        and metadataEditedAt > 0 order by metadataEditedAt desc limit 1""")
    fun latestMetadata(bookName: String, author: String): ReadRecord?

    @Transaction
    fun insert(vararg readRecord: ReadRecord) {
        readRecord.groupBy { it.bookName to it.author }.forEach { (identity, records) ->
            val stored = latestMetadata(identity.first, identity.second)
            val incoming = records.maxByOrNull { it.metadataEditedAt }
            val metadata = if ((stored?.metadataEditedAt ?: 0) >= (incoming?.metadataEditedAt ?: 0)) stored else incoming
            insertRecords(*records.map { it.withDisplayMetadata(metadata) }.toTypedArray())
            if (metadata != null && metadata.metadataEditedAt > 0) {
                updatePhysicalMetadata(identity.first, identity.second, metadata.displayBookName,
                    metadata.displayAuthor, metadata.metadataEditedAt)
            }
        }
    }

    @Query("""update readRecord set displayBookName = :name, displayAuthor = :displayAuthor, metadataEditedAt = :editedAt
        where bookName = :bookName and author = :author and metadataEditedAt <= :editedAt""")
    fun updatePhysicalMetadata(bookName: String, author: String, name: String, displayAuthor: String, editedAt: Long)

    @Update
    fun update(vararg record: ReadRecord)

    @Delete
    fun delete(vararg record: ReadRecord)

    @Query("delete from readRecord")
    fun clear()

    @Query("delete from readRecord where displayBookName = :bookName and displayAuthor = :author")
    fun deleteByBook(bookName: String, author: String)

    @Query("select * from readRecord where displayBookName = :bookName and displayAuthor = :author")
    fun getRecords(bookName: String, author: String): List<ReadRecord>

    @Query("select coalesce(max(metadataEditedAt), 0) from readRecord")
    fun latestMetadataEditTime(): Long

    @Query("""update readRecord set displayBookName = :newName, displayAuthor = :newAuthor, metadataEditedAt = :editedAt
        where displayBookName = :oldName and displayAuthor = :oldAuthor""")
    fun updateDisplayMetadata(oldName: String, oldAuthor: String, newName: String, newAuthor: String, editedAt: Long): Int

    @Transaction
    fun renameBook(oldName: String, oldAuthor: String, newName: String, newAuthor: String) {
        require(newName.isNotBlank())
        if (oldName == newName && oldAuthor == newAuthor) return
        updateDisplayMetadata(oldName, oldAuthor, newName, newAuthor,
            maxOf(System.currentTimeMillis(), latestMetadataEditTime() + 1, 1))
    }

    /** A user removes an obsolete label; the legacy row's undivided duration stays intact. */
    @Transaction
    fun removeLegacyAuthor(bookName: String, author: String, removedAuthor: String) {
        val authors = ReadRecordAuthors.decode(author)
        if (!ReadRecordAuthors.isCombined(author) || authors.size < 2 || removedAuthor !in authors) return
        val remaining = (authors - removedAuthor).reduce(ReadRecordAuthors::merge)
        renameBook(bookName, author, bookName, remaining)
    }

    /** The first subsequent reading supplies the author for this title's unknown history. */
    @Transaction
    fun resolveUnknownAuthor(bookName: String, author: String) {
        if (author.isBlank() || ReadRecordAuthors.isCombined(author)) return
        assignUnknownAuthor(bookName, author)
    }

    @Query("""update readRecord set resolvedAuthor = :author,
        displayAuthor = case when metadataEditedAt = 0 then :author else displayAuthor end
        where bookName = :bookName and author = '' and resolvedAuthor is null""")
    fun assignUnknownAuthor(bookName: String, author: String)

}
