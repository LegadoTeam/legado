package io.legado.app.help.book

import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadRecord
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.utils.externalFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val pending = ConcurrentHashMap<List<String>, Boolean>()

    fun request(record: ReadRecord, sourceOrigin: String? = null): Job? {
        val path = record.coverUrl?.takeIf { it.isNotBlank() } ?: return null
        if (ownedFile(path)?.isFile == true) return null
        val key = listOf(record.deviceId, record.bookName, record.author, path)
        if (pending.putIfAbsent(key, true) != null) return null
        return scope.launch {
            try {
                permits.withPermit {
                    var options = RequestOptions().set(
                        OkHttpModelLoader.loadOnlyWifiOption, AppConfig.loadCoverOnlyWifi,
                    )
                    sourceOrigin?.let { options = options.set(OkHttpModelLoader.sourceOriginOption, it) }
                    val target = ImageLoader.loadFile(appCtx, path).apply(options).submit()
                    try {
                        val downloaded = runInterruptible { target.get(10, TimeUnit.SECONDS) }
                        val validation = Glide.with(appCtx).load(downloaded).submit(1, 1)
                        try {
                            runInterruptible { validation.get(10, TimeUnit.SECONDS) }
                        } finally {
                            Glide.with(appCtx).clear(validation)
                        }
                        appDb.runInTransaction {
                            val current = appDb.readRecordDao.getRecord(record.deviceId, record.bookName, record.author)
                            if (current?.coverUrl == path) {
                                val targetFile = installPersistentCover(downloaded, root)
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

    // Called inside the snapshot transaction, so pruning cannot remove an uncommitted copy.
    fun retainLocal(path: String?): String? {
        if (path.isNullOrBlank()) return path
        if (ownedFile(path)?.isFile == true) return path
        val source = File(path)
        if (!source.isAbsolute || !source.isFile) return path
        return runCatching {
            installPersistentCover(source, root).absolutePath
        }.getOrDefault(path)
    }

    fun prune() = appDb.runInTransaction {
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
            appDb.readRecordDao.updateCoverIfUnchanged(record.deviceId, record.bookName, record.author, expected, file.absolutePath)
        }
    }

}
