package io.legado.app.ui.association

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.R
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.model.localBook.LocalBook
import io.legado.app.ui.book.import.local.ImportBook
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.openInputStream
import splitties.init.appCtx
import java.io.File

/** Own the complete input batch before showing a preview or opening a reader. */
internal fun collectSharedLocalBooks(uris: List<Uri>, staging: File): List<ImportBook> {
    val files = uris.distinct().flatMapIndexed { index, uri ->
        val doc = FileDoc.fromUri(uri, false)
        val directory = File(staging, index.toString()).apply { check(mkdirs()) }
        when {
            ArchiveUtils.isArchive(doc.name) -> ArchiveUtils.deCompress(doc, directory.path) {
                it.matches(AppPattern.bookFileRegex)
            }
            doc.name.matches(AppPattern.bookFileRegex) -> {
                val copy = File(directory, File(doc.name).name)
                doc.openInputStream().getOrThrow().use { input ->
                    copy.outputStream().use(input::copyTo)
                }
                listOf(copy)
            }
            else -> emptyList()
        }
    }
    check(files.isNotEmpty()) { appCtx.getString(R.string.unsupport_archivefile_entry) }
    val identities = hashSetOf<Pair<String, String>>()
    return files.map { file ->
        val doc = FileDoc.fromFile(file)
        val book = LocalBook.previewImportFile(doc.uri)
        val name = book.name
        var suffix = 2
        while (appDb.bookDao.has(book.name, book.author) || !identities.add(book.name to book.author)) {
            book.name = "$name (${suffix++})"
        }
        ImportBook(doc, false, book)
    }
}

/** Never overwrite a destination or retain the sender's temporary URI as the book. */
internal fun copySharedLocalBook(file: FileDoc, directory: Uri): Uri {
    val name = File(file.name).name
    fun candidate(suffix: Int) = if (suffix == 1) name
        else "${name.substringBeforeLast('.')} ($suffix).${name.substringAfterLast('.')}"
    if (directory.isContentScheme()) {
        val tree = checkNotNull(DocumentFile.fromTreeUri(appCtx, directory))
        var suffix = 1
        while (tree.findFile(candidate(suffix)) != null ||
            appDb.bookDao.getBookByFileName(candidate(suffix)) != null) suffix++
        val copy = checkNotNull(tree.createFile(FileUtils.getMimeType(name), candidate(suffix)))
        try {
            file.openInputStream().getOrThrow().use { input ->
                checkNotNull(appCtx.contentResolver.openOutputStream(copy.uri)).use(input::copyTo)
            }
        } catch (error: Throwable) {
            copy.delete()
            throw error
        }
        return copy.uri
    }
    val tree = File(checkNotNull(directory.path))
    check(tree.isDirectory || tree.mkdirs())
    var suffix = 1
    var copy = File(tree, candidate(suffix))
    while (appDb.bookDao.has(copy.path) || !copy.createNewFile()) copy = File(tree, candidate(++suffix))
    try {
        file.openInputStream().getOrThrow().use { input -> copy.outputStream().use(input::copyTo) }
    } catch (error: Throwable) {
        copy.delete()
        throw error
    }
    return Uri.fromFile(copy)
}
