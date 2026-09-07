package io.legado.app.help.storage

import io.legado.app.data.entities.ReadRecord
import java.io.File

internal const val readRecordCoverDirectory = "readRecordCovers"

internal fun prepareReadRecordBackup(
    records: List<ReadRecord>,
    externalFilesRoot: File,
    backupRoot: File,
    includeCovers: Boolean,
): List<ReadRecord> {
    val coverRoot = File(externalFilesRoot, readRecordCoverDirectory).canonicalFile
    val staged = File(backupRoot, readRecordCoverDirectory)
    return records.map { record ->
        if (!includeCovers && record.coverUrl?.startsWith("data:", ignoreCase = true) == true) {
            return@map record.copy(coverUrl = null)
        }
        val source = record.coverUrl?.let(::File)
        if (source == null || !source.isAbsolute || source.canonicalFile.parentFile != coverRoot) return@map record
        if (includeCovers && source.isFile) {
            check(staged.isDirectory || staged.mkdirs())
            source.copyTo(File(staged, source.name), overwrite = true)
            record
        } else {
            record.copy(coverUrl = null)
        }
    }
}

internal fun remapReadRecordCover(path: String?, backupRoot: File, externalFilesRoot: File): String? {
    val source = path?.let(::File) ?: return null
    if (!source.isAbsolute || source.parentFile?.name != readRecordCoverDirectory) return path
    val target = File(externalFilesRoot, "$readRecordCoverDirectory/${source.name}")
    return target.absolutePath.takeIf {
        File(backupRoot, "$readRecordCoverDirectory/${source.name}").isFile || target.isFile
    }
}
