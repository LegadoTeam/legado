package io.legado.app.help.audio

import androidx.annotation.WorkerThread
import androidx.core.net.toUri
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.model.AudioCacheKey
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.FileDoc
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.checkWrite
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.delete
import io.legado.app.utils.exists
import io.legado.app.utils.list
import io.legado.app.utils.openInputStream
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal data class CachedAudio(
    val mediaUri: String,
    val playUrl: String?,
)

object AudioCacheManager {

    private const val USER_CACHE_FOLDER = "LegadoAudioCache"
    private const val COMPLETE_SUFFIX = ".complete"
    private const val STALE_PARTIAL_AGE_MILLIS = 60 * 60 * 1000L
    private val chapterLocks = Array(16) { Mutex() }

    @WorkerThread
    fun isCacheDirAvailable(treeUri: String?): Boolean {
        return getCacheRoot(treeUri, requireWritable = true) != null
    }

    @WorkerThread
    fun getCachedUriString(
        treeUri: String?,
        bookUrl: String,
        chapter: BookChapter,
    ): String? {
        return getCachedAudio(treeUri, bookUrl, chapter)?.mediaUri
    }

    @WorkerThread
    internal fun getCachedAudio(
        treeUri: String?,
        bookUrl: String,
        chapter: BookChapter,
    ): CachedAudio? {
        return runCatching {
            val folder = getBookFolder(treeUri, bookUrl) ?: return null
            val file = findCachedFile(folder, AudioCacheKey.from(chapter)) ?: return null
            CachedAudio(
                mediaUri = file.uri.toString(),
                playUrl = readCompletePlayUrl(folder, file.name),
            )
        }.getOrNull()
    }

    @WorkerThread
    fun listCachedChapterKeys(treeUri: String?, bookUrl: String): Set<AudioCacheKey> {
        val folder = getBookFolder(treeUri, bookUrl) ?: return emptySet()
        return committedCacheFiles(folder)
            .mapNotNull { AudioCachePolicy.parseFileName(it.name)?.key }
            .toSet()
    }

    suspend fun removeCachedChapter(
        treeUri: String?,
        bookUrl: String,
        chapter: BookChapter,
    ): Boolean {
        return removeCachedChapter(treeUri, bookUrl, AudioCacheKey.from(chapter))
    }

    internal suspend fun removeCachedChapter(
        treeUri: String?,
        bookUrl: String,
        key: AudioCacheKey,
    ): Boolean {
        return withContext(Dispatchers.IO) {
            chapterLock(bookUrl, key).withLock {
                val folder = getBookFolder(treeUri, bookUrl) ?: return@withLock false
                if (!hasCacheFiles(folder, key)) return@withLock false
                removeCacheFiles(folder, key)
                !hasCacheFiles(folder, key)
            }
        }
    }

    suspend fun cacheChapter(
        treeUri: String?,
        bookSource: BookSource,
        book: Book,
        chapter: BookChapter,
    ): Result<String> {
        return runCatchingCancellable {
            withContext(Dispatchers.IO) {
                if (chapter.isVolume) throw IllegalStateException("分卷章节不支持缓存")
                val key = AudioCacheKey.from(chapter)
                chapterLock(book.bookUrl, key).withLock {
                    cacheChapterLocked(
                        treeUri = treeUri,
                        bookSource = bookSource,
                        book = book,
                        chapter = chapter,
                        key = key,
                    )
                }
            }
        }.onFailure {
            AppLog.put("缓存音频失败 ${book.name}-${chapter.title}\n${it.localizedMessage}", it)
        }
    }

    private suspend fun cacheChapterLocked(
        treeUri: String?,
        bookSource: BookSource,
        book: Book,
        chapter: BookChapter,
        key: AudioCacheKey,
    ): String {
        val playUrl = WebBook.getContentAwait(
            bookSource,
            book,
            chapter,
            needSave = false
        )
        AudioCachePolicy.requireCacheablePlayUrl(playUrl)
        val folder = requireBookFolder(treeUri, book.bookUrl)
        cleanupUncommittedFiles(folder, key)
        val response = AnalyzeUrl(
            playUrl,
            source = bookSource,
            ruleData = book,
            chapter = chapter,
            coroutineContext = currentCoroutineContext()
        ).getResponseAwait()
        response.use {
            if (!it.isSuccessful) throw IllegalStateException("网络请求失败(${it.code})")
            val extension = AudioCachePolicy.detectExtension(
                contentType = it.header("Content-Type"),
                finalUrl = it.request.url.toString(),
                playUrl = playUrl
            )
            val revision = UUID.randomUUID().toString().replace("-", "").take(8)
            val finalName = AudioCachePolicy.buildFileName(
                chapterIndex = chapter.index,
                key = key,
                chapterTitle = chapter.title,
                playUrlHash = MD5Utils.md5Encode16(playUrl),
                revision = revision,
                extension = extension
            )
            val staged = folder.createFileIfNotExist(
                "tmp_${key.value}_${revision}_${UUID.randomUUID()}.part"
            )
            val expectedSize = it.body.contentLength().takeIf { size -> size > 0L }
            var installed: FileDoc? = null
            try {
                it.body.byteStream().use { input ->
                    staged.openOutputStream().getOrThrow().use { output ->
                        copyCancellable(input, output)
                    }
                }
                requireComplete(staged, expectedSize)
                val installedFile = installStagedFile(folder, staged, finalName, expectedSize)
                installed = installedFile
                currentCoroutineContext().ensureActive()
                createCompleteMarker(folder, installedFile, playUrl)
                currentCoroutineContext().ensureActive()
                runCatching { removeCacheFiles(folder, key, installedFile.name) }
                return installedFile.uri.toString()
            } catch (e: Throwable) {
                installed?.let { file ->
                    runCatching { file.delete() }
                    runCatching { deleteCompleteMarker(folder, file.name) }
                }
                runCatching { staged.delete() }
                throw e
            }
        }
    }

    private fun findCachedFile(folder: FileDoc, key: AudioCacheKey): FileDoc? {
        return committedCacheFiles(folder, key).maxByOrNull { it.lastModified }
    }

    private fun getBookFolder(treeUri: String?, bookUrl: String): FileDoc? {
        val root = getCacheRoot(treeUri) ?: return null
        val cacheRoot = root.list { it.isDir && it.name == USER_CACHE_FOLDER }
            ?.firstOrNull()
            ?: return null
        val bookFolderName = "book_${MD5Utils.md5Encode16(bookUrl)}"
        return cacheRoot.list { it.isDir && it.name == bookFolderName }?.firstOrNull()
    }

    private fun getCacheRoot(treeUri: String?, requireWritable: Boolean = false): FileDoc? {
        if (treeUri.isNullOrBlank()) return null
        return runCatching {
            FileDoc.fromDir(treeUri.toUri()).takeIf {
                it.exists() && (!requireWritable || it.checkWrite())
            }
        }.getOrNull()
    }

    private fun requireBookFolder(treeUri: String?, bookUrl: String): FileDoc {
        val root = getCacheRoot(treeUri, requireWritable = true)
            ?: throw IllegalStateException("音频缓存目录不可用")
        return root.createFolderIfNotExist(
            USER_CACHE_FOLDER,
            "book_${MD5Utils.md5Encode16(bookUrl)}"
        )
    }

    private suspend fun installStagedFile(
        folder: FileDoc,
        staged: FileDoc,
        finalName: String,
        expectedSize: Long?,
    ): FileDoc {
        currentCoroutineContext().ensureActive()
        rename(staged, finalName)?.let { renamed ->
            return try {
                currentCoroutineContext().ensureActive()
                requireComplete(renamed, expectedSize)
            } catch (e: Throwable) {
                runCatching { renamed.delete() }
                throw e
            }
        }

        val installed = folder.createFileIfNotExist(finalName)
        try {
            staged.openInputStream().getOrThrow().use { input ->
                installed.openOutputStream().getOrThrow().use { output ->
                    copyCancellable(input, output)
                }
            }
            val complete = requireComplete(installed, expectedSize)
            currentCoroutineContext().ensureActive()
            staged.delete()
            return complete
        } catch (e: Throwable) {
            runCatching { installed.delete() }
            throw e
        }
    }

    private fun createCompleteMarker(folder: FileDoc, installed: FileDoc, playUrl: String) {
        val marker = folder.createFileIfNotExist(installed.name + COMPLETE_SUFFIX)
        try {
            marker.openOutputStream().getOrThrow().use { output ->
                output.write(AudioCacheMetadata.encode(playUrl).toByteArray(Charsets.UTF_8))
                output.flush()
            }
        } catch (error: Throwable) {
            runCatching { marker.delete() }
            throw error
        }
        if (readCompletePlayUrl(marker) != playUrl) {
            runCatching { marker.delete() }
            throw IllegalStateException("音频缓存完成标记校验失败")
        }
    }

    private fun readCompletePlayUrl(folder: FileDoc, fileName: String): String? {
        val marker = folder.list {
            !it.isDir && it.name == fileName + COMPLETE_SUFFIX
        }?.firstOrNull() ?: return null
        return readCompletePlayUrl(marker)
    }

    private fun readCompletePlayUrl(marker: FileDoc): String? {
        val metadata = marker.openInputStream().getOrNull()?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: return null
        return AudioCacheMetadata.decode(metadata)
    }

    private fun deleteCompleteMarker(folder: FileDoc, fileName: String) {
        folder.list { !it.isDir && it.name == fileName + COMPLETE_SUFFIX }
            ?.forEach { it.delete() }
    }

    private fun cleanupUncommittedFiles(folder: FileDoc, key: AudioCacheKey) {
        val files = folder.list() ?: return
        val staleBefore = System.currentTimeMillis() - STALE_PARTIAL_AGE_MILLIS
        val completeNames = files.asSequence()
            .filter { !it.isDir && it.name.endsWith(COMPLETE_SUFFIX) }
            .map { it.name.removeSuffix(COMPLETE_SUFFIX) }
            .toHashSet()
        files.filter {
            val uncommitted = isTemporaryFile(it, key) ||
                    (isCacheFile(it, key) && it.name !in completeNames)
            uncommitted && it.lastModified in 1..staleBefore
        }.forEach { it.delete() }
    }

    private fun rename(file: FileDoc, newName: String): FileDoc? {
        file.asFile()?.let { source ->
            val parent = source.parentFile ?: return null
            val target = File(parent, newName)
            if (source.renameTo(target)) return FileDoc.fromFile(target)
        }
        file.asDocumentFile()?.let { document ->
            if (document.renameTo(newName)) return FileDoc.fromDocumentFile(document)
        }
        return null
    }

    private fun requireComplete(file: FileDoc, expectedSize: Long?): FileDoc {
        val refreshed = FileDoc.fromUri(file.uri, false)
        if (!AudioCachePolicy.isCompleteFile(refreshed.size, expectedSize)) {
            throw IllegalStateException(
                if (refreshed.size <= 0L) "音频文件为空" else "音频文件不完整"
            )
        }
        return refreshed
    }

    private fun removeCacheFiles(
        folder: FileDoc,
        key: AudioCacheKey,
        exceptFileName: String? = null,
    ): Int {
        val files = folder.list() ?: return 0
        val dataTargets = files.filter {
            it.name != exceptFileName &&
                    (isCacheFile(it, key) || isTemporaryFile(it, key))
        }
        val markerTargets = files.filter {
            if (it.isDir || !it.name.endsWith(COMPLETE_SUFFIX)) return@filter false
            val dataName = it.name.removeSuffix(COMPLETE_SUFFIX)
            dataName != exceptFileName &&
                    AudioCachePolicy.parseFileName(dataName)?.key == key
        }
        (dataTargets + markerTargets).forEach { it.delete() }
        return dataTargets.size
    }

    private fun committedCacheFiles(
        folder: FileDoc,
        key: AudioCacheKey? = null,
    ): List<FileDoc> {
        val files = folder.list() ?: return emptyList()
        val completeNames = files.asSequence()
            .filter {
                !it.isDir &&
                        it.name.endsWith(COMPLETE_SUFFIX)
            }
            .map { it.name.removeSuffix(COMPLETE_SUFFIX) }
            .toHashSet()
        return files.filter {
            it.size > 0L && it.name in completeNames && isCacheFile(it, key)
        }
    }

    private fun hasCacheFiles(folder: FileDoc, key: AudioCacheKey): Boolean {
        return folder.list()?.any {
            isCacheFile(it, key) || isTemporaryFile(it, key) ||
                    (!it.isDir && it.name.endsWith(COMPLETE_SUFFIX) &&
                            AudioCachePolicy.parseFileName(
                                it.name.removeSuffix(COMPLETE_SUFFIX)
                            )?.key == key)
        } == true
    }

    private fun isTemporaryFile(file: FileDoc, key: AudioCacheKey): Boolean {
        return !file.isDir &&
                file.name.startsWith("tmp_${key.value}_") &&
                file.name.endsWith(".part")
    }

    private fun isCacheFile(file: FileDoc, key: AudioCacheKey? = null): Boolean {
        if (file.isDir) return false
        val parsed = AudioCachePolicy.parseFileName(file.name) ?: return false
        return key == null || parsed.key == key
    }

    internal suspend fun copyCancellable(input: InputStream, output: OutputStream): Long {
        val context = currentCoroutineContext()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            context.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            total += count
        }
        context.ensureActive()
        output.flush()
        context.ensureActive()
        return total
    }

    private fun chapterLock(bookUrl: String, key: AudioCacheKey): Mutex {
        return chapterLocks[Math.floorMod(31 * bookUrl.hashCode() + key.hashCode(), chapterLocks.size)]
    }
}
