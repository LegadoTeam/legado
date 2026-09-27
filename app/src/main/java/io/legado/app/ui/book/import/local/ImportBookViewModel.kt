package io.legado.app.ui.book.import.local

import android.app.Application
import android.net.Uri
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern.archiveFileRegex
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookGroup
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.FileDoc
import io.legado.app.utils.delete
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.list
import io.legado.app.utils.mapParallel
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import java.util.Collections

class ImportBookViewModel(application: Application) : BaseViewModel(application) {
    var rootDoc: FileDoc? = null
    val subDocs = arrayListOf<FileDoc>()
    var sort = context.getPrefInt(PreferKey.localBookImportSort)
    var dataCallback: DataCallback? = null
    var dataFlowStart: (() -> Unit)? = null
    var filterKey: String? = null
    val dataFlow = callbackFlow<List<ImportBook>> {

        val list = Collections.synchronizedList(ArrayList<ImportBook>())

        dataCallback = object : DataCallback {

            override fun setItems(
                fileDocs: List<FileDoc>,
                shelfFiles: ImportBookShelfFiles,
            ) {
                list.clear()
                fileDocs.mapTo(list) {
                    ImportBook(it, !it.isDir && it.name in shelfFiles)
                }
                trySend(list)
            }

            override fun addItems(
                fileDocs: List<FileDoc>,
                shelfFiles: ImportBookShelfFiles,
            ) {
                fileDocs.mapTo(list) {
                    ImportBook(it, !it.isDir && it.name in shelfFiles)
                }
                trySend(list)
            }

            override fun clear() {
                list.clear()
                trySend(emptyList())
            }

            override fun upAdapter() {
                trySend(list)
            }
        }

        withContext(Main) {
            dataFlowStart?.invoke()
        }

        awaitClose {
            dataCallback = null
        }

    }.map { docList ->
        val docList = docList.toList()
        val filterKey = filterKey
        val skipFilter = filterKey.isNullOrBlank()
        val comparator = when (sort) {
            2 -> compareBy<ImportBook>({ !it.isDir }, { -it.lastModified })
            1 -> compareBy({ !it.isDir }, { -it.size })
            else -> compareBy { !it.isDir }
        } then compareBy(AlphanumComparator) { it.name }
        docList.asSequence().filter {
            skipFilter || it.name.contains(filterKey)
        }.sortedWith(comparator).toList()
    }.flowOn(IO)

    fun addToBookshelf(
        bookList: HashSet<ImportBook>,
        groupName: String? = null,
        onSuccess: (Set<Uri>) -> Unit,
    ) {
        val fileUris = bookList.map { it.file.uri }
        execute {
            if (groupName != null && !appDb.bookGroupDao.canAddGroup) {
                throw NoStackTraceException(context.getString(R.string.book_group_limit))
            }
            val (importedUris, importedBooks) = LocalBook.importFiles(fileUris)
            val groupError = groupName?.let { name ->
                kotlin.runCatching {
                    appDb.runInTransaction {
                        val groupDao = appDb.bookGroupDao
                        if (!groupDao.canAddGroup) {
                            throw NoStackTraceException(context.getString(R.string.book_group_limit))
                        }
                        val groupId = groupDao.getUnusedId()
                        groupDao.getByID(groupId) ?: appDb.bookDao.removeGroup(groupId)
                        groupDao.insert(
                            BookGroup(
                                groupId = groupId,
                                groupName = name,
                                order = groupDao.maxOrder + 1,
                            )
                        )
                        importedBooks.map { it.bookUrl }.chunked(900).forEach {
                            appDb.bookDao.addGroup(it, groupId)
                        }
                    }
                }.exceptionOrNull()
            }
            Triple(importedUris, importedBooks.size, groupError)
        }.onError {
            context.toastOnUi(
                it.localizedMessage
                    ?: context.getString(R.string.add_loaded_books_to_bookshelf_failed)
            )
            AppLog.put("添加书架失败\n${it.localizedMessage}", it)
        }.onSuccess { (importedUris, importedBookCount, groupError) ->
            if (groupError != null) {
                AppLog.put("创建本地书籍目录分组失败\n${groupError.localizedMessage}", groupError)
                context.toastOnUi(
                    context.getString(
                        R.string.import_directory_group_failed,
                        groupError.localizedMessage,
                    )
                )
            } else if (importedUris.size == fileUris.size) {
                context.toastOnUi("添加书架成功")
            } else {
                context.toastOnUi(
                    "成功添加 $importedBookCount 本书，${fileUris.size - importedUris.size} 个文件未完整导入"
                )
            }
            onSuccess(importedUris)
        }
    }

    fun deleteDoc(bookList: HashSet<ImportBook>, finally: () -> Unit) {
        execute {
            bookList.forEach {
                it.file.delete()
            }
        }.onFinally {
            finally.invoke()
        }
    }

    fun loadDoc(fileDoc: FileDoc) {
        execute {
            val shelfFiles = loadShelfFiles()
            val docList = fileDoc.list { item ->
                when {
                    item.name.startsWith(".") -> false
                    item.isDir -> true
                    else -> item.name.matches(bookFileRegex) || item.name.matches(archiveFileRegex)
                }
            }
            dataCallback?.setItems(docList!!, shelfFiles)
        }.onError {
            context.toastOnUi("获取文件列表出错\n${it.localizedMessage}")
        }
    }

    suspend fun scanDoc(fileDoc: FileDoc) {
        val shelfFiles = loadShelfFiles()
        dataCallback?.clear()
        val channel = Channel<FileDoc>(UNLIMITED)
        var n = 1
        channel.trySend(fileDoc)
        val list = arrayListOf<FileDoc>()
        channel.consumeAsFlow()
            .mapParallel(16) { fileDoc ->
                fileDoc.list()!!
            }.onEach { fileDocs ->
                n--
                list.clear()
                fileDocs.forEach {
                    if (it.isDir) {
                        n++
                        channel.trySend(it)
                    } else if (it.name.matches(bookFileRegex)
                        || it.name.matches(archiveFileRegex)
                    ) {
                        list.add(it)
                    }
                }
                dataCallback?.addItems(list, shelfFiles)
            }.takeWhile {
                n > 0
            }.catch {
                context.toastOnUi("扫描文件夹出错\n${it.localizedMessage}")
            }.collect()
    }

    private fun loadShelfFiles(): ImportBookShelfFiles {
        return ImportBookShelfFiles(
            appDb.bookDao.localBookFileNames,
            appDb.bookDao.localBookAlternateOrigins
        )
    }

    fun updateCallBackFlow(filterKey: String?) {
        this.filterKey = filterKey
        dataCallback?.upAdapter()
    }

    interface DataCallback {

        fun setItems(fileDocs: List<FileDoc>, shelfFiles: ImportBookShelfFiles)

        fun addItems(fileDocs: List<FileDoc>, shelfFiles: ImportBookShelfFiles)

        fun clear()

        fun upAdapter()

    }

}
