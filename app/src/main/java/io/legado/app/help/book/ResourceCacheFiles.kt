package io.legado.app.help.book

import java.io.File
import java.io.IOException

/** Prepared files are on the cache filesystem; keep old files until metadata also succeeds. */
internal fun replaceResourceFiles(files: Map<File, File?>, saveMetadata: () -> Unit) {
    val backups = linkedMapOf<File, File?>()
    try {
        files.forEach { (target, prepared) ->
            val parent = checkNotNull(target.parentFile)
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create $parent")
            val backup = if (target.exists()) {
                if (!target.isFile) throw IOException("Not a cache file: $target")
                File.createTempFile(".resource-backup-", ".tmp", parent).also {
                    if (!it.delete() || !target.renameTo(it)) throw IOException("Cannot preserve $target")
                }
            } else null
            backups[target] = backup
            if (prepared != null && !prepared.renameTo(target)) {
                throw IOException("Cannot install refreshed cache: $target")
            }
        }
        saveMetadata()
    } catch (error: Throwable) {
        backups.entries.toList().asReversed().forEach { (target, backup) ->
            if (target.exists() && !target.delete()) {
                error.addSuppressed(IOException("Cannot remove new cache: $target; old file: $backup"))
            } else if (backup != null && !backup.renameTo(target)) {
                error.addSuppressed(IOException("Cannot restore $target; old file retained at $backup"))
            }
        }
        throw error
    }
    backups.values.forEach { it?.delete() }
}
