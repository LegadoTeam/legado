package io.legado.app.help

import io.legado.app.data.appDb
import io.legado.app.utils.DebugLog
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.IOException

internal const val RULE_DATA_QUERY_BATCH_SIZE = 900
private const val RULE_DATA_LOG_TAG = "RuleBigDataHelp"

internal data class RuleDataDirectory(
    val directory: File,
    val key: String?,
)

internal fun ruleDataKeyBatches(
    keys: Iterable<String>,
    batchSize: Int = RULE_DATA_QUERY_BATCH_SIZE,
): List<List<String>> {
    require(batchSize > 0)
    return keys.distinct().chunked(batchSize)
}

internal fun findExistingRuleDataKeys(
    keys: Iterable<String>,
    batchSize: Int = RULE_DATA_QUERY_BATCH_SIZE,
    findExistingKeys: (List<String>) -> List<String>,
): Set<String> {
    val existingKeys = hashSetOf<String>()
    ruleDataKeyBatches(keys, batchSize).forEach { batch ->
        existingKeys.addAll(findExistingKeys(batch))
    }
    return existingKeys
}

internal fun clearInvalidRuleData(
    dataDir: File,
    markerFileName: String,
    findExistingKeys: (List<String>) -> List<String>,
    existsNow: (String) -> Boolean,
    accessLock: Any,
) {
    val directories = arrayListOf<RuleDataDirectory>()
    dataDir.listFiles()?.forEach { entry ->
        if (entry.isDirectory) {
            val keyResult = synchronized(accessLock) {
                entry.readRuleDataKey(markerFileName)
            }
            keyResult.onSuccess { key ->
                directories.add(
                    RuleDataDirectory(
                        directory = entry,
                        key = key,
                    )
                )
            }
        } else {
            deleteRuleDataEntry(entry)
        }
    }

    val existingKeys = findExistingRuleDataKeys(
        keys = directories.mapNotNull { it.key },
        findExistingKeys = findExistingKeys,
    )
    directories
        .filter { it.key == null || it.key !in existingKeys }
        .forEach { candidate ->
            synchronized(accessLock) {
                candidate.directory.readRuleDataKey(markerFileName).onSuccess { currentKey ->
                    if (currentKey == null || !existsNow(currentKey)) {
                        deleteRuleDataEntry(candidate.directory, deleteRootDir = true)
                    }
                }
            }
        }
}

private fun File.readRuleDataKey(markerFileName: String): Result<String?> {
    val markerFile = getFile(markerFileName)
    return when {
        !markerFile.exists() -> Result.success(null)
        !markerFile.isFile -> Result.failure(
            IOException("Rule data marker is not a file: ${markerFile.path}")
        )

        else -> runCatching { markerFile.readText() }
    }
}

private fun deleteRuleDataEntry(file: File, deleteRootDir: Boolean = false) {
    if (!FileUtils.delete(file, deleteRootDir)) {
        DebugLog.w(RULE_DATA_LOG_TAG, "Unable to delete invalid rule data: ${file.path}")
    }
}

object RuleBigDataHelp {

    private val ruleDataDir = FileUtils.createFolderIfNotExist(appCtx.externalFiles, "ruleData")
    private val bookData = FileUtils.createFolderIfNotExist(ruleDataDir, "book")
    private val rssData = FileUtils.createFolderIfNotExist(ruleDataDir, "rss")
    private val accessLock = Any()

    suspend fun clearInvalid() {
        withContext(IO) {
            clearInvalidRuleData(
                dataDir = bookData,
                markerFileName = "bookUrl.txt",
                findExistingKeys = { appDb.bookDao.findExistingBookUrls(it) },
                existsNow = { appDb.bookDao.has(it) },
                accessLock = accessLock,
            )
            clearInvalidRuleData(
                dataDir = rssData,
                markerFileName = "origin.txt",
                findExistingKeys = { appDb.rssSourceDao.findExistingSourceUrls(it) },
                existsNow = { appDb.rssSourceDao.has(it) },
                accessLock = accessLock,
            )
        }
    }

    fun putBookVariable(bookUrl: String, key: String, value: String?) {
        synchronized(accessLock) {
            val md5BookUrl = MD5Utils.md5Encode(bookUrl)
            val md5Key = MD5Utils.md5Encode(key)
            if (value == null) {
                FileUtils.delete(FileUtils.getPath(bookData, md5BookUrl, "$md5Key.txt"), true)
            } else {
                val valueFile =
                    FileUtils.createFileIfNotExist(bookData, md5BookUrl, "$md5Key.txt")
                valueFile.writeText(value)
                val bookUrlFile = File(FileUtils.getPath(bookData, md5BookUrl, "bookUrl.txt"))
                if (!bookUrlFile.exists()) {
                    bookUrlFile.writeText(bookUrl)
                }
            }
        }
    }

    fun getBookVariable(bookUrl: String, key: String?): String? {
        synchronized(accessLock) {
            val md5BookUrl = MD5Utils.md5Encode(bookUrl)
            val md5Key = MD5Utils.md5Encode(key)
            val file = File(FileUtils.getPath(bookData, md5BookUrl, "$md5Key.txt"))
            if (file.exists()) {
                return file.readText()
            }
            return null
        }
    }

    fun hasBookVariable(bookUrl: String, key: String): Boolean {
        synchronized(accessLock) {
            val md5BookUrl = MD5Utils.md5Encode(bookUrl)
            val md5Key = MD5Utils.md5Encode(key)
            val file = File(FileUtils.getPath(bookData, md5BookUrl, "$md5Key.txt"))
            return file.exists()
        }
    }

    fun putChapterVariable(bookUrl: String, chapterUrl: String, key: String, value: String?) {
        synchronized(accessLock) {
            val md5BookUrl = MD5Utils.md5Encode(bookUrl)
            val md5ChapterUrl = MD5Utils.md5Encode(chapterUrl)
            val md5Key = MD5Utils.md5Encode(key)
            if (value == null) {
                FileUtils.delete(
                    FileUtils.getPath(bookData, md5BookUrl, md5ChapterUrl, "$md5Key.txt")
                )
            } else {
                val valueFile = FileUtils.createFileIfNotExist(
                    bookData,
                    md5BookUrl,
                    md5ChapterUrl,
                    "$md5Key.txt",
                )
                valueFile.writeText(value)
                val bookUrlFile = File(FileUtils.getPath(bookData, md5BookUrl, "bookUrl.txt"))
                if (!bookUrlFile.exists()) {
                    bookUrlFile.writeText(bookUrl)
                }
            }
        }
    }

    fun getChapterVariable(bookUrl: String, chapterUrl: String, key: String): String? {
        synchronized(accessLock) {
            val md5BookUrl = MD5Utils.md5Encode(bookUrl)
            val md5ChapterUrl = MD5Utils.md5Encode(chapterUrl)
            val md5Key = MD5Utils.md5Encode(key)
            val file =
                File(FileUtils.getPath(bookData, md5BookUrl, md5ChapterUrl, "$md5Key.txt"))
            if (file.exists()) {
                return file.readText()
            }
            return null
        }
    }

    fun getDanmakuFile(bookUrl: String, chapterUrl: String): File? {
        synchronized(accessLock) {
            val md5BookUrl = MD5Utils.md5Encode(bookUrl)
            val md5ChapterUrl = MD5Utils.md5Encode(chapterUrl)
            val md5Key = MD5Utils.md5Encode("danmaku")
            val file =
                File(FileUtils.getPath(bookData, md5BookUrl, md5ChapterUrl, "$md5Key.txt"))
            if (file.exists()) {
                return file
            }
            return null
        }
    }

    fun putRssVariable(origin: String, link: String, key: String, value: String?) {
        synchronized(accessLock) {
            val md5Origin = MD5Utils.md5Encode(origin)
            val md5Link = MD5Utils.md5Encode(link)
            val md5Key = MD5Utils.md5Encode(key)
            val filePath = FileUtils.getPath(rssData, md5Origin, md5Link, "$md5Key.txt")
            if (value == null) {
                FileUtils.delete(filePath)
            } else {
                val valueFile = FileUtils.createFileIfNotExist(filePath)
                valueFile.writeText(value)
                val originFile = File(FileUtils.getPath(rssData, md5Origin, "origin.txt"))
                if (!originFile.exists()) {
                    originFile.writeText(origin)
                }
                val linkFile = File(FileUtils.getPath(rssData, md5Origin, md5Link, "origin.txt"))
                if (!linkFile.exists()) {
                    linkFile.writeText(link)
                }
            }
        }
    }

    fun getRssVariable(origin: String, link: String, key: String): String? {
        synchronized(accessLock) {
            val md5Origin = MD5Utils.md5Encode(origin)
            val md5Link = MD5Utils.md5Encode(link)
            val md5Key = MD5Utils.md5Encode(key)
            val filePath = FileUtils.getPath(rssData, md5Origin, md5Link, "$md5Key.txt")
            val file = File(filePath)
            if (file.exists()) {
                return file.readText()
            }
            return null
        }
    }
}
