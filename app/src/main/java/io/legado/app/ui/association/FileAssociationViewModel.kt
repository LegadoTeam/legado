package io.legado.app.ui.association

import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.data.entities.Book
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

class FileAssociationViewModel(application: Application) : BaseAssociationViewModel(application) {
    val importBookLiveData = MutableLiveData<Uri>()
    val onLineImportLive = MutableLiveData<Uri>()
    val openBookLiveData = MutableLiveData<Book>()
    val notSupportedLiveData = MutableLiveData<Pair<Uri, String>>()
    private var sharedImportFile: File? = null
    private var initialIntentDispatched = false

    fun shouldDispatchInitialIntent(): Boolean {
        if (initialIntentDispatched) return false
        initialIntentDispatched = true
        return true
    }

    fun dispatchIntent(uri: Uri) {
        execute {
            //如果是普通的url，需要根据返回的内容判断是什么
            if (uri.isContentScheme() || uri.isFileScheme()) {
                val fileDoc = FileDoc.fromUri(uri, false)
                val fileName = fileDoc.name
                if (fileName.matches(AppPattern.archiveFileRegex)) {
                    ArchiveUtils.deCompress(fileDoc, ArchiveUtils.TEMP_PATH) {
                        it.matches(bookFileRegex)
                    }.forEach {
                        dispatch(FileDoc.fromFile(it))
                    }
                } else {
                    dispatch(fileDoc)
                }
            } else {
                onLineImportLive.postValue(uri)
            }
        }.onError {
            it.printOnDebug()
            val msg = "无法打开文件\n${it.localizedMessage}"
            errorLive.postValue(msg)
            AppLog.put(msg, it)
        }
    }

    fun dispatchSharedUri(uri: Uri) {
        execute {
            require(uri.isContentScheme() && uri.canRead())
            importJson(uri)
        }.onError {
            reportSharedImportError(it)
        }
    }

    fun dispatchSharedText(text: String) {
        execute {
            extractSharedImportUrl(text)?.let { url ->
                onLineImportLive.postValue(
                    Uri.Builder()
                        .scheme("legado")
                        .authority("import")
                        .appendPath("auto")
                        .appendQueryParameter("src", url)
                        .build()
                )
                return@execute
            }
            val file = File.createTempFile("shared_import_", ".json", context.cacheDir)
            sharedImportFile = file
            file.writeText(text)
            importJson(Uri.fromFile(file))
        }.onError {
            reportSharedImportError(it)
        }
    }

    fun reportInvalidSharedContent() {
        errorLive.value = context.getString(R.string.wrong_format)
    }

    private fun dispatch(fileDoc: FileDoc) {
        kotlin.runCatching {
            if (fileDoc.openInputStream().getOrNull().looksLikeJson()) {
                importJson(fileDoc.uri)
                return
            }
        }.onFailure {
            it.printOnDebug()
            AppLog.put("尝试导入为JSON文件失败\n${it.localizedMessage}", it)
        }
        if (fileDoc.name.matches(bookFileRegex)) {
            importBookLiveData.postValue(fileDoc.uri)
            return
        }
        notSupportedLiveData.postValue(Pair(fileDoc.uri, fileDoc.name))
    }

    fun importBook(uri: Uri) {
        val book = LocalBook.importFile(uri)
        openBookLiveData.postValue(book)
    }

    private fun reportSharedImportError(error: Throwable) {
        error.printOnDebug()
        errorLive.postValue(context.getString(R.string.wrong_format))
        AppLog.put("尝试导入分享内容失败\n${error.localizedMessage}", error)
    }

    override fun onCleared() {
        sharedImportFile?.delete()
        super.onCleared()
    }
}

private val sharedImportUrlRegex =
    Regex("""(?<!["'])https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

private val sharedImportUrlTrailingPunctuation =
    setOf(
        '.', ',', ';', ':', '!', '?',
        '\u3002', '\uff0c', '\uff1b', '\uff1a', '\uff01', '\uff1f', '\u3001'
    )

private val sharedImportUrlBrackets =
    listOf(
        '(' to ')', '[' to ']', '{' to '}',
        '\uff08' to '\uff09', '\u3010' to '\u3011', '\u300a' to '\u300b'
    )

internal fun extractSharedImportUrl(text: String): String? {
    if (text.isJson()) return null
    return sharedImportUrlRegex.findAll(text)
        .map { it.value.trimSharedImportUrlSuffix() }
        .filter { it.toHttpUrlOrNull() != null }
        .singleOrNull()
}

private fun String.trimSharedImportUrlSuffix(): String {
    var result = trimEnd { it in sharedImportUrlTrailingPunctuation }
    sharedImportUrlBrackets.forEach { (open, close) ->
        while (result.endsWith(close) && result.count { it == close } > result.count { it == open }) {
            result = result.dropLast(1)
        }
    }
    return result
}
