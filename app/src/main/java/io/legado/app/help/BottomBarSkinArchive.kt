package io.legado.app.help

import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

object BottomBarSkinArchive {

    const val MAX_ARCHIVE_BYTES = 16 * 1024 * 1024
    const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 32 * 1024 * 1024
    const val MAX_ENTRIES = 64

    fun extract(input: InputStream, destination: File): List<File> {
        destination.deleteRecursively()
        require(destination.mkdirs()) { "cannot create staging directory" }
        val destinationPath = destination.canonicalFile
        val files = ArrayList<File>()
        val usedNames = HashSet<String>()
        var entryCount = 0
        var totalBytes = 0L

        try {
            ZipInputStream(BufferedInputStream(LimitedInputStream(input, MAX_ARCHIVE_BYTES.toLong())))
                .use { zip ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var entry = zip.nextEntry
                    while (entry != null) {
                        entryCount++
                        require(entryCount <= MAX_ENTRIES) { "too many zip entries" }
                        val imageName = if (entry.isDirectory) {
                            null
                        } else {
                            BottomBarSkinFormat.sanitizeImageName(entry.name)
                        }
                        val outputFile = imageName?.let {
                            val uniqueName = uniqueName(it, usedNames)
                            File(destinationPath, uniqueName).canonicalFile.also { file ->
                                require(file.parentFile == destinationPath) { "invalid image path" }
                            }
                        }
                        var entryBytes = 0L
                        outputFile?.outputStream().use { stream ->
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                entryBytes += read
                                totalBytes += read
                                require(entryBytes <= MAX_ENTRY_BYTES) { "zip entry too large" }
                                require(totalBytes <= MAX_TOTAL_BYTES) { "zip content too large" }
                                stream?.write(buffer, 0, read)
                            }
                        }
                        outputFile?.let(files::add)
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            require(files.isNotEmpty()) { "no images in zip" }
            return files
        } catch (error: Throwable) {
            destination.deleteRecursively()
            throw error
        }
    }

    private fun uniqueName(base: String, used: MutableSet<String>): String {
        if (used.add(base.lowercase(Locale.ROOT))) return base
        val dot = base.lastIndexOf('.')
        require(dot > 0) { "invalid image name" }
        var index = 2
        while (true) {
            val candidate = BottomBarSkinFormat.addImageNameSuffix(base, " ($index)")
            if (used.add(candidate.lowercase(Locale.ROOT))) return candidate
            index++
        }
    }

    private class LimitedInputStream(
        input: InputStream,
        private val limit: Long,
    ) : FilterInputStream(input) {

        private var count = 0L

        override fun read(): Int {
            return super.read().also { value ->
                if (value >= 0) checkLimit(1)
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return super.read(buffer, offset, length).also { read ->
                if (read > 0) checkLimit(read.toLong())
            }
        }

        override fun skip(byteCount: Long): Long {
            if (byteCount <= 0) return 0
            val buffer = ByteArray(minOf(byteCount, DEFAULT_BUFFER_SIZE.toLong()).toInt())
            var remaining = byteCount
            var skipped = 0L
            while (remaining > 0) {
                val read = read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (read < 0) break
                remaining -= read
                skipped += read
            }
            return skipped
        }

        private fun checkLimit(read: Long) {
            count += read
            if (count > limit) throw IOException("zip file too large")
        }
    }
}
