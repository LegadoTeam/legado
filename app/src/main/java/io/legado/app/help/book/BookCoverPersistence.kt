package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.coverSourceOrigin
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.isAbsUrl
import java.io.File
import java.io.IOException

internal fun Book.networkCoverForPersistence(): String? {
    return (customCoverUrl?.takeIf { it.isNotEmpty() } ?: coverUrl)
        ?.takeIf { it.isAbsUrl() }
}

internal fun Book.networkCoverSourceOrigin() = coverSourceOrigin(origin, customCoverUrl)

internal fun hasEditedNetworkCover(
    editedCoverUrl: String?,
    customCoverUrl: String?,
    sourceCoverUrl: String?,
): Boolean {
    val edited = editedCoverUrl?.takeIf { it.isNotEmpty() }
    val current = customCoverUrl?.takeIf { it.isNotEmpty() }
        ?: sourceCoverUrl?.takeIf { it.isNotEmpty() }
    return edited != current
}

private val persistedCoverFileName = Regex("^[0-9a-fA-F]{32}\\.cover$")

internal fun isLegacyPersistedCoverPath(path: String?): Boolean {
    val file = path?.let(::File) ?: return false
    return file.isAbsolute &&
        file.parentFile?.name == "covers" &&
        persistedCoverFileName.matches(file.name)
}

internal fun Book.normalizeLegacyPersistedCover() {
    if (persistedCoverUrl.isNullOrEmpty() && isLegacyPersistedCoverPath(customCoverUrl)) {
        persistedCoverUrl = customCoverUrl
        customCoverUrl = null
    }
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
