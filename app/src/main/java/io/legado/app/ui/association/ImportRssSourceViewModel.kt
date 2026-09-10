package io.legado.app.ui.association

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.source.requireSourceUrl
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.RuleUpdate
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.isUri
import io.legado.app.utils.readText
import io.legado.app.utils.splitNotBlank
import splitties.init.appCtx

class ImportRssSourceViewModel(app: Application) : BaseViewModel(app) {
    private val importRequestGate = RssSourceImportRequestGate()
    var isAddGroup = AppConfig.importRememberGroup && AppConfig.importLastGroupAdd
    var groupName: String? = AppConfig.importLastGroup.takeIf { AppConfig.importRememberGroup }
    var searchQuery = ""
    val errorLiveData = MutableLiveData<String>()
    val successLiveData = MutableLiveData<Int>()
    val sourceUpdatePending = MutableLiveData(false)

    val allSources = arrayListOf<RssSource>()
    val checkSources = arrayListOf<RssSource?>()
    val selectStatus = arrayListOf<Boolean>()
    private val sourceCandidates = arrayListOf<RssSourceImportCandidate>()
    private val manualSelections = arrayListOf<Boolean?>()
    private var automaticSourceReplacement = AppConfig.importReplaceSource
    private val manualRuleIds = hashMapOf<Int, List<Long>>()
    val useSourceReplacement: Boolean
        get() = AppConfig.manualSourceReplaceRule || automaticSourceReplacement

    val isSelectAll: Boolean
        get() {
            selectStatus.forEachIndexed { index, selected ->
                if (canImportSource(index) && !selected) {
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
            val selectSource = arrayListOf<RssSource>()
            selectStatus.forEachIndexed { index, b ->
                if (b && canImportSource(index)) {
                    val source = allSources[index]
                    checkSources[index]?.let {
                        if (keepName) {
                            source.sourceName = it.sourceName
                        }
                        if (keepGroup) {
                            source.sourceGroup = it.sourceGroup
                        }
                        if (keepEnable) {
                            source.enabled = it.enabled
                        }
                        source.customOrder = it.customOrder
                    }
                    if (!group.isNullOrEmpty()) {
                        if (isAddGroup) {
                            val groups = linkedSetOf<String>()
                            source.sourceGroup?.splitNotBlank(AppPattern.splitGroupRegex)?.let {
                                groups.addAll(it)
                            }
                            groups.add(group)
                            source.sourceGroup = groups.joinToString(",")
                        } else {
                            source.sourceGroup = group
                        }
                    }
                    selectSource.add(source)
                }
            }
            SourceHelp.insertRssSource(*selectSource.toTypedArray())
        }.onFinally {
            finally.invoke()
        }
    }

    fun importSource(text: String) {
        if (!importRequestGate.tryStart()) return
        execute {
            importSourceAwait(text)
        }.onError {
            errorLiveData.postValue("ImportError:${it.localizedMessage}")
            AppLog.put("ImportError:${it.localizedMessage}", it)
        }.onSuccess {
            prepareSourceCandidates()
        }
    }

    private fun prepareSourceCandidates() {
        executeLazy {
            val rules = appDb.replaceRuleDao.findEnabledBySourceScope()
            allSources.mapIndexed { index, source ->
                prepareRssSourceImportCandidate(source, selectedRules(index, rules))
            }
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
        val previousMode = automaticSourceReplacement
        automaticSourceReplacement = enabled
        AppConfig.importReplaceSource = enabled
        applyCandidateSources()
        sourceUpdatePending.value = true
        comparisonSource(
            preserveManualSelections = true,
            onError = {
                automaticSourceReplacement = previousMode
                AppConfig.importReplaceSource = previousMode
                applyCandidateSources()
            },
        ) {
            sourceUpdatePending.value = false
        }
    }

    private suspend fun importSourceAwait(text: String) {
        val mText = text.trim()
        when {
            mText.isJsonObject() || mText.isJsonArray() -> {
                when (val importJson = parseRssSourceJson(mText)) {
                    is RssSourceImportJson.Sources -> allSources.addAll(importJson.items)
                    is RssSourceImportJson.SourceUrls -> importJson.items.forEach {
                        importSourceUrl(it)
                    }
                }
            }

            mText.isAbsUrl() -> {
                importSourceUrl(mText)
            }

            mText.isUri() -> {
                importSourceAwait(mText.toUri().readText(appCtx))
            }

            else -> throw NoStackTraceException(context.getString(R.string.wrong_format))
        }
    }

    private suspend fun importSourceUrl(url: String) {
        RuleUpdate.cacheRssSourceMap[url]?.also {
            allSources.addAll(it)
            RuleUpdate.cacheRssSourceMap.remove(url)
            return
        }
        okHttpClient.newCallResponseBody {
            if (url.endsWith("#requestWithoutUA")) {
                url(url.substringBeforeLast("#requestWithoutUA"))
                header(AppConst.UA_NAME, "null")
            } else {
                url(url)
            }
        }.decompressed().byteStream().use { body ->
            val sources = GSON.fromJsonArray<RssSource>(body).getOrThrow()
            sources.forEach { source -> source.requireSourceUrl() }
            allSources.addAll(sources)
        }
    }

    private fun comparisonSource(
        preserveManualSelections: Boolean = false,
        onError: () -> Unit = {},
        finally: () -> Unit = {},
    ) {
        val savedManualSelections = manualSelections.toList()
        execute {
            lateinit var comparison: RssSourceImportComparison
            appDb.runInTransaction {
                comparison = compareImportedRssSources(allSources) { sourceUrls ->
                    appDb.rssSourceDao.getRssSources(*sourceUrls.toTypedArray())
                }
            }
            comparison
        }.onSuccess { comparison ->
            checkSources.clear()
            selectStatus.clear()
            manualSelections.clear()
            comparison.existingSources.forEachIndexed { index, existingSource ->
                val manualSelection = if (preserveManualSelections) {
                    savedManualSelections.getOrNull(index)
                } else {
                    null
                }
                checkSources.add(existingSource)
                selectStatus.add(
                    canImportSource(index) &&
                        (manualSelection ?: comparison.selectStatus[index])
                )
                manualSelections.add(manualSelection)
            }
            successLiveData.postValue(allSources.size)
        }.onError {
            onError()
            errorLiveData.value = "ImportError:${it.localizedMessage}"
            AppLog.put("ImportError:${it.localizedMessage}", it)
        }.onFinally {
            finally()
        }
    }

    fun setSelection(index: Int, selected: Boolean) {
        if (index !in selectStatus.indices || sourceUpdatePending.value == true ||
            !canImportSource(index)
        ) return
        selectStatus[index] = selected
        if (index in manualSelections.indices) manualSelections[index] = selected
    }

    fun refreshSourceReplacements(
        index: Int = -1,
        source: RssSource? = null,
        ids: List<Long>? = null,
        onReady: () -> Unit = {},
    ): Boolean {
        if (sourceUpdatePending.value == true || (index != -1 && index !in sourceCandidates.indices)) return false
        val previousCandidates = sourceCandidates.toList()
        val previousIds = manualRuleIds.toMap()
        if (ids != null) {
            if (index == -1) sourceCandidates.indices.forEach { manualRuleIds[it] = ids }
            else manualRuleIds[index] = ids
        }
        val selectedIds = manualRuleIds.toMap().takeIf { AppConfig.manualSourceReplaceRule }
        sourceUpdatePending.value = true
        executeLazy {
            refreshRssSourceImportCandidates(
                previousCandidates,
                index,
                source,
                appDb.replaceRuleDao.findEnabledBySourceScope(),
                selectedIds,
            )
        }.onSuccess { candidates ->
            var comparisonSucceeded = true
            sourceCandidates.clear()
            sourceCandidates.addAll(candidates)
            applyCandidateSources()
            comparisonSource(
                preserveManualSelections = true,
                onError = {
                    comparisonSucceeded = false
                    sourceCandidates.clear()
                    sourceCandidates.addAll(previousCandidates)
                    manualRuleIds.clear()
                    manualRuleIds.putAll(previousIds)
                    applyCandidateSources()
                },
            ) {
                sourceUpdatePending.value = false
                if (comparisonSucceeded) onReady()
            }
        }.onError {
            errorLiveData.value = "ImportError:${it.localizedMessage}"
            AppLog.put("ImportError:${it.localizedMessage}", it)
            manualRuleIds.clear()
            manualRuleIds.putAll(previousIds)
            sourceUpdatePending.value = false
        }.start()
        return true
    }

    private fun selectedRules(index: Int, rules: List<io.legado.app.data.entities.ReplaceRule>) =
        if (AppConfig.manualSourceReplaceRule) rules.filter { it.id in manualRuleIds[index].orEmpty() } else rules

    fun selectedManualRuleIds(index: Int = -1): List<Long> = if (index >= 0) manualRuleIds[index].orEmpty()
        else sourceCandidates.indices.map { manualRuleIds[it].orEmpty().toSet() }
            .reduceOrNull { all, ids -> all.intersect(ids) }?.toList().orEmpty()

    fun effectiveRuleIds(index: Int = -1): List<Long> = if (!useSourceReplacement) emptyList() else
        (if (index >= 0) listOfNotNull(sourceCandidates.getOrNull(index)) else sourceCandidates)
            .flatMap { it.effectiveRuleIds }.distinct()

    fun canImportSource(index: Int): Boolean =
        sourceCandidates.getOrNull(index)?.canImport(useSourceReplacement) != false

    fun originalSourceJson(index: Int): String? =
        sourceCandidates.getOrNull(index)?.originalJson

    fun replacedSourceJson(index: Int): String? =
        sourceCandidates.getOrNull(index)?.replacedJson

    fun sourceReplacementError(index: Int): String? =
        sourceCandidates.getOrNull(index)?.replacementError

}
