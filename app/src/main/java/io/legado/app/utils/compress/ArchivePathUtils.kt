package io.legado.app.utils.compress

import io.legado.app.utils.isSameOrDescendantOf
import java.io.File
import java.io.FileNotFoundException

private const val INVALID_ARCHIVE_PATH = "压缩文件只能解压到指定路径"
private val windowsDrivePath = Regex("^[A-Za-z]:.*")

internal fun resolveArchiveEntryFile(destDir: File, entryName: String): File {
    if (entryName.startsWith('/') ||
        entryName.startsWith('\\') ||
        windowsDrivePath.matches(entryName) ||
        File(entryName).isAbsolute
    ) {
        throw SecurityException(INVALID_ARCHIVE_PATH)
    }

    val canonicalDestDir = destDir.canonicalFile
    val canonicalEntryFile = File(canonicalDestDir, entryName).canonicalFile
    if (!canonicalEntryFile.isSameOrDescendantOf(canonicalDestDir)) {
        throw SecurityException(INVALID_ARCHIVE_PATH)
    }
    return canonicalEntryFile
}

internal fun prepareArchiveEntryFile(
    destDir: File,
    entryName: String,
    isDirectory: Boolean,
): File {
    var entryFile = resolveArchiveEntryFile(destDir, entryName)
    if (isDirectory) {
        if (!entryFile.exists() && !entryFile.mkdirs() && !entryFile.isDirectory) {
            throw FileNotFoundException("Unable to create archive directory: $entryFile")
        }
        return resolveArchiveEntryFile(destDir, entryName)
    }

    val parent = entryFile.parentFile
    if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
        throw FileNotFoundException("Unable to create archive directory: $parent")
    }
    entryFile = resolveArchiveEntryFile(destDir, entryName)
    if (!entryFile.exists() && entryFile.createNewFile()) {
        entryFile.setReadable(true)
        entryFile.setExecutable(true)
    }
    return resolveArchiveEntryFile(destDir, entryName)
}
