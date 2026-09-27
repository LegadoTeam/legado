package io.legado.app.ui.font

import java.io.File
import java.io.IOException
import java.io.InputStream

private val fontFileNameRegex = Regex("(?i)^.+\\.(?:ttf|otf)$")

@Synchronized
internal fun installFontFile(
    input: InputStream,
    displayName: String,
    directory: File,
    isValid: (File) -> Boolean,
): File {
    val fileName = displayName
        .replace('\\', '/')
        .substringAfterLast('/')
        .trim()
    require(fontFileNameRegex.matches(fileName)) { "invalid font file name" }
    if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
        throw IOException("unable to create font directory")
    }

    val pending = File.createTempFile(".font-", ".part", directory)
    try {
        pending.outputStream().buffered().use(input::copyTo)
        require(pending.length() > 0L && isValid(pending)) { "invalid font file" }

        val target = uniqueFontFile(directory, fileName, pending)
        if (target.isFile) return target
        if (!pending.renameTo(target)) {
            throw IOException("unable to install font")
        }
        return target
    } finally {
        pending.delete()
    }
}

private fun uniqueFontFile(directory: File, fileName: String, pending: File): File {
    val requested = File(directory, fileName)
    if (!requested.exists()) return requested
    if (requested.isFile && requested.contentEquals(pending)) return requested

    val extensionIndex = fileName.lastIndexOf('.')
    val baseName = fileName.substring(0, extensionIndex)
    val extension = fileName.substring(extensionIndex)
    var index = 1
    while (true) {
        val candidate = File(directory, "$baseName ($index)$extension")
        if (!candidate.exists()) return candidate
        if (candidate.isFile && candidate.contentEquals(pending)) return candidate
        index++
    }
}

private fun File.contentEquals(other: File): Boolean {
    if (length() != other.length()) return false
    inputStream().buffered().use { first ->
        other.inputStream().buffered().use { second ->
            val firstBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val secondBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val firstRead = first.read(firstBuffer)
                val secondRead = second.read(secondBuffer)
                if (firstRead != secondRead) return false
                if (firstRead < 0) return true
                for (index in 0 until firstRead) {
                    if (firstBuffer[index] != secondBuffer[index]) return false
                }
            }
        }
    }
}
