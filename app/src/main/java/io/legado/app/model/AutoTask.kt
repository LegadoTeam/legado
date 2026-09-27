package io.legado.app.model

import android.content.Context
import io.legado.app.data.appDb
import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.CacheManager
import io.legado.app.service.AutoTaskScheduler
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.mergeFilteredOrder
import splitties.init.appCtx

object AutoTask {

    const val SOURCE_KEY = "auto_task"
    const val DEFAULT_CRON = "*/30 * * * *"
    internal const val BOOK_UPDATE_GENERATOR = "bookUpdate"
    private const val LEGACY_RULES_KEY = "autoTaskRules"
    private val legacyRulesLoader = LegacyAutoTaskRulesLoader()

    fun normalizeScript(script: String): String {
        val trimmed = script.trim()
        return when {
            trimmed.startsWith("@js:", true) -> trimmed.substring(4).trim()
            trimmed.startsWith("<js>", true) && trimmed.endsWith("</js>", true) ->
                trimmed.substring(4, trimmed.length - 5).trim()
            else -> trimmed
        }
    }

    internal fun buildBookUpdateTask(book: Book, name: String): AutoTaskRule {
        val action = mapOf(
            "type" to "refreshToc",
            "bookUrl" to book.bookUrl,
            "bookName" to book.name,
            "bookAuthor" to book.author,
            "generatedBy" to BOOK_UPDATE_GENERATOR,
            "respectCanUpdate" to true,
            "notify" to mapOf("enable" to true, "minCount" to 1),
            "cache" to mapOf("enable" to false)
        )
        return AutoTaskRule(
            id = bookUpdateTaskId(book.bookUrl),
            name = name,
            cron = DEFAULT_CRON,
            script = "(${GSON.toJson(action)})"
        )
    }

    internal fun findBookUpdateTask(
        tasks: List<AutoTaskRule>,
        book: Book
    ): AutoTaskRule? {
        tasks.firstOrNull { it.id == bookUpdateTaskId(book.bookUrl) }?.let { return it }
        val sameBook = tasks.mapNotNull { task ->
            generatedBookIdentity(task)?.let { task to it }
        }.filter { it.second == (book.name to book.author) }
        return sameBook.singleOrNull()?.first
    }

    internal fun buildBookUpdateTasks(
        books: List<Book>,
        existingTasks: List<AutoTaskRule>,
        cron: String,
        nameOf: (Book) -> String
    ): List<AutoTaskRule> {
        val generated = books.map { it to buildBookUpdateTask(it, nameOf(it)) }
        val existingById = existingTasks.associateBy { it.id }
        val generatedIds = generated.mapTo(hashSetOf()) { it.second.id }
        val movedTasks = existingTasks.filterNot { it.id in generatedIds }.toMutableList()
        return generated.map { (book, task) ->
            val existing = existingById[task.id]
                ?: findBookUpdateTask(movedTasks, book)?.also { movedTasks.remove(it) }
            task.copy(
                id = existing?.id ?: task.id,
                enable = existing?.enable ?: task.enable,
                cron = cron
            )
        }
    }

    private fun bookUpdateTaskId(bookUrl: String): String {
        return "book_update:${MD5Utils.md5Encode16(bookUrl)}"
    }

    private fun generatedBookIdentity(task: AutoTaskRule): Pair<String, String>? {
        if (!task.id.startsWith("book_update:")) return null
        val script = normalizeScript(task.script)
        if (!script.startsWith('(') || !script.endsWith(')')) return null
        val action = GSON.fromJsonObject<Map<String, Any?>>(
            script.substring(1, script.length - 1)
        ).getOrNull() ?: return null
        if (action["generatedBy"] != BOOK_UPDATE_GENERATOR) return null
        val name = action["bookName"] as? String ?: return null
        return name to ((action["bookAuthor"] as? String).orEmpty())
    }

    fun buildSource(task: AutoTaskRule): BookSource {
        return BookSource(
            bookSourceUrl = "$SOURCE_KEY:${task.id}",
            bookSourceName = task.name
        ).apply {
            loginUrl = task.loginUrl
            loginUi = task.loginUi
            loginCheckJs = task.loginCheckJs
            header = task.header
            jsLib = task.jsLib
            concurrentRate = task.concurrentRate
            enabledCookieJar = task.enabledCookieJar
        }
    }

    @Synchronized
    fun all(): List<AutoTaskRule> {
        val rules = appDb.autoTaskRuleDao.all()
        return legacyRulesLoader.load(
            existingRules = rules,
            read = { CacheManager.get(LEGACY_RULES_KEY) },
            persist = { legacyRules ->
                appDb.autoTaskRuleDao.upsert(*legacyRules.toTypedArray())
            },
            clear = { CacheManager.delete(LEGACY_RULES_KEY) }
        )
    }

    fun enabled(): List<AutoTaskRule> = all().filter { it.enable }

    fun exportJson(rules: List<AutoTaskRule> = all()): String {
        val json = GSON.toJsonTree(rules).asJsonArray
        json.forEach { task ->
            task.asJsonObject.apply {
                remove("customOrder")
                remove("lastRunAt")
                remove("lastResult")
                remove("lastError")
                remove("lastLog")
            }
        }
        return GSON.toJson(json)
    }

    @Synchronized
    fun get(id: String): AutoTaskRule? {
        all()
        return appDb.autoTaskRuleDao.getById(id)
    }

    fun upsert(rule: AutoTaskRule, context: Context = appCtx): AutoTaskRule {
        val saved = synchronized(this) {
            all()
            val existing = appDb.autoTaskRuleDao.getById(rule.id)
            val value = if (existing == null) {
                rule.copy(customOrder = appDb.autoTaskRuleDao.maxOrder() + 1)
            } else {
                rule.copy(customOrder = existing.customOrder)
            }
            appDb.autoTaskRuleDao.upsert(value)
            value
        }
        AutoTaskScheduler.refresh(context)
        return saved
    }

    fun importRules(
        rules: List<AutoTaskRule>,
        context: Context = appCtx
    ): List<AutoTaskRule> {
        if (rules.isEmpty()) return emptyList()
        val saved = synchronized(this) {
            val imported = prepareImportedAutoTasks(all(), rules)
            appDb.autoTaskRuleDao.upsert(*imported.toTypedArray())
            imported
        }
        AutoTaskScheduler.refresh(context)
        return saved
    }

    fun delete(ids: Collection<String>, context: Context = appCtx) {
        if (ids.isEmpty()) return
        synchronized(this) {
            all()
            appDb.autoTaskRuleDao.deleteByIds(ids)
        }
        AutoTaskScheduler.refresh(context)
    }

    fun reorder(orderedIds: List<String>, context: Context = appCtx) {
        val changed = synchronized(this) {
            all()
            val rules = appDb.autoTaskRuleDao.all()
            val reordered = mergeAutoTaskOrder(rules, orderedIds)
            if (rules.indices.all { rules[it].id == reordered[it].id }) {
                return@synchronized false
            }
            reordered.forEachIndexed { index, rule -> rule.customOrder = index }
            appDb.autoTaskRuleDao.update(*reordered.toTypedArray())
            true
        }
        if (changed) AutoTaskScheduler.refresh(context)
    }

    fun updateEnabled(ids: Collection<String>, enabled: Boolean, context: Context = appCtx): Int {
        if (ids.isEmpty()) return 0
        val changed = synchronized(this) {
            all()
            ids.chunked(900).sumOf { appDb.autoTaskRuleDao.updateEnabled(it, enabled) }
        }
        if (changed > 0) AutoTaskScheduler.refresh(context)
        return changed
    }

    fun updateCron(ids: Collection<String>, cron: String, context: Context = appCtx): Int {
        if (ids.isEmpty()) return 0
        val changed = synchronized(this) {
            all()
            ids.chunked(900).sumOf { appDb.autoTaskRuleDao.updateCron(it, cron) }
        }
        if (changed > 0) AutoTaskScheduler.refresh(context)
        return changed
    }

    fun clearRunLog(id: String) = synchronized(this) {
        appDb.autoTaskRuleDao.clearRunLog(id)
    }

    fun updateRunState(
        id: String,
        lastRunAt: Long,
        lastResult: String?,
        lastError: String?,
        lastLog: String?
    ) = synchronized(this) {
        appDb.autoTaskRuleDao.updateRunState(id, lastRunAt, lastResult, lastError, lastLog)
    }

}

internal fun mergeAutoTaskOrder(
    allRules: List<AutoTaskRule>,
    orderedIds: List<String>
): List<AutoTaskRule> {
    val rulesById = allRules.associateBy { it.id }
    val orderedRules = orderedIds.mapNotNull(rulesById::get)
    return mergeFilteredOrder(allRules, orderedRules) { it.id }
}

internal fun prepareImportedAutoTasks(
    localTasks: List<AutoTaskRule>,
    importedTasks: List<AutoTaskRule>
): List<AutoTaskRule> {
    val localById = localTasks.associateBy { it.id }
    val importedById = linkedMapOf<String, AutoTaskRule>()
    var nextOrder = (localTasks.maxOfOrNull { it.customOrder } ?: -1) + 1
    importedTasks.forEach { imported ->
        val local = localById[imported.id]
        val state = local ?: imported
        val order = local?.customOrder
            ?: importedById[imported.id]?.customOrder
            ?: nextOrder++
        importedById[imported.id] = imported.copy(
            customOrder = order,
            lastRunAt = state.lastRunAt,
            lastResult = state.lastResult,
            lastError = state.lastError,
            lastLog = state.lastLog
        )
    }
    return importedById.values.toList()
}

internal class LegacyAutoTaskRulesLoader {

    private var checked = false

    @Synchronized
    fun load(
        existingRules: List<AutoTaskRule>,
        read: () -> String?,
        persist: (List<AutoTaskRule>) -> Unit,
        clear: () -> Unit
    ): List<AutoTaskRule> {
        if (checked) return existingRules
        val rules = if (existingRules.isNotEmpty()) {
            clear()
            existingRules
        } else {
            migrateLegacyAutoTaskRules(read, persist, clear)
        }
        checked = true
        return rules
    }
}

internal fun migrateLegacyAutoTaskRules(
    read: () -> String?,
    persist: (List<AutoTaskRule>) -> Unit,
    clear: () -> Unit
): List<AutoTaskRule> {
    val json = read() ?: return emptyList()
    val parsed = GSON.fromJsonArray<AutoTaskRule>(json).getOrNull() ?: return emptyList()
    val rules = parsed.mapIndexed { index, rule -> rule.copy(customOrder = index) }
    if (rules.isNotEmpty()) persist(rules)
    clear()
    return rules
}
