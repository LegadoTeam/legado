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
import java.util.UUID

@Dao
interface HighlightRuleDao {

    @get:Query("SELECT * FROM highlightRules ORDER BY sortOrder ASC, id ASC")
    val all: List<HighlightRule>

    @Query("SELECT * FROM highlightRules ORDER BY sortOrder ASC, id ASC")
    fun flowAll(): Flow<List<HighlightRule>>

    @Query("SELECT * FROM highlightRules WHERE id = :id")
    fun findById(id: Long): HighlightRule?

    @Query(
        """SELECT * FROM highlightRules WHERE isEnabled = 1
        AND (scope IS NULL OR scope = ''
            OR (:name != '' AND instr(scope, :name) > 0)
            OR (:origin != '' AND instr(scope, :origin) > 0))
        ORDER BY sortOrder ASC, id ASC"""
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
    fun importRules(rules: List<HighlightRule>) {
        if (rules.isEmpty()) return
        requireUniqueHighlightRuleUuids(rules)
        val current = all
        val merged = mergeImportedHighlightRules(current, rules)
        val currentIds = current.mapTo(hashSetOf()) { it.id }
        val updates = merged.filter { it.id in currentIds }
        val inserts = merged.filter { it.id == 0L }
        if (updates.isNotEmpty()) update(*updates.toTypedArray())
        if (inserts.isNotEmpty()) insert(*inserts.toTypedArray())
    }

    @Transaction
    fun move(uuids: Set<String>, toTop: Boolean) {
        if (uuids.isEmpty()) return
        val reordered = reorderHighlightRules(all, uuids, toTop)
        if (reordered.isNotEmpty()) update(*reordered.toTypedArray())
    }

    @Transaction
    fun replaceAll(rules: List<HighlightRule>) {
        val restored = mergeImportedHighlightRules(emptyList(), rules)
        deleteAll()
        if (restored.isNotEmpty()) insert(*restored.toTypedArray())
    }
}

internal fun requireUniqueHighlightRuleUuids(rules: List<HighlightRule>) {
    val canonical = rules.map { rule ->
        val uuid = rule.uuid
        require(UUID.fromString(uuid).toString().equals(uuid, ignoreCase = true))
        uuid.lowercase()
    }
    require(canonical.distinct().size == canonical.size)
}

internal fun mergeImportedHighlightRules(
    current: List<HighlightRule>,
    imported: List<HighlightRule>
): List<HighlightRule> {
    requireUniqueHighlightRuleUuids(current)
    requireUniqueHighlightRuleUuids(imported)
    val importedByUuid = imported.associateBy { it.uuid.lowercase() }
    val currentUuids = current.mapTo(hashSetOf()) { it.uuid.lowercase() }
    val existing = current.map { local ->
        importedByUuid[local.uuid.lowercase()]?.copy(id = local.id) ?: local.copy()
    }
    val added = imported
        .filterNot { it.uuid.lowercase() in currentUuids }
        .map { it.copy(id = 0L) }
    return (existing + added).mapIndexed { index, rule -> rule.copy(order = index) }
}

internal fun reorderHighlightRules(
    rules: List<HighlightRule>,
    selectedUuids: Set<String>,
    toTop: Boolean
): List<HighlightRule> {
    val selected = selectedUuids.mapTo(hashSetOf()) { it.lowercase() }
    val (moving, staying) = rules.partition { it.uuid.lowercase() in selected }
    val ordered = if (toTop) moving + staying else staying + moving
    return ordered.mapIndexed { index, rule -> rule.copy(order = index) }
}
