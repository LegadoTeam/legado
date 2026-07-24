package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.utils.cnCompare
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private const val REPLACE_RULE_GROUP_FILTER = """
trim(:groupName, $GROUP_TRIM_CHARACTERS) <> ''
and exists (
    with recursive replace_rule_groups(group_name, rest) as (
        select '',
            replace(replace(replace(coalesce(t2.`group`, ''), ';', ','), '，', ','), '；', ',') || ','
        union all
        select
            trim(substr(rest, 1, instr(rest, ',') - 1), $GROUP_TRIM_CHARACTERS),
            substr(rest, instr(rest, ',') + 1)
        from replace_rule_groups
        where rest <> ''
    )
    select 1
    from replace_rule_groups
    where group_name = trim(:groupName, $GROUP_TRIM_CHARACTERS)
)
"""

private const val REPLACE_RULE_NO_GROUP_FILTER = """
trim(coalesce(`group`, ''), $GROUP_TRIM_CHARACTERS) in ('', '未分组')
"""

@Dao
interface ReplaceRuleDao {

    @Query("SELECT * FROM replace_rules ORDER BY sortOrder ASC")
    fun flowAll(): Flow<List<ReplaceRule>>

    @Query("SELECT * FROM replace_rules where `group` like :key or name like :key ORDER BY sortOrder ASC")
    fun flowSearch(key: String): Flow<List<ReplaceRule>>

    @Query("SELECT * FROM replace_rules WHERE isEnabled = 1 ORDER BY sortOrder ASC")
    fun flowEnabled(): Flow<List<ReplaceRule>>

    @Query("SELECT * FROM replace_rules WHERE isEnabled = 0 ORDER BY sortOrder ASC")
    fun flowDisabled(): Flow<List<ReplaceRule>>

    @Query(
        """SELECT t2.* FROM replace_rules AS t2
        WHERE """ + REPLACE_RULE_GROUP_FILTER + """
        ORDER BY t2.sortOrder ASC"""
    )
    fun flowGroupSearch(groupName: String): Flow<List<ReplaceRule>>

    @Query("select `group` from replace_rules where `group` is not null and `group` <> ''")
    fun flowGroupsUnProcessed(): Flow<List<String>>

    @Query(
        """select * from replace_rules
        where """ + REPLACE_RULE_NO_GROUP_FILTER
    )
    fun flowNoGroup(): Flow<List<ReplaceRule>>

    @get:Query("SELECT MIN(sortOrder) FROM replace_rules")
    val minOrder: Int

    @get:Query("SELECT MAX(sortOrder) FROM replace_rules")
    val maxOrder: Int

    @get:Query("SELECT * FROM replace_rules ORDER BY sortOrder ASC")
    val all: List<ReplaceRule>

    @get:Query("select distinct `group` from replace_rules where trim(`group`) <> ''")
    val allGroupsUnProcessed: List<String>

    @get:Query("SELECT * FROM replace_rules WHERE isEnabled = 1 ORDER BY sortOrder ASC")
    val allEnabled: List<ReplaceRule>

    @Query("SELECT * FROM replace_rules WHERE id = :id")
    fun findById(id: Long): ReplaceRule?

    @Query("SELECT * FROM replace_rules WHERE id in (:ids)")
    fun findByIds(vararg ids: Long): List<ReplaceRule>

    @Query(
        """SELECT * FROM replace_rules WHERE isEnabled = 1 and scopeContent = 1
        AND (scope LIKE '%' || :name || '%' or scope LIKE '%' || :origin || '%' or scope is null or scope = '')
        and (excludeScope is null or (excludeScope not LIKE '%' || :name || '%' and excludeScope not LIKE '%' || :origin || '%'))
        order by sortOrder"""
    )
    fun findEnabledByContentScope(name: String, origin: String): List<ReplaceRule>

    @Query(
        """SELECT * FROM replace_rules WHERE isEnabled = 1 and scopeTitle = 1
        AND (scope LIKE '%' || :name || '%' or scope LIKE '%' || :origin || '%' or scope is null or scope = '')
        and (excludeScope is null or (excludeScope not LIKE '%' || :name || '%' and excludeScope not LIKE '%' || :origin || '%'))
        order by sortOrder"""
    )
    fun findEnabledByTitleScope(name: String, origin: String): List<ReplaceRule>

    @Query("select * from replace_rules where `group` like '%' || :group || '%'")
    fun getByGroup(group: String): List<ReplaceRule>

    @get:Query("select * from replace_rules where `group` is null or `group` = ''")
    val noGroup: List<ReplaceRule>

    @get:Query("SELECT COUNT(*) - SUM(isEnabled) FROM replace_rules")
    val summary: Int

    @Query("UPDATE replace_rules SET isEnabled = :enable")
    fun enableAll(enable: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg replaceRule: ReplaceRule): List<Long>

    @Update
    fun update(vararg replaceRules: ReplaceRule)

    @Delete
    fun delete(vararg replaceRules: ReplaceRule)

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

    fun flowGroups(): Flow<List<String>> {
        return flowGroupsUnProcessed().map { list ->
            dealGroups(list)
        }.flowOn(IO)
    }
}
