package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import io.legado.app.data.entities.AutoTaskRule
import kotlinx.coroutines.flow.Flow

@Dao
interface AutoTaskRuleDao {

    @Query("SELECT * FROM auto_task_rules ORDER BY customOrder, name COLLATE NOCASE, id")
    fun all(): List<AutoTaskRule>

    @Query("SELECT * FROM auto_task_rules ORDER BY customOrder, name COLLATE NOCASE, id")
    fun flowAll(): Flow<List<AutoTaskRule>>

    @Query("SELECT * FROM auto_task_rules WHERE id = :id")
    fun getById(id: String): AutoTaskRule?

    @Query("SELECT COALESCE(MAX(customOrder), -1) FROM auto_task_rules")
    fun maxOrder(): Int

    @Upsert
    fun upsert(vararg rules: AutoTaskRule)

    @Update
    fun update(vararg rules: AutoTaskRule)

    @Query("DELETE FROM auto_task_rules WHERE id IN (:ids)")
    fun deleteByIds(ids: Collection<String>)

    @Query(
        """
        UPDATE auto_task_rules
        SET lastRunAt = :lastRunAt,
            lastResult = :lastResult,
            lastError = :lastError,
            lastLog = :lastLog
        WHERE id = :id
        """
    )
    fun updateRunState(
        id: String,
        lastRunAt: Long,
        lastResult: String?,
        lastError: String?,
        lastLog: String?
    )
}
