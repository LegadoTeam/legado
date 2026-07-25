package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.legado.app.data.entities.HighlightRule
import kotlinx.coroutines.flow.Flow

@Dao
interface HighlightRuleDao {

    @get:Query("SELECT * FROM highlightRules ORDER BY sortOrder ASC")
    val all: List<HighlightRule>

    @Query("SELECT * FROM highlightRules ORDER BY sortOrder ASC")
    fun flowAll(): Flow<List<HighlightRule>>

    @Query("SELECT * FROM highlightRules WHERE id = :id")
    fun findById(id: Long): HighlightRule?

    @Query(
        """SELECT * FROM highlightRules WHERE isEnabled = 1
        AND (scope IS NULL OR scope = ''
            OR (:name != '' AND instr(scope, :name) > 0)
            OR (:origin != '' AND instr(scope, :origin) > 0))
        ORDER BY sortOrder ASC"""
    )
    fun findEnabledByBook(name: String, origin: String): List<HighlightRule>

    @get:Query("SELECT ifnull(min(sortOrder), 0) FROM highlightRules")
    val minOrder: Int

    @get:Query("SELECT ifnull(max(sortOrder), 0) FROM highlightRules")
    val maxOrder: Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg rule: HighlightRule): List<Long>

    @Update
    fun update(vararg rule: HighlightRule)

    @Delete
    fun delete(vararg rule: HighlightRule)

    @Query("DELETE FROM highlightRules")
    fun deleteAll()

    @Transaction
    fun replaceAll(rules: List<HighlightRule>) {
        deleteAll()
        if (rules.isNotEmpty()) insert(*rules.toTypedArray())
    }
}
