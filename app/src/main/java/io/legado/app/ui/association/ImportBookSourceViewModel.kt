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
    val checkSources = arrayListOf<BookSourcePart?>()
    val selectStatus = arrayListOf<Boolean>()
    val newSourceStatus = arrayListOf<Boolean>()
    val updateSourceStatus = arrayListOf<Boolean>()
    private val manualSelections = arrayListOf<Boolean?>()
    private var importStarted = false

    val isSelectAll: Boolean
        get() {
            selectStatus.forEach {
                if (!it) {
                    return false
                }
            }
            return true
        }

    val isSelectAllNew: Boolean
        get() {
            newSourceStatus.forEachIndexed { index, b ->
                if (b && !selectStatus[index]) {
                    return false
                }
            }
            return true
        }

    val isSelectAllUpdate: Boolean
        get() {
            updateSourceStatus.forEachIndexed { index, b ->
                if (b && !selectStatus[index]) {
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
                if (b) {
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
            comparisonSource()
        }.start()
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

    private fun comparisonSource() {
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
            comparisons.forEach { (localSource, status) ->
                checkSources.add(localSource)
                selectStatus.add(status.shouldSelect)
                newSourceStatus.add(status.isNew)
                updateSourceStatus.add(status.isUpdate)
                manualSelections.add(null)
            }
            successLiveData.value = allSources.size
        }.onError {
            errorLiveData.value = "ImportError:${it.localizedMessage}"
            AppLog.put("ImportError:${it.localizedMessage}", it)
        }.start()
    }

    fun setSelection(index: Int, selected: Boolean) {
        if (index !in selectStatus.indices || index !in manualSelections.indices) return
        selectStatus[index] = selected
        manualSelections[index] = selected
    }

    fun updateSource(index: Int, source: BookSource) {
        if (sourceUpdatePending.value == true) return
        sourceUpdatePending.value = true
        executeLazy {
            val localSource = appDb.bookSourceDao.getBookSourcePart(source.bookSourceUrl)
            val editedStatus = resolveImportBookSourceStatus(
                source.lastUpdateTime,
                localSource?.lastUpdateTime,
            )
            localSource to editedStatus
        }.onSuccess { (localSource, editedStatus) ->
            if (index !in allSources.indices) return@onSuccess
            allSources[index] = source
            checkSources[index] = localSource
            selectStatus[index] = resolveImportSourceSelection(
                editedStatus,
                manualSelections[index],
            )
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

}
