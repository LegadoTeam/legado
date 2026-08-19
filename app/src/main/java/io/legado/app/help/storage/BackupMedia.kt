package io.legado.app.help.storage

import io.legado.app.help.book.isLegacyPersistedCoverPath
import java.io.File

internal val backupMediaDirectoryNames = listOf("covers", "bg")

internal fun requiredRestoreMediaBytes(
    backupRoot: File,
    externalFilesRoot: File,
    directoryNames: Collection<String>,
): Long = directoryNames.fold(0L) { total, name ->
    require(name in backupMediaDirectoryNames)
    val source = File(backupRoot, name)
    if (!source.hasFiles()) {
        total
    } else {
        Math.addExact(
            total,
            Math.addExact(source.directoryBytes(), File(externalFilesRoot, name).directoryBytes()),
        )
    }
}

internal fun remapRestoredCoverPath(
    path: String,
    backupRoot: File,
    externalFilesRoot: File,
): String? {
    val source = File(path)
    if (!source.isAbsolute || source.parentFile?.name != "covers") return path
    val target = File(externalFilesRoot, "covers/${source.name}")
    return target.absolutePath.takeIf {
        File(backupRoot, "covers/${source.name}").isFile || target.isFile
    }
}

internal fun prepareBackupMediaDirectories(
    externalFilesRoot: File,
    backupRoot: File,
    referencedBackgroundPaths: Collection<String>,
    includePersistedCovers: Boolean = true,
    includeOtherCovers: Boolean = true,
    includeBackgrounds: Boolean = true,
): List<File> {
    val directories = arrayListOf<File>()
    val coverRoot = File(externalFilesRoot, "covers").canonicalFile
    val stagedCoverRoot = File(backupRoot, "covers")
    stagedCoverRoot.deleteRecursively()
    if (includePersistedCovers && includeOtherCovers) {
        coverRoot.takeIf { it.hasFiles() }?.let(directories::add)
    } else if (includePersistedCovers || includeOtherCovers) {
        coverRoot.takeIf { it.isDirectory }?.walkTopDown()
            ?.filter { it.isFile }
            ?.filter { source ->
                val persisted = isLegacyPersistedCoverPath(source.absolutePath)
                if (persisted) includePersistedCovers else includeOtherCovers
            }
            ?.forEach { source ->
                val target = File(stagedCoverRoot, source.relativeTo(coverRoot).path)
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
            }
        stagedCoverRoot.takeIf { it.hasFiles() }?.let(directories::add)
    }

    val backgroundRoot = File(externalFilesRoot, "bg").canonicalFile
    val backgroundRootPrefix = backgroundRoot.path.trimEnd(File.separatorChar) + File.separator
    val stagedBackgroundRoot = File(backupRoot, "bg")
    stagedBackgroundRoot.deleteRecursively()
    if (includeBackgrounds) {
        referencedBackgroundPaths.asSequence()
            .map { path ->
                if (path.contains(File.separator)) File(path) else File(backgroundRoot, path)
            }
            .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
            .filter { it.isFile && it.path.startsWith(backgroundRootPrefix) }
            .distinctBy { it.path }
            .forEach { source ->
                val target = File(stagedBackgroundRoot, source.relativeTo(backgroundRoot).path)
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
            }
        stagedBackgroundRoot.takeIf { it.hasFiles() }?.let(directories::add)
    }
    return directories
}

private fun File.hasFiles(): Boolean {
    return isDirectory && runCatching {
        walkTopDown().any { it.isFile }
    }.getOrDefault(false)
}

private fun File.directoryBytes(): Long {
    if (!isDirectory) return 0L
    return walkTopDown().filter(File::isFile).fold(0L) { total, file ->
        Math.addExact(total, file.length())
    }
}

/**
 * Merges a restored media directory only after it has been copied completely.
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
    }
    val hasBackupFiles = source.isDirectory && runCatching {
        source.walkTopDown().any { it.isFile }
    }.getOrDefault(false)
    if (!hasBackupFiles && !previous.exists()) return Result.success(false)

    var rollbackReady = false
    return runCatching {
        if (previous.exists() && target.exists()) {
            check(target.copyRecursively(previous, overwrite = true)) {
                "Unable to update previous $directoryName"
            }
        }
        (previous.takeIf { it.exists() } ?: target.takeIf { it.exists() })?.let { baseline ->
            check(baseline.copyRecursively(staging, overwrite = true)) {
                "Unable to stage current $directoryName"
            }
        }
        if (hasBackupFiles) {
            check(source.copyRecursively(staging, overwrite = true)) {
                "Unable to stage $directoryName"
            }
        }
        if (target.exists()) {
            if (previous.exists()) {
                rollbackReady = true
                check(target.deleteRecursively()) {
                    "Unable to replace current $directoryName"
                }
            } else {
                check(target.renameTo(previous)) {
                    "Unable to preserve current $directoryName"
                }
                rollbackReady = true
            }
        }
        if (!staging.renameTo(target)) {
            error("Unable to activate restored $directoryName")
        }
        previous.deleteRecursively()
        true
    }.onFailure {
        staging.deleteRecursively()
        if (rollbackReady && previous.exists()) {
            target.deleteRecursively()
            previous.renameTo(target)
        }
    }
}
