package io.legado.app.help.storage

import java.io.File

internal val backupMediaDirectoryNames = listOf("covers", "bg")

internal fun findBackupMediaDirectories(externalFilesRoot: File): List<File> {
    return backupMediaDirectoryNames.mapNotNull { name ->
        File(externalFilesRoot, name).takeIf { directory ->
            directory.isDirectory && runCatching {
                directory.walkTopDown().any { it.isFile }
            }.getOrDefault(false)
        }
    }
}

/**
 * Replaces a restored media directory only after it has been copied completely.
 * Staging and rollback directories live beside the target so renames stay on one volume.
 */
internal fun restoreBackupMediaDirectory(
    backupRoot: File,
    externalFilesRoot: File,
    directoryName: String,
): Result<Boolean> {
    require(directoryName in backupMediaDirectoryNames)
    externalFilesRoot.mkdirs()
    val source = File(backupRoot, directoryName)
    val target = File(externalFilesRoot, directoryName)
    val staging = File(externalFilesRoot, ".$directoryName.restore")
    val previous = File(externalFilesRoot, ".$directoryName.previous")
    staging.deleteRecursively()
    if (!target.exists() && previous.exists()) {
        if (!previous.renameTo(target)) {
            return Result.failure(
                IllegalStateException("Unable to recover current $directoryName")
            )
        }
    } else if (target.exists()) {
        previous.deleteRecursively()
    }
    val hasBackupFiles = source.isDirectory && runCatching {
        source.walkTopDown().any { it.isFile }
    }.getOrDefault(false)
    if (!hasBackupFiles) return Result.success(false)

    return runCatching {
        check(source.copyRecursively(staging, overwrite = true)) {
            "Unable to stage $directoryName"
        }
        if (target.exists()) {
            check(target.renameTo(previous)) {
                "Unable to preserve current $directoryName"
            }
        }
        if (!staging.renameTo(target)) {
            if (previous.exists()) {
                check(previous.renameTo(target)) {
                    "Unable to roll back current $directoryName"
                }
            }
            error("Unable to activate restored $directoryName")
        }
        previous.deleteRecursively()
        true
    }.onFailure {
        staging.deleteRecursively()
        if (!target.exists() && previous.exists()) {
            previous.renameTo(target)
        }
    }
}
