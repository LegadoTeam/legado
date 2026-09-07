package io.legado.app.ui.association

import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.RuleUpdate
import io.legado.app.model.jsSource.JsSourceConfig
import io.legado.app.utils.inputStream
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.isUri
import io.legado.app.utils.runCatchingCancellable
import io.legado.app.utils.splitNotBlank
import kotlin.coroutines.coroutineContext


internal data class ImportBookSourceStatus(
    val isNew: Boolean,
    val isUpdate: Boolean,
) {
    val shouldSelect: Boolean
        get() = isNew || isUpdate
}

internal fun resolveImportBookSourceStatus(
    importedLastUpdateTime: Long,
    localLastUpdateTime: Long?,
): ImportBookSourceStatus {
    return ImportBookSourceStatus(
        isNew = localLastUpdateTime == null,
        isUpdate = localLastUpdateTime != null && localLastUpdateTime < importedLastUpdateTime,
    )
}

internal fun resolveImportSourceSelection(
    status: ImportBookSourceStatus,
    manualSelection: Boolean?,
): Boolean {
    return manualSelection ?: status.shouldSelect
}

class ImportBookSourceViewModel(app: Application) : BaseViewModel(app) {
    var isAddGroup = false
    var groupName: String? = null
    val errorLiveData = MutableLiveData<String>()
    val successLiveData = MutableLiveData<Int>()
    val sourceUpdatePending = MutableLiveData(false)

    val allSources = arrayListOf<BookSource>()
    private val sourceCandidates = arrayListOf<BookSourceImportCandidate>()
    val checkSources = arrayListOf<BookSourcePart?>()
    val selectStatus = arrayListOf<Boolean>()
    val newSourceStatus = arrayListOf<Boolean>()
    val updateSourceStatus = arrayListOf<Boolean>()
    private val manualSelections = arrayListOf<Boolean?>()
    private var importStarted = false
    var useSourceReplacement = AppConfig.importReplaceSource
        private set

    val isSelectAll: Boolean
        get() {
            selectStatus.forEachIndexed { index, selected ->
                if (canImportSource(index) && !selected) {
                    return false
                }
            }
            return true
        }

    val isSelectAllNew: Boolean
        get() {
            newSourceStatus.forEachIndexed { index, b ->
                if (b && canImportSource(index) && !selectStatus[index]) {
                    return false
                }
            }
            return true
        }

    val isSelectAllUpdate: Boolean
        get() {
            updateSourceStatus.forEachIndexed { index, b ->
                if (b && canImportSource(index) && !selectStatus[index]) {
                    return false
                }
            }
            return true
        }

    val selectCount: Int
        get() {
            var count = 0
            selectStatus.forEach {
                if (it) {
                    count++
                }
            }
            return count
        }

    fun importSelect(finally: () -> Unit) {
        execute {
            val group = groupName?.trim()
            val keepName = AppConfig.importKeepName
            val keepGroup = AppConfig.importKeepGroup
            val keepEnable = AppConfig.importKeepEnable
            val selectSource = arrayListOf<BookSource>()
            selectStatus.forEachIndexed { index, b ->
                if (b && canImportSource(index)) {
                    val source = allSources[index]
                    checkSources[index]?.let {
                        if (keepName) {
                            source.bookSourceName = it.bookSourceName
                        }
                        if (keepGroup) {
                            source.bookSourceGroup = it.bookSourceGroup
                        }
                        if (keepEnable) {
                            source.enabled = it.enabled
                            source.enabledExplore = it.enabledExplore
                        }
                        source.customOrder = it.customOrder
                    }
                    if (!group.isNullOrEmpty()) {
                        if (isAddGroup) {
                            val groups = linkedSetOf<String>()
                            source.bookSourceGroup?.splitNotBlank(AppPattern.splitGroupRegex)?.let {
                                groups.addAll(it)
                            }
                            groups.add(group)
                            source.bookSourceGroup = groups.joinToString(",")
                        } else {
                            source.bookSourceGroup = group
                        }
                    }
                    selectSource.add(source)
                }
            }
            SourceHelp.insertBookSource(*selectSource.toTypedArray())
            ContentProcessor.upReplaceRules()
        }.onFinally {
            finally.invoke()
        }
    }

    fun importSource(text: String) {
        if (importStarted) return
        importStarted = true
        executeLazy {
            val mText = text.trim()
            when {
                mText.isJsonObject() || mText.isJsonArray() ->
                    importBookSourceJson(parseBookSourceJson(mText))

                mText.isAbsUrl() -> {
                    importSourceUrl(mText)
                }

                mText.isUri() -> {
                    val uri = Uri.parse(mText)
                    uri.inputStream(context).getOrThrow().use { inputS ->
                        importSourceText(inputS.bufferedReader().readText())
                    }
                }

                else -> runCatchingCancellable {
                    allSources.add(JsSourceConfig.extract(mText, coroutineContext))
                }.getOrElse {
                    throw NoStackTraceException(
                        "${context.getString(R.string.wrong_format)}\n${it.localizedMessage}"
                    )
                }
            }
        }.onError {
            errorLiveData.postValue("ImportError:${it.localizedMessage}")
            AppLog.put("ImportError:${it.localizedMessage}", it)
        }.onSuccess {
            prepareSourceCandidates()
        }.start()
    }

    private fun prepareSourceCandidates() {
        executeLazy {
            val rules = appDb.replaceRuleDao.findEnabledBySourceScope()
            allSources.map { prepareBookSourceImportCandidate(it, rules) }
        }.onSuccess { candidates ->
            sourceCandidates.clear()
            sourceCandidates.addAll(candidates)
            applyCandidateSources()
            comparisonSource()
        }.onError {
            errorLiveData.value = "ImportError:${it.localizedMessage}"
            AppLog.put("ImportError:${it.localizedMessage}", it)
        }.start()
    }

    private fun applyCandidateSources() {
        allSources.clear()
        allSources.addAll(sourceCandidates.map { it.source(useSourceReplacement) })
    }

    fun setUseSourceReplacement(enabled: Boolean) {
        if (enabled == useSourceReplacement || sourceUpdatePending.value == true) return
        val previousMode = useSourceReplacement
        useSourceReplacement = enabled
        AppConfig.importReplaceSource = enabled
        applyCandidateSources()
        sourceUpdatePending.value = true
        comparisonSource(
            preserveManualSelections = true,
            onError = {
                useSourceReplacement = previousMode
                AppConfig.importReplaceSource = previousMode
                applyCandidateSources()
            },
        ) {
            sourceUpdatePending.value = false
        }
    }

    private suspend fun importSourceUrl(url: String) {
        RuleUpdate.cacheBookSourceMap[url]?.also {
            allSources.addAll(it)
            RuleUpdate.cacheBookSourceMap.remove(url)
            return
        }
        okHttpClient.newCallResponseBody {
            if (url.endsWith("#requestWithoutUA")) {
                url(url.substringBeforeLast("#requestWithoutUA"))
                header(AppConst.UA_NAME, "null")
            } else {
                url(url)
            }
        }.decompressed().byteStream().use {
            importSourceText(it.bufferedReader().readText())
        }
    }

    private suspend fun importSourceText(text: String) {
        val content = text.trim()
        when {
            content.isJsonArray() || content.isJsonObject() ->
                importBookSourceJson(parseBookSourceJson(content, allowSourceUrls = false))

            else -> allSources.add(JsSourceConfig.extract(content, coroutineContext))
        }
    }

    private suspend fun importBookSourceJson(importJson: BookSourceImportJson) {
        when (importJson) {
            is BookSourceImportJson.Sources -> allSources.addAll(importJson.items)
            is BookSourceImportJson.SourceUrls -> importJson.items.forEach {
                importSourceUrl(it)
            }
        }
    }

    private fun comparisonSource(
        preserveManualSelections: Boolean = false,
        onError: () -> Unit = {},
        finally: () -> Unit = {},
    ) {
        val savedManualSelections = manualSelections.toList()
        executeLazy {
            allSources.map { source ->
                val localSource = appDb.bookSourceDao.getBookSourcePart(source.bookSourceUrl)
                val status = resolveImportBookSourceStatus(
                    source.lastUpdateTime,
                    localSource?.lastUpdateTime,
                )
                localSource to status
            }
        }.onSuccess { comparisons ->
            checkSources.clear()
            selectStatus.clear()
            newSourceStatus.clear()
            updateSourceStatus.clear()
            manualSelections.clear()
            comparisons.forEachIndexed { index, (localSource, status) ->
                val manualSelection = if (preserveManualSelections) {
                    savedManualSelections.getOrNull(index)
                } else {
                    null
                }
                checkSources.add(localSource)
                selectStatus.add(
                    canImportSource(index) &&
                        resolveImportSourceSelection(status, manualSelection)
                )
                newSourceStatus.add(status.isNew)
                updateSourceStatus.add(status.isUpdate)
                manualSelections.add(manualSelection)
            }
            successLiveData.value = allSources.size
        }.onError {
            onError()
            errorLiveData.value = "ImportError:${it.localizedMessage}"
            AppLog.put("ImportError:${it.localizedMessage}", it)
        }.onFinally {
            finally()
        }.start()
    }

    fun setSelection(index: Int, selected: Boolean) {
        if (index !in selectStatus.indices || index !in manualSelections.indices) return
        if (sourceUpdatePending.value == true || !canImportSource(index)) return
        selectStatus[index] = selected
        manualSelections[index] = selected
    }

    fun updateSource(index: Int, source: BookSource) {
        if (sourceUpdatePending.value == true) return
        sourceUpdatePending.value = true
        executeLazy {
            val rules = appDb.replaceRuleDao.findEnabledBySourceScope()
            val candidate = prepareBookSourceImportCandidate(source, rules)
            val activeSource = candidate.source(useSourceReplacement)
            val localSource = appDb.bookSourceDao.getBookSourcePart(activeSource.bookSourceUrl)
            val editedStatus = resolveImportBookSourceStatus(
                activeSource.lastUpdateTime,
                localSource?.lastUpdateTime,
            )
            Triple(candidate, localSource, editedStatus)
        }.onSuccess { (candidate, localSource, editedStatus) ->
            if (index !in allSources.indices) return@onSuccess
            sourceCandidates[index] = candidate
            allSources[index] = candidate.source(useSourceReplacement)
            checkSources[index] = localSource
            selectStatus[index] = canImportSource(index) &&
                resolveImportSourceSelection(editedStatus, manualSelections[index])
            newSourceStatus[index] = editedStatus.isNew
            updateSourceStatus[index] = editedStatus.isUpdate
            successLiveData.value = allSources.size
        }.onError {
            errorLiveData.value = "ImportError:${it.localizedMessage}"
            AppLog.put("ImportError:${it.localizedMessage}", it)
        }.onFinally {
            sourceUpdatePending.value = false
        }.start()
    }

    fun refreshSourceReplacements(index: Int, source: BookSource?): Boolean {
        if (sourceUpdatePending.value == true || index !in sourceCandidates.indices) return false
        val previousCandidates = sourceCandidates.toList()
        sourceUpdatePending.value = true
        executeLazy {
            refreshBookSourceImportCandidates(
                previousCandidates,
                index,
                source,
                appDb.replaceRuleDao.findEnabledBySourceScope(),
            )
        }.onSuccess { candidates ->
            sourceCandidates.clear()
            sourceCandidates.addAll(candidates)
            applyCandidateSources()
            comparisonSource(
                preserveManualSelections = true,
                onError = {
                    sourceCandidates.clear()
                    sourceCandidates.addAll(previousCandidates)
                    applyCandidateSources()
                },
            ) {
                sourceUpdatePending.value = false
            }
        }.onError {
            errorLiveData.value = "ImportError:${it.localizedMessage}"
            AppLog.put("ImportError:${it.localizedMessage}", it)
            sourceUpdatePending.value = false
        }.start()
        return true
    }

    fun canImportSource(index: Int): Boolean =
        sourceCandidates.getOrNull(index)?.canImport(useSourceReplacement) != false

    fun originalSourceJson(index: Int): String? =
        sourceCandidates.getOrNull(index)?.originalJson

    fun replacedSourceJson(index: Int): String? =
        sourceCandidates.getOrNull(index)?.replacedJson

    fun sourceReplacementError(index: Int): String? =
        sourceCandidates.getOrNull(index)?.replacementError

}
