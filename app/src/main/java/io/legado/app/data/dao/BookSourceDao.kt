package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.utils.cnCompare
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private const val BOOK_SOURCE_NO_GROUP_FILTER = """
trim(coalesce(bookSourceGroup, ''), $GROUP_TRIM_CHARACTERS) in ('', '未分组')
"""

@Dao
interface BookSourceDao {

    @Query("select * from book_sources_part order by customOrder asc")
    fun flowAll(): Flow<List<BookSourcePart>>

    @Query(
        """select bp.*
        from book_sources b join book_sources_part bp on b.bookSourceUrl = bp.bookSourceUrl 
        where b.bookSourceName like '%' || :searchKey || '%'
        or b.bookSourceGroup like '%' || :searchKey || '%'
        or b.bookSourceUrl like '%' || :searchKey || '%'
        or b.bookSourceComment like '%' || :searchKey || '%' 
        order by b.customOrder asc"""
    )
    fun flowSearch(searchKey: String): Flow<List<BookSourcePart>>

    @Query(
        """select * from book_sources 
        where bookSourceName like '%' || :searchKey || '%'
        or bookSourceGroup like '%' || :searchKey || '%'
        or bookSourceUrl like '%' || :searchKey || '%'
        or bookSourceComment like '%' || :searchKey || '%' 
        order by customOrder asc"""
    )
    fun search(searchKey: String): List<BookSource>

    @Query(
        """select bp.*
        from book_sources b join book_sources_part bp on b.bookSourceUrl = bp.bookSourceUrl 
        where b.enabled = 1 
        and (b.bookSourceName like '%' || :searchKey || '%' 
        or b.bookSourceGroup like '%' || :searchKey || '%' 
        or b.bookSourceUrl like '%' || :searchKey || '%'  
        or b.bookSourceComment like '%' || :searchKey || '%')
        order by b.customOrder asc"""
    )
    fun flowSearchEnabled(searchKey: String): Flow<List<BookSourcePart>>

    @Query(
        """select t2.* from book_sources_part as t2
        where """ + NON_EMPTY_SOURCE_GROUP_CONDITION + """
        """ + SOURCE_GROUP_MEMBERSHIP_FILTER + """
        order by t2.customOrder asc"""
    )
    fun flowGroupSearch(sourceGroup: String): Flow<List<BookSourcePart>>

    @Query(
        """select t2.* from book_sources as t2
        where """ + NON_EMPTY_SOURCE_GROUP_CONDITION + """
        """ + SOURCE_GROUP_MEMBERSHIP_FILTER + """
        order by t2.customOrder asc"""
    )
    fun groupSearch(sourceGroup: String): List<BookSource>

    @Query("select * from book_sources_part where enabled = 1 order by customOrder asc")
    fun flowEnabled(): Flow<List<BookSourcePart>>

    @Query("select * from book_sources_part where enabled = 0 order by customOrder asc")
    fun flowDisabled(): Flow<List<BookSourcePart>>

    @Query(
        """select * from book_sources_part 
        where enabledExplore = 1 and hasExploreUrl = 1 order by customOrder asc"""
    )
    fun flowExplore(): Flow<List<BookSourcePart>>

    @Query("select * from book_sources_part where hasLoginUrl = 1 order by customOrder asc")
    fun flowLogin(): Flow<List<BookSourcePart>>

    @Query(
        """select * from book_sources_part 
        where """ + BOOK_SOURCE_NO_GROUP_FILTER + """
        order by customOrder asc"""
    )
    fun flowNoGroup(): Flow<List<BookSourcePart>>

    @Query("select * from book_sources_part where enabledExplore = 1 order by customOrder asc")
    fun flowEnabledExplore(): Flow<List<BookSourcePart>>

    @Query("select * from book_sources_part where enabledExplore = 0 order by customOrder asc")
    fun flowDisabledExplore(): Flow<List<BookSourcePart>>

    @Query(
        """select * from book_sources_part 
        where enabledExplore = 1 
        and hasExploreUrl = 1 
        and (bookSourceGroup like '%' || :key || '%' 
            or bookSourceName like '%' || :key || '%') 
        order by customOrder asc"""
    )
    fun flowExplore(key: String): Flow<List<BookSourcePart>>

    @Query(
        """select t2.* from book_sources_part as t2
        where t2.enabledExplore = 1
        and t2.hasExploreUrl = 1
        and """ + NON_EMPTY_SOURCE_GROUP_CONDITION + """
        """ + SOURCE_GROUP_MEMBERSHIP_FILTER + """
        order by t2.customOrder asc"""
    )
    fun flowGroupExplore(sourceGroup: String): Flow<List<BookSourcePart>>

    @Query("select distinct bookSourceGroup from book_sources where trim(bookSourceGroup) <> ''")
    fun flowGroupsUnProcessed(): Flow<List<String>>

    @Query(
        """select distinct bookSourceGroup from book_sources 
        where enabled = 1 and trim(bookSourceGroup) <> ''"""
    )
    fun flowEnabledGroupsUnProcessed(): Flow<List<String>>

    @Query(
        """select distinct bookSourceGroup from book_sources 
        where enabledExplore = 1 
        and trim(exploreUrl) <> '' 
        and trim(bookSourceGroup) <> ''
        order by customOrder"""
    )
    fun flowExploreGroupsUnProcessed(): Flow<List<String>>

    @Query(
        """select * from book_sources 
        where bookSourceGroup like '%' || :group || '%' order by customOrder asc"""
    )
    fun getByGroup(group: String): List<BookSource>

    @Query(
        """select * from book_sources 
        where enabled = 1 
        and (bookSourceGroup = :group
            or bookSourceGroup like :group || ',%' 
            or bookSourceGroup like  '%,' || :group
            or bookSourceGroup like  '%,' || :group || ',%')
        order by customOrder asc"""
    )
    fun getEnabledByGroup(group: String): List<BookSource>

    @Query(
        """select t2.* from book_sources_part as t2
        where t2.enabled = 1
        """ + SOURCE_GROUP_MEMBERSHIP_FILTER + """
        order by t2.customOrder asc"""
    )
    fun getEnabledPartByGroup(sourceGroup: String): List<BookSourcePart>

    @Query(
        """select * from book_sources 
        where bookUrlPattern != 'NONE' and bookSourceType = :type order by customOrder asc"""
    )
    fun getEnabledByType(type: Int): List<BookSource>

    @Query("select * from book_sources where enabled = 1 and bookSourceUrl = :baseUrl")
    fun getBookSourceAddBook(baseUrl: String): BookSource?

    @get:Query(
        """select bp.* 
        from book_sources b join book_sources_part bp on b.bookSourceUrl = bp.bookSourceUrl
        where b.enabled = 1 
        and trim(b.bookUrlPattern) <> '' 
        and trim(b.bookUrlPattern) <> 'NONE' 
        order by b.customOrder"""
    )
    val hasBookUrlPattern: List<BookSourcePart>

    @get:Query("select * from book_sources where bookSourceGroup is null or bookSourceGroup = ''")
    val noGroup: List<BookSource>

    @get:Query("select * from book_sources order by customOrder asc")
    val all: List<BookSource>

    @get:Query("select * from book_sources_part order by customOrder asc")
    val allPart: List<BookSourcePart>

    @get:Query("select * from book_sources where enabled = 1 order by customOrder")
    val allEnabled: List<BookSource>

    @get:Query("select * from book_sources_part where enabled = 1 order by customOrder asc")
    val allEnabledPart: List<BookSourcePart>

    @get:Query("select * from book_sources where enabled = 0 order by customOrder")
    val allDisabled: List<BookSource>

    @get:Query(
        """select * from book_sources 
        where """ + BOOK_SOURCE_NO_GROUP_FILTER
    )
    val allNoGroup: List<BookSource>

    @get:Query("select * from book_sources where enabledExplore = 1 order by customOrder")
    val allEnabledExplore: List<BookSource>

    @get:Query("select * from book_sources where enabledExplore = 0 order by customOrder")
    val allDisabledExplore: List<BookSource>

    @get:Query("select * from book_sources where loginUrl is not null and loginUrl != ''")
    val allLogin: List<BookSource>

    @get:Query(
        """select bp.*
        from book_sources b join book_sources_part bp on b.bookSourceUrl = bp.bookSourceUrl 
        where b.enabled = 1 and b.bookSourceType = 0 order by b.customOrder"""
    )
    val allTextEnabledPart: List<BookSourcePart>

    @get:Query(
        """select distinct bookSourceGroup from book_sources 
        where trim(bookSourceGroup) <> ''"""
    )
    val allGroupsUnProcessed: List<String>

    @get:Query(
        """select distinct bookSourceGroup from book_sources 
        where enabled = 1 and trim(bookSourceGroup) <> ''"""
    )
    val allEnabledGroupsUnProcessed: List<String>

    @Query("select * from book_sources where bookSourceUrl = :key")
    fun getBookSource(key: String): BookSource?

    @Query("select * from book_sources where bookSourceUrl in (:keys)")
    fun getBookSources(keys: List<String>): List<BookSource>

    @Query("select * from book_sources_part where bookSourceUrl = :key")
    fun getBookSourcePart(key: String): BookSourcePart?

    @Query("select count(*) from book_sources")
    fun allCount(): Int

    @Query("SELECT EXISTS(select 1 from book_sources where bookSourceUrl = :key)")
    fun has(key: String): Boolean

    @Query("select mainJs from book_sources where bookSourceUrl = :key")
    fun getMainJs(key: String): String?

    fun hasJsSource(key: String): Boolean {
        return !getMainJs(key).isNullOrBlank()
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg bookSource: BookSource)

    @Update
    fun update(vararg bookSource: BookSource)

    @Delete
    fun delete(vararg bookSource: BookSource)

    @Query("delete from book_sources where bookSourceUrl = :key")
    fun delete(key: String)

    @Transaction
    fun delete(bookSources: List<BookSourcePart>) {
        for (bs in bookSources) {
            delete(bs.bookSourceUrl)
        }
    }

    @get:Query("select min(customOrder) from book_sources")
    val minOrder: Int

    @get:Query("select max(customOrder) from book_sources")
    val maxOrder: Int

    @get:Query(
        """select exists (select 1 
        from book_sources group by customOrder having count(customOrder) > 1)"""
    )
    val hasDuplicateOrder: Boolean

    @Query("update book_sources set enabled = :enable where bookSourceUrl = :bookSourceUrl")
    fun enable(bookSourceUrl: String, enable: Boolean)

    @Transaction
    fun enable(enable: Boolean, bookSources: List<BookSourcePart>) {
        for (bs in bookSources) {
            enable(bs.bookSourceUrl, enable)
        }
    }

    @Query("update book_sources set enabledExplore = :enable where bookSourceUrl = :bookSourceUrl")
    fun enableExplore(bookSourceUrl: String, enable: Boolean)

    @Transaction
    fun enableExplore(enable: Boolean, bookSources: List<BookSourcePart>) {
        for (bs in bookSources) {
            enableExplore(bs.bookSourceUrl, enable)
        }
    }

    @Query(
        """update book_sources 
        set customOrder = :customOrder where bookSourceUrl = :bookSourceUrl"""
    )
    fun upOrder(bookSourceUrl: String, customOrder: Int)

    @Transaction
    fun upOrder(bookSources: List<BookSourcePart>) {
        for (bs in bookSources) {
            upOrder(bs.bookSourceUrl, bs.customOrder)
        }
    }

    fun upOrder(bookSource: BookSourcePart) {
        upOrder(bookSource.bookSourceUrl, bookSource.customOrder)
    }

    @Query(
        """update book_sources 
        set bookSourceGroup = :bookSourceGroup where bookSourceUrl = :bookSourceUrl"""
    )
    fun upGroup(bookSourceUrl: String, bookSourceGroup: String)

    @Transaction
    fun upGroup(bookSources: List<BookSourcePart>) {
        for (bs in bookSources) {
            bs.bookSourceGroup?.let { upGroup(bs.bookSourceUrl, it) }
        }
    }

    private fun dealGroups(list: List<String>): List<String> {
        val groups = linkedSetOf<String>()
        list.forEach {
            it.splitNotBlank(AppPattern.splitGroupRegex).forEach { group ->
                groups.add(group)
            }
        }
        return groups.sortedWith { o1, o2 ->
            o1.cnCompare(o2)
        }
    }

    fun allGroups(): List<String> = dealGroups(allGroupsUnProcessed)

    fun allEnabledGroups(): List<String> = dealGroups(allEnabledGroupsUnProcessed)

    fun flowGroups(): Flow<List<String>> {
        return flowGroupsUnProcessed().map { list ->
            dealGroups(list)
        }.flowOn(IO)
    }

    fun flowExploreGroups(): Flow<List<String>> {
        return flowExploreGroupsUnProcessed().map { list ->
            dealGroups(list)
        }.flowOn(IO)
    }

    fun flowEnabledGroups(): Flow<List<String>> {
        return flowEnabledGroupsUnProcessed().map { list ->
            dealGroups(list)
        }.flowOn(IO)
    }
}
