package io.legado.app.help.book

import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadRecord
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** History owns these copies independently of the bookshelf and Glide's evictable cache. */
object ReadRecordCoverCache {
    const val DIRECTORY = "readRecordCovers"
    private val root get() = File(appCtx.externalFiles, DIRECTORY)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(2)
    private val pending = ConcurrentHashMap.newKeySet<Triple<String, String, String>>()
    private val lock = Any()

    fun request(record: ReadRecord, sourceOrigin: String? = null) {
        val path = record.coverUrl?.takeIf { it.isNotBlank() } ?: return
        if (ownedFile(path)?.isFile == true) return
        val key = Triple(record.deviceId, record.bookName, path)
        if (!pending.add(key)) return
        scope.launch {
            try {
                permits.withPermit {
                    val targetFile = File(root, MD5Utils.md5Encode(path) + ".cover")
                    if (targetFile.isFile) {
                        synchronized(lock) { attach(record, path, targetFile) }
                        return@withPermit
                    }
                    var options = RequestOptions().set(
                        OkHttpModelLoader.loadOnlyWifiOption, AppConfig.loadCoverOnlyWifi,
                    )
                    sourceOrigin?.let { options = options.set(OkHttpModelLoader.sourceOriginOption, it) }
                    val target = ImageLoader.loadFile(appCtx, path).apply(options).submit()
                    try {
                        val downloaded = runInterruptible { target.get(10, TimeUnit.SECONDS) }
                        synchronized(lock) {
                            val current = appDb.readRecordDao.getRecord(record.deviceId, record.bookName)
                            if (current?.coverUrl == path) {
                                install(downloaded, targetFile)
                                attach(record, path, targetFile)
                            }
                        }
                    } finally {
                        Glide.with(appCtx).clear(target)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A later reading session can retry; the original URL stays in the record.
            } finally {
                pending.remove(key)
            }
        }
    }

    fun retainLocal(path: String?): String? = synchronized(lock) {
        if (path.isNullOrBlank()) return@synchronized path
        if (ownedFile(path)?.isFile == true) return@synchronized path
        val source = File(path)
        if (!source.isAbsolute || !source.isFile) return@synchronized path
        runCatching {
            val target = File(root, MD5Utils.md5Encode(path) + ".cover")
            install(source, target)
            target.absolutePath
        }.getOrDefault(path)
    }

    fun prune() = synchronized(lock) {
        val referenced = appDb.readRecordDao.all.mapNotNull { ownedFile(it.coverUrl)?.name }.toSet()
        root.listFiles()?.filter { it.isFile && it.name !in referenced }?.forEach(File::delete)
        Unit
    }

    private fun ownedFile(path: String?): File? {
        val file = path?.let(::File) ?: return null
        return file.takeIf {
            runCatching { it.canonicalFile.parentFile == root.canonicalFile }.getOrDefault(false)
        }
    }

    private fun attach(record: ReadRecord, expected: String, file: File) {
        if (file.isFile) {
            appDb.readRecordDao.updateCoverIfUnchanged(record.deviceId, record.bookName, expected, file.absolutePath)
        }
    }

    private fun install(source: File, target: File) {
        require(source.length() > 0L)
        check(root.isDirectory || root.mkdirs())
        val temporary = File.createTempFile(".history-", ".part", root)
        try {
            source.copyTo(temporary, overwrite = true)
            if (!target.exists()) check(temporary.renameTo(target))
        } finally {
            temporary.delete()
        }
    }
}
