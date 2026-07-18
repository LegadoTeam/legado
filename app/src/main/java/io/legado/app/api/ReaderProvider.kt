/*
 * Copyright (C) 2020 w568w
 */
package io.legado.app.api

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.google.gson.Gson
import io.legado.app.api.controller.BookController
import io.legado.app.api.controller.BookSourceController
import io.legado.app.api.controller.RssSourceController
import kotlinx.coroutines.runBlocking

/**
 * Export book data to other app.
 */
class ReaderProvider : ContentProvider() {
    private val postBodyKey = "json"
    private val sMatcher by lazy {
        UriMatcher(UriMatcher.NO_MATCH).apply {
            "${context?.applicationInfo?.packageName}.readerProvider".also { authority ->
                ReaderProviderRoutes.all.forEach { route ->
                    addURI(authority, route.path, route.requestCode.ordinal)
                }
            }
        }
    }

    private fun requestCode(uri: Uri): ReaderProviderRequestCode? =
        ReaderProviderRoutes.requestForMatcherCode(sMatcher.match(uri))

    override fun onCreate(): Boolean {
        context?.let { context ->
            ShortCuts.buildShortCuts(context)
        }
        return false
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        val requestCode = requestCode(uri) ?: return -1
        dispatchReaderProviderDelete(
            requestCode,
            selection,
            deleteBookSources = { BookSourceController.deleteSources(it) },
            deleteRssSources = { RssSourceController.deleteSources(it) },
        )
        return 0
    }

    override fun getType(uri: Uri) = throw UnsupportedOperationException("Not yet implemented")

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val requestCode = requestCode(uri) ?: return null
        runBlocking {
            dispatchReaderProviderInsert(
                requestCode,
                values?.getAsString(postBodyKey),
                valuesPresent = values != null,
                saveBookSource = { BookSourceController.saveSource(it) },
                saveBookSources = { BookSourceController.saveSources(it) },
                saveRssSource = { RssSourceController.saveSource(it) },
                saveRssSources = { RssSourceController.saveSources(it) },
                saveBook = { BookController.saveBook(it) },
                saveBookProgress = { BookController.saveBookProgress(it) },
            )
        }
        return null
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? {
        val map: MutableMap<String, ArrayList<String>> = HashMap()
        uri.getQueryParameter("url")?.let {
            map["url"] = arrayListOf(it)
        }
        uri.getQueryParameter("index")?.let {
            map["index"] = arrayListOf(it)
        }
        uri.getQueryParameter("path")?.let {
            map["path"] = arrayListOf(it)
        }
        val requestCode = requestCode(uri) ?: return null
        return SimpleCursor(
            dispatchReaderProviderQuery(
                requestCode,
                map,
                getBookSource = { BookSourceController.getSource(it) },
                getBookSources = { BookSourceController.sources },
                getRssSource = { RssSourceController.getSource(it) },
                getRssSources = { RssSourceController.sources },
                getBookshelf = { BookController.bookshelf },
                getBookContent = { BookController.getBookContent(it) },
                refreshToc = { BookController.refreshToc(it) },
                getChapterList = { BookController.getChapterList(it) },
                getBookCover = { BookController.getCover(it) },
            )
        )
    }

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<String>?
    ) = throw UnsupportedOperationException("Not yet implemented")


    /**
     * Simple inner class to deliver json callback data.
     *
     * Only getString() makes sense.
     */
    private class SimpleCursor(data: ReturnData?) : MatrixCursor(arrayOf("result"), 1) {

        private val mData: String = Gson().toJson(data)

        init {
            addRow(arrayOf(mData))
        }

    }
}
