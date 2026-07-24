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
            "notify" to mapOf("enable" to true, "minCount" to 1)
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

    fun move(id: String, offset: Int, context: Context = appCtx) {
        val changed = synchronized(this) {
            all()
            val rules = appDb.autoTaskRuleDao.all().toMutableList()
            val from = rules.indexOfFirst { it.id == id }
            if (from < 0) return@synchronized false
            val to = (from + offset).coerceIn(rules.indices)
            if (from == to) return@synchronized false
            rules.add(to, rules.removeAt(from))
            rules.forEachIndexed { index, rule -> rule.customOrder = index }
            appDb.autoTaskRuleDao.update(*rules.toTypedArray())
            true
        }
        if (changed) AutoTaskScheduler.refresh(context)
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

    fun updateRunState(
        id: String,
        lastRunAt: Long,
        lastResult: String?,
        lastError: String?,
        lastLog: String?
    ) {
        appDb.autoTaskRuleDao.updateRunState(id, lastRunAt, lastResult, lastError, lastLog)
    }

}

internal fun prepareImportedAutoTasks(
    localTasks: List<AutoTaskRule>,
    importedTasks: List<AutoTaskRule>
): List<AutoTaskRule> {
    val localById = localTasks.associateBy { it.id }
    val importedById = linkedMapOf<String, AutoTaskRule>()
    var nextOrder = (localTasks.maxOfOrNull { it.customOrder } ?: -1) + 1
    importedTasks.forEach { imported ->
        val order = localById[imported.id]?.customOrder
            ?: importedById[imported.id]?.customOrder
            ?: nextOrder++
        importedById[imported.id] = imported.copy(customOrder = order)
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
