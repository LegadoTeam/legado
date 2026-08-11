package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.isAbsUrl
import java.io.File
import java.io.IOException

internal fun Book.networkCoverForPersistence(): String? {
    return getDisplayCover()?.takeIf { it.isAbsUrl() }
}

internal fun installPersistentCover(source: File, coversDir: File): File {
    if (!source.isFile || source.length() == 0L) {
        throw IOException("Cover download is empty")
    }
    if ((!coversDir.exists() && !coversDir.mkdirs()) || !coversDir.isDirectory) {
        throw IOException("Unable to create cover directory")
    }
    val digest = source.inputStream().use { MD5Utils.md5Encode(it) }
    val target = File(coversDir, "$digest.cover")
    if (target.isFile) return target

    val pending = File.createTempFile(".$digest-", ".part", coversDir)
    try {
        source.copyTo(pending, overwrite = true)
        if (!pending.renameTo(target) && !target.isFile) {
            throw IOException("Unable to install persistent cover")
        }
        return target
    } finally {
        pending.delete()
    }
}
