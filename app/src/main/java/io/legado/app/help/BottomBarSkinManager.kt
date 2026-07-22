package io.legado.app.help

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.StateListDrawable
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefString
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefString
import splitties.init.appCtx
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object BottomBarSkinManager {

    private const val SESSION_DIR_NAME = ".staging"
    private const val SAVE_DIR_PREFIX = ".save-"
    private const val BACKUP_DIR_PREFIX = ".backup-"
    private const val DELETE_DIR_PREFIX = ".delete-"
    private const val SESSION_MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L
    private const val MAX_SOURCE_DIMENSION = 8192
    private const val MAX_SOURCE_PIXELS = 32L * 1024 * 1024
    private const val STORED_ICON_SIZE = 512

    private val rootDir: File by lazy {
        File(appCtx.filesDir, "bottomBarSkins").apply {
            require(isDirectory || mkdirs()) { "cannot create skin directory" }
            recoverInterruptedSaves(this)
        }
    }

    private val sessionRoot: File by lazy {
        File(rootDir, SESSION_DIR_NAME).apply {
            require(isDirectory || mkdirs()) { "cannot create staging directory" }
        }
    }

    private var rawActive: String
        get() = appCtx.getPrefString(PreferKey.bottomBarSkin).orEmpty()
        set(value) = appCtx.putPrefString(PreferKey.bottomBarSkin, value)

    var active: String
        get() = rawActive.takeIf { it.isNotEmpty() && hasSkin(it) }.orEmpty()
        set(value) {
            rawActive = value.takeIf { it.isEmpty() || hasSkin(it) }.orEmpty()
        }

    fun list(): List<String> = runCatching {
        rootDir.listFiles { file ->
            file.isDirectory && BottomBarSkinFormat.isValidSkinName(file.name)
        }?.map(File::getName)?.sorted().orEmpty()
    }.getOrDefault(emptyList())

    fun hasSkin(name: String): Boolean {
        return resolveSkinDir(name)?.isDirectory == true
    }

    @Synchronized
    fun delete(name: String): Boolean {
        val dir = resolveSkinDir(name)?.takeIf(File::isDirectory) ?: return false
        val wasActive = rawActive == name
        val pendingDeletes = ArrayList<File>(2)
        val backup = File(rootDir, "$BACKUP_DIR_PREFIX$name")
        if (backup.exists()) {
            val pendingBackup = File(rootDir, "$DELETE_DIR_PREFIX${UUID.randomUUID()}")
            if (!backup.renameTo(pendingBackup)) return false
            pendingDeletes += pendingBackup
        }
        val pendingSkin = File(rootDir, "$DELETE_DIR_PREFIX${UUID.randomUUID()}")
        if (wasActive) rawActive = ""
        if (!dir.renameTo(pendingSkin)) {
            if (wasActive) rawActive = name
            pendingDeletes.forEach(File::deleteRecursively)
            return false
        }
        pendingDeletes += pendingSkin
        pendingDeletes.forEach(File::deleteRecursively)
        return true
    }

    fun getStateDrawable(skinName: String, slot: String, iconSizePx: Int): StateListDrawable? {
        if (slot !in BottomBarSkinFormat.MAPPED_SLOTS) return null
        val dir = resolveSkinDir(skinName)?.takeIf(File::isDirectory) ?: return null
        val resources = appCtx.resources
        val selected = decodeSquared(File(dir, "${slot}_selected.png"), iconSizePx) ?: return null
        val normal = decodeSquared(File(dir, "${slot}_normal.png"), iconSizePx)
        val normalDrawable = if (normal != null) {
            BitmapDrawable(resources, normal)
        } else {
            BitmapDrawable(resources, selected).apply { alpha = 102 }
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), BitmapDrawable(resources, selected))
            addState(intArrayOf(), normalDrawable)
        }
    }

    fun getPreviewBitmaps(skinName: String, iconSizePx: Int): List<Bitmap> {
        val dir = resolveSkinDir(skinName)?.takeIf(File::isDirectory) ?: return emptyList()
        return BottomBarSkinFormat.MAPPED_SLOTS.mapNotNull { slot ->
            decodeSquared(File(dir, "${slot}_selected.png"), iconSizePx)
        }
    }

    data class Prefill(val selected: File?, val normal: File?)

    data class SlotAssign(val selected: File, val normal: File?)

    fun extractImages(input: InputStream): Result<String> = runCatching {
        val session = createSessionDir()
        try {
            val extracted = BottomBarSkinArchive.extract(input, session)
            extracted.filterNot(::isUsableImage).forEach(File::delete)
            require(sessionImages(session).isNotEmpty()) { "no decodable images" }
            session.name
        } catch (error: Throwable) {
            session.deleteRecursively()
            throw error
        }
    }

    fun stageExisting(skinName: String): Result<String> = runCatching {
        val source = resolveSkinDir(skinName)?.takeIf(File::isDirectory)
            ?: error("skin not found")
        val session = createSessionDir()
        try {
            val files = canonicalSkinFiles(source)
            var totalBytes = 0L
            files.forEach { file ->
                require(isUsableImage(file)) { "invalid image" }
                totalBytes += file.length()
                require(totalBytes <= BottomBarSkinArchive.MAX_TOTAL_BYTES) { "skin too large" }
                file.copyTo(File(session, file.name), overwrite = false)
            }
            require(sessionImages(session).isNotEmpty()) { "no images" }
            session.name
        } catch (error: Throwable) {
            session.deleteRecursively()
            throw error
        }
    }

    fun stagingImages(sessionId: String): List<File> {
        val session = resolveSessionDir(sessionId)?.takeIf(File::isDirectory) ?: return emptyList()
        return sessionImages(session)
    }

    fun discardSession(sessionId: String) {
        resolveSessionDir(sessionId)?.deleteRecursively()
    }

    fun buildPrefill(images: List<File>): Map<String, Prefill> {
        val selected = HashMap<String, File>()
        val normal = HashMap<String, File>()
        images.forEach { file ->
            val entry = BottomBarSkinFormat.parseEntryName(file.name) ?: return@forEach
            if (entry.selected) {
                selected.putIfAbsent(entry.slot, file)
            } else {
                normal.putIfAbsent(entry.slot, file)
            }
        }
        return (selected.keys + normal.keys).associateWith {
            Prefill(selected[it], normal[it])
        }
    }

    @Synchronized
    fun saveSkin(
        desiredName: String,
        assigns: Map<String, SlotAssign>,
        sessionId: String,
        editName: String? = null,
    ): Result<String> = runCatching {
        require(assigns.isNotEmpty()) { "no assignment" }
        require(assigns.keys.all { it in BottomBarSkinFormat.MAPPED_SLOTS }) { "invalid slot" }
        val session = resolveSessionDir(sessionId)?.takeIf(File::isDirectory)
            ?: error("staging session not found")
        val currentNames = list()
        val sanitized = BottomBarSkinFormat.sanitize(desiredName)
        val editDir = editName?.let {
            resolveSkinDir(it)?.takeIf(File::isDirectory) ?: error("skin not found")
        }
        val finalName = when {
            editName == null -> BottomBarSkinFormat.uniqueName(sanitized, currentNames)
            sanitized == editName -> editName
            else -> BottomBarSkinFormat.uniqueName(sanitized, currentNames - editName)
        }
        val target = resolveSkinDir(finalName) ?: error("invalid skin name")
        val temp = File(rootDir, "$SAVE_DIR_PREFIX${UUID.randomUUID()}")
        require(temp.mkdirs()) { "cannot create temporary skin" }

        try {
            writeAssigns(temp, session, assigns)
            if (editDir != null && editDir != target) {
                require(!target.exists()) { "skin already exists" }
                require(temp.renameTo(target)) { "cannot save skin" }
                val wasActive = rawActive == editName
                if (wasActive) rawActive = finalName
                val deleted = File(rootDir, "$DELETE_DIR_PREFIX${UUID.randomUUID()}")
                if (!editDir.renameTo(deleted)) {
                    if (wasActive) rawActive = editName.orEmpty()
                    target.deleteRecursively()
                    error("cannot rename skin")
                }
                deleted.deleteRecursively()
            } else {
                commitDirectory(temp, target)
            }
            if (editName == null || rawActive == editName) rawActive = finalName
            session.deleteRecursively()
            postEvent(EventBus.BOTTOM_BAR_SKIN, "")
            finalName
        } finally {
            temp.deleteRecursively()
        }
    }

    fun buildZipBytes(skinName: String): Result<ByteArray> = runCatching {
        val dir = resolveSkinDir(skinName)?.takeIf(File::isDirectory)
            ?: error("skin not found")
        val files = canonicalSkinFiles(dir)
        require(files.isNotEmpty()) { "empty skin" }
        require(files.all { it.length() in 1L..BottomBarSkinArchive.MAX_ENTRY_BYTES.toLong() }) {
            "invalid image size"
        }
        require(files.sumOf(File::length) <= BottomBarSkinArchive.MAX_TOTAL_BYTES) {
            "skin too large"
        }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        require(output.size() <= BottomBarSkinArchive.MAX_ARCHIVE_BYTES) { "skin zip too large" }
        output.toByteArray()
    }

    fun cacheShareZip(skinName: String): Result<File> = runCatching {
        val bytes = buildZipBytes(skinName).getOrThrow()
        val root = File(appCtx.cacheDir, "bottomBarSkinShare").apply {
            require(isDirectory || mkdirs()) { "cannot create share directory" }
        }
        cleanupStaleDirectories(root)
        val dir = File(root, UUID.randomUUID().toString())
        require(dir.mkdirs()) { "cannot create share session" }
        try {
            File(dir, "bottom-bar-skin.zip").apply { writeBytes(bytes) }
        } catch (error: Throwable) {
            dir.deleteRecursively()
            throw error
        }
    }

    fun previewBitmap(file: File, sizePx: Int): Bitmap? = decodeSquared(file, sizePx)

    private fun createSessionDir(): File {
        cleanupStaleSessions()
        val dir = File(sessionRoot, UUID.randomUUID().toString())
        require(dir.mkdirs()) { "cannot create staging session" }
        return dir
    }

    private fun cleanupStaleSessions() {
        cleanupStaleDirectories(sessionRoot)
    }

    private fun cleanupStaleDirectories(root: File) {
        val cutoff = System.currentTimeMillis() - SESSION_MAX_AGE_MILLIS
        root.listFiles { file -> file.isDirectory && file.lastModified() < cutoff }
            ?.forEach(File::deleteRecursively)
    }

    private fun recoverInterruptedSaves(root: File) {
        root.listFiles { file ->
            file.isDirectory && (
                file.name.startsWith(SAVE_DIR_PREFIX) ||
                    file.name.startsWith(DELETE_DIR_PREFIX)
                )
        }
            ?.forEach(File::deleteRecursively)
        root.listFiles { file -> file.isDirectory && file.name.startsWith(BACKUP_DIR_PREFIX) }
            ?.forEach { backup ->
                val name = backup.name.removePrefix(BACKUP_DIR_PREFIX)
                if (!BottomBarSkinFormat.isValidSkinName(name)) {
                    backup.deleteRecursively()
                    return@forEach
                }
                val target = File(root, name)
                if (target.exists()) {
                    backup.deleteRecursively()
                } else {
                    require(backup.renameTo(target)) { "cannot restore skin backup" }
                }
            }
    }

    private fun resolveSkinDir(name: String): File? = runCatching {
        if (!BottomBarSkinFormat.isValidSkinName(name)) return null
        val root = rootDir.canonicalFile
        File(root, name).canonicalFile.takeIf { it.parentFile == root }
    }.getOrNull()

    private fun resolveSessionDir(sessionId: String): File? = runCatching {
        if (UUID.fromString(sessionId).toString() != sessionId) return null
        val root = sessionRoot.canonicalFile
        File(root, sessionId).canonicalFile.takeIf { it.parentFile == root }
    }.getOrNull()

    private fun sessionImages(session: File): List<File> {
        val root = session.canonicalFile
        return session.listFiles { file ->
            file.isFile &&
                file.canonicalFile.parentFile == root &&
                BottomBarSkinFormat.isImageName(file.name) &&
                isUsableImage(file)
        }?.sortedBy(File::getName).orEmpty()
    }

    private fun canonicalSkinFiles(dir: File): List<File> {
        return BottomBarSkinFormat.MAPPED_SLOTS.flatMap { slot ->
            listOf("${slot}_selected.png", "${slot}_normal.png")
        }.map { File(dir, it) }.filter(File::isFile)
    }

    private fun requireSessionFile(session: File, file: File): File {
        val canonicalSession = session.canonicalFile
        val canonicalFile = file.canonicalFile
        require(canonicalFile.isFile && canonicalFile.parentFile == canonicalSession) {
            "image is outside staging session"
        }
        require(isUsableImage(canonicalFile)) { "invalid image" }
        return canonicalFile
    }

    private fun writeAssigns(
        destination: File,
        session: File,
        assigns: Map<String, SlotAssign>,
    ) {
        assigns.forEach { (slot, assign) ->
            require(slot in BottomBarSkinFormat.MAPPED_SLOTS) { "invalid slot" }
            writePng(
                requireSessionFile(session, assign.selected),
                File(destination, "${slot}_selected.png"),
            )
            assign.normal?.let {
                writePng(
                    requireSessionFile(session, it),
                    File(destination, "${slot}_normal.png"),
                )
            }
        }
    }

    private fun writePng(source: File, destination: File) {
        val bounds = readBounds(source) ?: error("invalid image")
        var sample = 1
        while (maxOf(bounds.first, bounds.second) / sample > STORED_ICON_SIZE * 2) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: error("invalid image")
        val maxSide = maxOf(bitmap.width, bitmap.height)
        val stored = if (maxSide > STORED_ICON_SIZE) {
            val scale = STORED_ICON_SIZE.toFloat() / maxSide
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        try {
            FileOutputStream(destination).use {
                require(stored.compress(Bitmap.CompressFormat.PNG, 100, it)) { "cannot encode image" }
            }
            require(destination.length() <= BottomBarSkinArchive.MAX_ENTRY_BYTES) {
                "encoded image too large"
            }
        } finally {
            if (stored !== bitmap) stored.recycle()
            bitmap.recycle()
        }
    }

    private fun commitDirectory(temp: File, target: File) {
        if (!target.exists()) {
            require(temp.renameTo(target)) { "cannot save skin" }
            return
        }
        val backup = File(rootDir, "$BACKUP_DIR_PREFIX${target.name}")
        if (backup.exists()) {
            require(backup.deleteRecursively()) { "cannot clear stale skin backup" }
        }
        require(target.renameTo(backup)) { "cannot back up skin" }
        if (!temp.renameTo(target)) {
            require(backup.renameTo(target)) { "cannot restore skin backup" }
            error("cannot replace skin")
        }
        backup.deleteRecursively()
    }

    private fun isUsableImage(file: File): Boolean {
        val bounds = readBounds(file) ?: return false
        val pixels = bounds.first.toLong() * bounds.second.toLong()
        return bounds.first <= MAX_SOURCE_DIMENSION &&
            bounds.second <= MAX_SOURCE_DIMENSION &&
            pixels <= MAX_SOURCE_PIXELS
    }

    private fun readBounds(file: File): Pair<Int, Int>? {
        if (!file.isFile || file.length() <= 0 || file.length() > BottomBarSkinArchive.MAX_ENTRY_BYTES) {
            return null
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            null
        }
    }

    private fun decodeSquared(file: File, sizePx: Int): Bitmap? {
        val bounds = readBounds(file) ?: return null
        if (sizePx <= 0) return null
        val maxSide = maxOf(bounds.first, bounds.second)
        var sample = 1
        while (maxSide / sample > sizePx * 2) sample *= 2
        val source = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val scale = sizePx.toFloat() / maxOf(source.width, source.height)
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        if (scaled !== source) source.recycle()
        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(scaled, (sizePx - width) / 2f, (sizePx - height) / 2f, null)
        scaled.recycle()
        return output
    }
}
