package io.legado.app.ui.association

import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.constant.AppPattern.jsFileRegex
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.HighlightRuleFile
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.jsSource.JsSourceConfig
import io.legado.app.help.storage.Restore
import io.legado.app.help.storage.selectedBackupFileNames
import io.legado.app.ui.main.bookshelf.importBookshelfJson
import io.legado.app.ui.book.import.local.ImportBook
import io.legado.app.utils.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

class FileAssociationViewModel(application: Application, private val savedState: SavedStateHandle) : BaseAssociationViewModel(application) {
    val localBookBatch = MutableLiveData<List<ImportBook>>()
    val localBookDestination = MutableLiveData(false)
    val importingLocalBooks = MutableLiveData(false)
    val importedLocalBooks = MutableLiveData(false)
    val mixedLocalTypes = MutableLiveData(false)
    val selectedLocalBooks = linkedSetOf<Uri>()
    var pendingLocalBooks: List<ImportBook> = emptyList()
    private var openSingleLocalBook = false
    var importAfterDirectorySelection: Boolean
        get() = savedState["importAfterDirectorySelection"] ?: true
        private set(value) { savedState["importAfterDirectorySelection"] = value }
    var choosingLocalBookDirectory: Boolean
        get() = savedState["choosingLocalBookDirectory"] ?: false
        set(value) { savedState["choosingLocalBookDirectory"] = value }
    private var stagingDirectory: File? = null
    val onLineImportLive = MutableLiveData<Uri>()
    val openBookLiveData = MutableLiveData<Book>()
    val notSupportedLiveData = MutableLiveData<Pair<Uri, String>>()
    val importingData = MutableLiveData(false)
    val importedData = MutableLiveData(false)
    private var sharedImportFile: File? = null
    private var initialIntentDispatched = false

    init {
        savedState.get<String>("localBookStaging")?.let { path ->
            kotlin.runCatching {
                val staging = File(path)
                require(staging.parentFile?.canonicalFile == File(context.cacheDir, "shared-books").canonicalFile)
                val books = GSON.fromJsonArray<Book>(File(staging, "preview.json").readText()).getOrThrow()
                val items = books.map { ImportBook(FileDoc.fromFile(File(it.bookUrl)), false, it) }
                stagingDirectory = staging
                openSingleLocalBook = savedState["openSingleLocalBook"] ?: false
                selectedLocalBooks.addAll(savedState.get<ArrayList<String>>("selectedLocalBooks").orEmpty().map(Uri::parse))
                pendingLocalBooks = items.filter { it.file.uri in selectedLocalBooks }
                if (!openSingleLocalBook) localBookBatch.value = items
                if (savedState.get<Boolean>("localBookDestination") == true) localBookDestination.value = true
                initialIntentDispatched = true
            }.onFailure { AppLog.put("恢复分享书籍预览失败", it) }
        }
    }

    fun shouldDispatchInitialIntent(): Boolean {
        if (initialIntentDispatched) return false
        initialIntentDispatched = true
        return true
    }

    fun dispatchIntent(uri: Uri) {
        execute {
            //如果是普通的url，需要根据返回的内容判断是什么
            if (uri.isContentScheme() || uri.isFileScheme()) {
                dispatchFile(FileDoc.fromUri(uri, false))
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
            require(uri.isContentScheme())
            // Provider ownership can allow reading without an explicit URI grant.
            uri.inputStream(context).getOrThrow().use { }
            dispatchFile(FileDoc.fromUri(uri, false), shared = true)
        }.onError {
            reportSharedImportError(it)
        }
    }

    fun dispatchSharedUris(uris: List<Uri>) {
        execute {
            require(uris.isNotEmpty() && uris.all { it.isContentScheme() })
            prepareLocalBooks(uris, false)
        }.onError { reportSharedImportError(it) }
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

    private suspend fun dispatchFile(fileDoc: FileDoc, shared: Boolean = false) {
        if (fileDoc.name.matches(AppPattern.archiveFileRegex)) {
            val backupNames = selectedBackupFileNames { true }.toSet()
            val entries = if (fileDoc.name.endsWith(".zip", true)) {
                ArchiveUtils.getArchiveFilesName(fileDoc) { it in backupNames || it.matches(bookFileRegex) }
            } else emptyList()
            // Backup archives contain records and media, never the local book files themselves.
            if (entries.any { it in backupNames } && entries.none { it.matches(bookFileRegex) }) {
                successLive.postValue("backup" to fileDoc.uri.toString())
            } else {
                prepareLocalBooks(listOf(fileDoc.uri), false)
            }
        } else {
            dispatch(fileDoc, shared)
        }
    }

    fun importData(type: String, source: String) {
        if (importingData.value == true || importedData.value == true) return
        importingData.value = true
        execute {
            when (type) {
                "bookshelf" -> importBookshelfJson(Uri.parse(source).readText(context), 0)
                "backup" -> Restore.restoreOrThrow(context, Uri.parse(source))
                else -> error("Unsupported import")
            }
        }.onSuccess {
            importedData.value = true
        }.onError {
            errorLive.value = it.localizedMessage ?: context.getString(R.string.wrong_format)
            AppLog.put("导入分享数据失败\n${it.localizedMessage}", it)
        }.onFinally {
            importingData.value = false
        }
    }

    private suspend fun dispatch(fileDoc: FileDoc, shared: Boolean = false) {
        kotlin.runCatching {
            if (fileDoc.openInputStream().getOrNull().looksLikeJson()) {
                importJson(fileDoc.uri)
                return
            }
        }.onFailure {
            it.printOnDebug()
            AppLog.put("尝试导入为JSON文件失败\n${it.localizedMessage}", it)
        }
        if (fileDoc.name.matches(jsFileRegex)) {
            successLive.postValue("bookSource" to fileDoc.uri.toString())
            return
        }
        if (fileDoc.name.matches(bookFileRegex)) {
            prepareLocalBooks(listOf(fileDoc.uri), !shared)
            return
        }
        notSupportedLiveData.postValue(Pair(fileDoc.uri, fileDoc.name))
    }

    private suspend fun prepareLocalBooks(uris: List<Uri>, openSingle: Boolean) {
        val staging = File(context.cacheDir, "shared-books/${UUID.randomUUID()}")
        stagingDirectory = staging
        val files = collectSharedImportFiles(uris, staging)
        val dataFiles = files.mapNotNull { file ->
            val type = if (file.name.matches(jsFileRegex)) "bookSource"
            else kotlin.runCatching {
                if (!file.inputStream().looksLikeJson()) return@runCatching null
                val map = file.inputStream().use { jsonPath.parse(it).read<Map<String, *>>("$[0]") }
                    ?: file.inputStream().use { jsonPath.parse(it).read("$") }
                jsonImportType(map)
            }.getOrNull()
            type?.let { it to file }
        }
        val bookFiles = files.filter { file ->
            file.name.matches(bookFileRegex) && dataFiles.none { it.second == file }
        }
        if (dataFiles.isNotEmpty()) {
            if (bookFiles.isNotEmpty() || dataFiles.map { it.first }.distinct().size != 1) {
                mixedLocalTypes.postValue(true)
                return
            }
            val type = dataFiles.first().first
            val file = if (dataFiles.size == 1) dataFiles.single().second else {
                val merged = JsonArray()
                dataFiles.forEach { (_, source) ->
                    val json = if (source.name.matches(jsFileRegex)) {
                        GSON.toJsonTree(JsSourceConfig.extract(source.readText(), currentCoroutineContext()))
                    } else source.reader().use { GSON.fromJson(it, JsonElement::class.java) }
                    val records = if (type == "highlightRule" && json.isJsonObject &&
                        json.asJsonObject.get("type")?.asString == HighlightRuleFile.TYPE) {
                        json.asJsonObject.getAsJsonArray("rules")
                    } else json
                    if (records.isJsonArray) records.asJsonArray.forEach(merged::add)
                    else merged.add(records)
                }
                File(staging, "import-data.json").apply { writeText(merged.toString()) }
            }
            successLive.postValue(type to Uri.fromFile(file).toString())
            return
        }
        val items = previewSharedLocalBooks(bookFiles)
        File(staging, "preview.json").writeText(GSON.toJson(items.map { it.preview }))
        withContext(Main) {
            savedState["localBookStaging"] = staging.path
            savedState["openSingleLocalBook"] = openSingle
            openSingleLocalBook = openSingle
            updateLocalSelection(items.map { it.file.uri })
            pendingLocalBooks = items
            if (openSingle) requestLocalBookDirectory(true)
            else localBookBatch.value = items
        }
    }

    fun updateLocalSelection(uris: Collection<Uri>) {
        selectedLocalBooks.clear()
        selectedLocalBooks.addAll(uris)
        savedState["selectedLocalBooks"] = ArrayList(uris.map { it.toString() })
    }

    fun confirmLocalBooks() {
        if (importingLocalBooks.value == true || localBookDestination.value == true) return
        pendingLocalBooks = localBookBatch.value.orEmpty().filter { it.file.uri in selectedLocalBooks }
        if (pendingLocalBooks.isNotEmpty()) {
            requestLocalBookDirectory(true)
        }
    }

    fun requestLocalBookDirectory(importAfter: Boolean) {
        importAfterDirectorySelection = importAfter
        savedState["localBookDestination"] = true
        localBookDestination.value = true
    }

    fun selectLocalBookDirectory(directory: Uri) {
        choosingLocalBookDirectory = false
        savedState["localBookDestination"] = false
        localBookDestination.value = false
        if (importAfterDirectorySelection) importLocalBooks(directory)
    }

    fun importBook(uri: Uri) {
        execute { LocalBook.importFile(uri) }.onSuccess { openBookLiveData.value = it }
            .onError { errorLive.value = it.localizedMessage }
    }

    fun importLocalBooks(directory: Uri) {
        if (importingLocalBooks.value == true || importedLocalBooks.value == true) return
        localBookDestination.value = false
        savedState["localBookDestination"] = false
        importingLocalBooks.value = true
        execute {
            val copies = linkedMapOf<Uri, Book>()
            pendingLocalBooks.forEach { item ->
                kotlin.runCatching {
                    val uri = copySharedLocalBook(item.file, directory)
                    val preview = checkNotNull(item.preview)
                        .copy(bookUrl = FileDoc.fromUri(uri, false).toString())
                    item.preview.coverUrl?.let { coverPath ->
                        val cover = File(coverPath)
                        if (coverPath == LocalBook.getCoverPath(item.preview) && cover.isFile) {
                            val destination = File(LocalBook.getCoverPath(preview))
                            cover.copyTo(destination, overwrite = true)
                            preview.coverUrl = destination.path
                        }
                    }
                    copies[uri] = preview
                }.onFailure { AppLog.put("复制分享书籍失败\n${it.localizedMessage}", it) }
            }
            val (_, books) = LocalBook.importFiles(copies.keys.toList(), copies)
            books
        }.onSuccess { books ->
            context.toastOnUi(context.getString(R.string.shared_local_books_copied,
                books.size, pendingLocalBooks.size - books.size))
            if (openSingleLocalBook) openBookLiveData.value = books.single()
            else importedLocalBooks.value = true
        }.onError {
            errorLive.value = it.localizedMessage ?: context.getString(R.string.wrong_format)
            AppLog.put("导入分享书籍失败\n${it.localizedMessage}", it)
        }.onFinally { importingLocalBooks.value = false }
    }

    private fun reportSharedImportError(error: Throwable) {
        error.printOnDebug()
        errorLive.postValue(error.localizedMessage ?: context.getString(R.string.wrong_format))
        AppLog.put("尝试导入分享内容失败\n${error.localizedMessage}", error)
    }

    override fun onCleared() {
        sharedImportFile?.delete()
        (localBookBatch.value.orEmpty() + pendingLocalBooks).distinctBy { it.file.uri }.forEach {
            LocalBook.withParserCacheInvalidated(it.file.uri, it.file.name) { }
            it.preview?.let { preview ->
                if (preview.coverUrl == LocalBook.getCoverPath(preview)) File(preview.coverUrl!!).delete()
            }
        }
        stagingDirectory?.deleteRecursively()
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
