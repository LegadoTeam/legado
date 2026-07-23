package io.legado.app.ui.autoTask

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.model.AutoTask
import io.legado.app.utils.CronSchedule
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.isUri
import io.legado.app.utils.readText
import splitties.init.appCtx

class ImportAutoTaskViewModel(app: Application) : BaseViewModel(app) {

    val errorLiveData = MutableLiveData<String>()
    val successLiveData = MutableLiveData<Int>()

    val allTasks = arrayListOf<AutoTaskRule>()
    val checkTasks = arrayListOf<AutoTaskRule?>()
    val selectStatus = arrayListOf<Boolean>()
    @Volatile
    private var localTasksById: Map<String, AutoTaskRule> = emptyMap()
    private var importStarted = false

    val isSelectAll: Boolean
        get() = selectStatus.all { it }

    val selectCount: Int
        get() = selectStatus.count { it }

    fun importSelect(finally: () -> Unit) {
        execute {
            val selected = selectStatus.mapIndexedNotNull { index, selected ->
                if (selected) allTasks.getOrNull(index) else null
            }.map(::validateImportedTask)
            AutoTask.importRules(selected, context)
        }.onError {
            errorLiveData.postValue("ImportError:${it.localizedMessage}")
            AppLog.put("ImportError:${it.localizedMessage}", it)
        }.onSuccess {
            finally.invoke()
        }
    }

    fun importSource(text: String?) {
        if (importStarted) return
        val sourceText = text?.trim().orEmpty()
        if (sourceText.isBlank()) {
            errorLiveData.postValue("ImportError:${context.getString(R.string.wrong_format)}")
            return
        }
        importStarted = true
        allTasks.clear()
        checkTasks.clear()
        selectStatus.clear()
        execute {
            importSourceAwait(sourceText)
        }.onError {
            importStarted = false
            allTasks.clear()
            checkTasks.clear()
            selectStatus.clear()
            errorLiveData.postValue("ImportError:${it.localizedMessage}")
            AppLog.put("ImportError:${it.localizedMessage}", it)
        }.onSuccess {
            comparisonSource()
        }
    }

    fun updateTaskAt(index: Int, task: AutoTaskRule): AutoTaskRule? {
        if (index !in allTasks.indices) return null
        val validatedTask = try {
            validateImportedTask(task)
        } catch (e: Exception) {
            errorLiveData.value = "ImportError:${e.localizedMessage ?: context.getString(R.string.wrong_format)}"
            return null
        }
        allTasks[index] = validatedTask
        val localTask = localTasksById[validatedTask.id]
        if (index < checkTasks.size) checkTasks[index] = localTask
        if (index < selectStatus.size) {
            selectStatus[index] = localTask == null || !sameAutoTaskForImport(validatedTask, localTask)
        }
        return validatedTask
    }

    private suspend fun importSourceAwait(text: String) {
        when {
            text.isJsonObject() -> allTasks.add(
                validateImportedTask(GSON.fromJsonObject<AutoTaskRule>(text).getOrThrow())
            )
            text.isJsonArray() -> allTasks.addAll(
                GSON.fromJsonArray<AutoTaskRule>(text).getOrThrow().map(::validateImportedTask)
            )
            text.isAbsUrl() -> importSourceUrl(text)
            text.isUri() -> importSourceAwait(text.toUri().readText(appCtx))
            else -> throw NoStackTraceException(context.getString(R.string.wrong_format))
        }
    }

    private suspend fun importSourceUrl(url: String) {
        okHttpClient.newCallResponseBody {
            if (url.endsWith("#requestWithoutUA")) {
                url(url.substringBeforeLast("#requestWithoutUA"))
                header(AppConst.UA_NAME, "null")
            } else {
                url(url)
            }
        }.decompressed().text().let { importSourceAwait(it) }
    }

    private fun validateImportedTask(task: AutoTaskRule): AutoTaskRule {
        val normalized = task.copy(
            name = task.name.orEmpty().trim(),
            cron = task.cron?.trim().orEmpty().ifBlank { AutoTask.DEFAULT_CRON },
            script = task.script.orEmpty()
        )
        if (normalized.name.isBlank()) {
            throw NoStackTraceException(context.getString(R.string.auto_task_name_required))
        }
        if (CronSchedule.parse(normalized.cron.orEmpty()) == null) {
            throw NoStackTraceException(context.getString(R.string.auto_task_cron_invalid))
        }
        if (AutoTask.normalizeScript(normalized.script).isBlank()) {
            throw NoStackTraceException(context.getString(R.string.auto_task_script_empty))
        }
        return normalized
    }

    private fun comparisonSource() {
        execute {
            val localMap = AutoTask.all().associateBy { it.id }
            localTasksById = localMap
            allTasks.forEach { task ->
                val local = localMap[task.id]
                checkTasks.add(local)
                selectStatus.add(local == null || !sameAutoTaskForImport(task, local))
            }
            successLiveData.postValue(allTasks.size)
        }
    }
}

internal fun sameAutoTaskForImport(left: AutoTaskRule, right: AutoTaskRule): Boolean {
    return left.copy(customOrder = 0) == right.copy(customOrder = 0)
}
