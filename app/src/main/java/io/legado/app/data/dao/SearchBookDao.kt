package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.SearchBook

internal const val GROUP_TRIM_CHARACTERS =
    "char(9,10,11,12,13,28,29,30,31,32,160,5760,8192,8193,8194,8195,8196," +
        "8197,8198,8199,8200,8201,8202,8232,8233,8239,8287,12288)"

internal const val NON_EMPTY_SOURCE_GROUP_CONDITION =
    "trim(:sourceGroup, $GROUP_TRIM_CHARACTERS) <> ''"

internal const val SOURCE_GROUP_MEMBERSHIP_FILTER = """
and (
    trim(:sourceGroup, $GROUP_TRIM_CHARACTERS) = ''
    or exists (
        with recursive source_groups(group_name, rest) as (
            select '',
                replace(replace(replace(coalesce(t2.bookSourceGroup, ''), ';', ','), '，', ','), '；', ',') || ','
            union all
            select
                trim(substr(rest, 1, instr(rest, ',') - 1), $GROUP_TRIM_CHARACTERS),
                substr(rest, instr(rest, ',') + 1)
            from source_groups
            where rest <> ''
        )
        select 1
        from source_groups
        where group_name = trim(:sourceGroup, $GROUP_TRIM_CHARACTERS)
    )
)
"""

@Dao
interface SearchBookDao {

    @Query("select * from searchBooks where bookUrl = :bookUrl")
    fun getSearchBook(bookUrl: String): SearchBook?

    @Query("select * from searchBooks where name = :name and author = :author and origin in (select bookSourceUrl from book_sources) order by originOrder limit 1")
    fun getFirstByNameAuthor(name: String, author: String): SearchBook?

    @Query(
        """select t1.name, t1.author, t1.origin, t1.originName, t1.coverUrl, t1.bookUrl, 
        t1.type, t1.time, t1.intro, t1.kind, t1.latestChapterTitle, t1.tocUrl, t1.variable, 
        t1.wordCount, t2.customOrder as originOrder, t1.chapterWordCountText, t1.respondTime, t1.chapterWordCount
        from searchBooks as t1 inner join book_sources as t2 
        on t1.origin = t2.bookSourceUrl 
        where t1.name = :name and t1.author like '%'||:author||'%' 
        and t2.enabled = 1
        """ + SOURCE_GROUP_MEMBERSHIP_FILTER + """
        order by t2.customOrder"""
    )
    fun changeSourceByGroup(name: String, author: String, sourceGroup: String): List<SearchBook>

    @Query(
        """select t1.name, t1.author, t1.origin, t1.originName, t1.coverUrl, t1.bookUrl, 
        t1.type, t1.time, t1.intro, t1.kind, t1.latestChapterTitle, t1.tocUrl, t1.variable, 
        t1.wordCount, t2.customOrder as originOrder, t1.chapterWordCountText, t1.respondTime, t1.chapterWordCount
        from searchBooks as t1 inner join book_sources as t2 
        on t1.origin = t2.bookSourceUrl 
        where t1.name = :name and t1.author like '%'||:author||'%'
        """ + SOURCE_GROUP_MEMBERSHIP_FILTER + """
        and (originName like '%'||:key||'%' or t1.latestChapterTitle like '%'||:key||'%')
        and t2.enabled = 1 
        order by t2.customOrder"""
    )
    fun changeSourceSearch(
        name: String,
        author: String,
        key: String,
        sourceGroup: String
    ): List<SearchBook>

    @Query(
        """
        select t1.name, t1.author, t1.origin, t1.originName, t1.coverUrl, t1.bookUrl, 
        t1.type, t1.time, t1.intro, t1.kind, t1.latestChapterTitle, t1.tocUrl, t1.variable, 
        t1.wordCount, t2.customOrder as originOrder, t1.chapterWordCountText, t1.respondTime, t1.chapterWordCount
        from searchBooks as t1 inner join book_sources as t2 
        on t1.origin = t2.bookSourceUrl 
        where t1.name = :name and t1.author = :author and t1.coverUrl is not null and t1.coverUrl <> '' and t2.enabled = 1
        order by t2.customOrder
        """
    )
    fun getEnableHasCover(name: String, author: String): List<SearchBook>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg searchBook: SearchBook): List<Long>

    @Query("delete from searchBooks where name = :name and author = :author")
    fun clear(name: String, author: String)

    @Query("delete from searchBooks where time < :time")
    fun clearExpired(time: Long)

    @Update
    fun update(vararg searchBook: SearchBook)

    @Delete
    fun delete(vararg searchBook: SearchBook)
}
