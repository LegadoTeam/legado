package io.legado.app.ui.association

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.MutableLiveData
import com.google.gson.JsonObject
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.HighlightRuleFile
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import io.legado.app.utils.GSONStrict
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.readText
import splitties.init.appCtx
import java.util.UUID

internal enum class HighlightRuleImportStatus {
    NEW,
    UPDATE,
    EXISTING
}

internal data class HighlightRuleImportItem(
    val rule: HighlightRule,
    val status: HighlightRuleImportStatus
)

internal fun parseHighlightRuleFile(text: String): List<HighlightRule> {
    val root = GSONStrict.fromJsonObject<JsonObject>(text).getOrThrow()
    val rules = root.get("rules")
    require(rules != null && rules.isJsonArray)
    rules.asJsonArray.forEach { element ->
        require(element.isJsonObject)
        val rule = element.asJsonObject
        val uuid = rule.get("uuid")
        require(
            uuid != null && !uuid.isJsonNull &&
                uuid.isJsonPrimitive && uuid.asJsonPrimitive.isString
        )
        listOf("name", "pattern", "style").forEach { field ->
            rule.get(field)?.let { value ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
            }
        }
        rule.get("scope")?.let { value ->
            require(
                value.isJsonNull ||
                    value.isJsonPrimitive && value.asJsonPrimitive.isString
            )
        }
        listOf("isRegex", "isEnabled", "applyToTitle", "applyToBody").forEach { field ->
            rule.get(field)?.let { value ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
            }
        }
        listOf("id", "timeoutMillisecond").forEach { field ->
            rule.get(field)?.let { value ->
                require(
                    value.isJsonPrimitive && value.asJsonPrimitive.isNumber &&
                        runCatching { value.asBigDecimal.longValueExact() }.isSuccess
                )
            }
        }
        rule.get("order")?.let { value ->
            require(
                value.isJsonPrimitive && value.asJsonPrimitive.isNumber &&
                    runCatching { value.asBigDecimal.intValueExact() }.isSuccess
            )
        }
    }
    return validateHighlightRuleFile(
        GSONStrict.fromJson(root, HighlightRuleFile::class.java)
    )
}

internal fun validateHighlightRuleFile(file: HighlightRuleFile): List<HighlightRule> {
    require(file.type == HighlightRuleFile.TYPE)
    val rules = file.rules ?: error("Missing rules")
    val uuids = hashSetOf<String>()
    return rules.map { nullableRule ->
        val rule = nullableRule ?: error("Invalid rule")
        @Suppress("USELESS_CAST")
        val rawUuid = (rule.uuid as String?).orEmpty()
        val uuid = UUID.fromString(rawUuid).toString()
        require(uuid.equals(rawUuid, ignoreCase = true))
        require(uuids.add(uuid))
        rule.uuid = uuid
        rule.scope = rule.scope?.ifBlank { null }
        rule.normalizeForRestore()
        require(rule.isValid())
        rule
    }
}

internal fun compareImportedHighlightRules(
    imported: List<HighlightRule>,
    local: List<HighlightRule>
): List<HighlightRuleImportItem> {
    val localByUuid = local.associateBy { it.uuid.lowercase() }
    return imported.map { rule ->
        val existing = localByUuid[rule.uuid.lowercase()]
        val status = when {
            existing == null -> HighlightRuleImportStatus.NEW
            GSON.toJsonTree(existing.copy(id = 0L, order = 0)) ==
                GSON.toJsonTree(rule.copy(id = 0L, order = 0)) ->
                HighlightRuleImportStatus.EXISTING
            else -> HighlightRuleImportStatus.UPDATE
        }
        HighlightRuleImportItem(rule, status)
    }
}

class ImportHighlightRuleViewModel(app: Application) : BaseViewModel(app) {

    val errorLiveData = MutableLiveData<String>()
    val successLiveData = MutableLiveData<Int>()
    val importingLiveData = MutableLiveData(false)
    val importSuccessLiveData = MutableLiveData(false)
    internal val items = arrayListOf<HighlightRuleImportItem>()
    val selectStatus = arrayListOf<Boolean>()
    private var loadStarted = false

    val isSelectAll: Boolean
        get() = selectStatus.isNotEmpty() && selectStatus.all { it }

    val selectCount: Int
        get() = selectStatus.count { it }

    fun load(source: String) {
        if (loadStarted) return
        loadStarted = true
        execute {
            val text = source.toUri().readText(appCtx)
            val comparison = compareImportedHighlightRules(
                parseHighlightRuleFile(text),
                appDb.highlightRuleDao.all
            )
            items.clear()
            items.addAll(comparison)
            selectStatus.clear()
            selectStatus.addAll(comparison.map { it.status != HighlightRuleImportStatus.EXISTING })
        }.onError {
            val message = context.getString(R.string.wrong_format)
            errorLiveData.postValue(message)
            AppLog.put("ImportHighlightRuleError:${it.localizedMessage}", it)
        }.onSuccess {
            successLiveData.postValue(items.size)
        }
    }

    fun importSelected() {
        if (importingLiveData.value == true) return
        val selected = items.mapIndexedNotNull { index, item ->
            item.rule.takeIf { selectStatus[index] }
        }
        if (selected.isEmpty()) return
        importingLiveData.value = true
        execute {
            appDb.highlightRuleDao.importRules(selected)
        }.onError {
            errorLiveData.postValue(
                it.localizedMessage ?: context.getString(R.string.unknown_error)
            )
            importingLiveData.postValue(false)
        }.onSuccess {
            ReadBook.upHighlightRules()
            importSuccessLiveData.postValue(true)
        }
    }
}
