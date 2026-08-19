package io.legado.app.help.storage

import android.content.Context
import com.google.gson.annotations.SerializedName
import fi.iki.elonen.NanoHTTPD
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.compress.resolveArchiveEntryFile
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.externalFiles
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal const val LAN_BACKUP_PROTOCOL = "legado-lan-backup"
internal const val LAN_BACKUP_VERSION = 1
internal const val LAN_BACKUP_SESSION_MS = 5 * 60_000L
internal const val LAN_BACKUP_MAX_ENCRYPTED_BYTES = 32L * 1024L * 1024L
internal const val LAN_BACKUP_MAX_UNCOMPRESSED_BYTES = 512L * 1024L * 1024L
internal const val LAN_BACKUP_MAX_ENTRIES = 10_000

private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 60_000
private const val MIN_FREE_SPACE_BYTES = 32L * 1024L * 1024L
private const val CLOCK_SKEW_MS = 5 * 60_000L
private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
private val HEX_PATTERN = Regex("^[0-9a-f]+$")

internal data class LanBackupDescriptor(
    @SerializedName("protocol")
    val protocol: String = LAN_BACKUP_PROTOCOL,
    @SerializedName("version")
    val version: Int = LAN_BACKUP_VERSION,
    @SerializedName("hosts")
    val hosts: List<String>,
    @SerializedName("port")
    val port: Int,
    @SerializedName("token")
    val token: String,
    @SerializedName("key")
    val key: String,
    @SerializedName("iv")
    val iv: String,
    @SerializedName("size")
    val size: Long,
    @SerializedName("sha256")
    val sha256: String,
    @SerializedName("deviceName")
    val deviceName: String,
    @SerializedName("expiresAt")
    val expiresAt: Long,
)

internal data class ReceivedLanBackup(
    val descriptor: LanBackupDescriptor,
    val file: File,
    val uncompressedBytes: Long,
)

internal class LanBackupSession(
    val descriptor: LanBackupDescriptor,
    private val server: LanBackupServer,
    private val directory: File,
) : Closeable {

    val qrText: String = GSON.toJson(descriptor)

    override fun close() {
        server.stop()
        directory.deleteRecursively()
    }
}

internal object LanBackupTransfer {

    private val secureRandom = SecureRandom()

    fun prepare(
        backupFile: File,
        deviceName: String,
    ): LanBackupSession {
        require(backupFile.isFile) { "备份文件不存在" }
        val hosts = NetworkUtils.getLocalIPAddress()
            .filter(InetAddress::isSiteLocalAddress)
            .mapNotNull { it.hostAddress }
            .distinct()
            .take(8)
        require(hosts.isNotEmpty()) { "未找到可用的局域网地址" }

        val directory = backupFile.parentFile ?: error("备份目录不存在")
        var server: LanBackupServer? = null
        return try {
            val key = randomBytes(32)
            val iv = randomBytes(12)
            val token = randomBytes(16).toHex()
            val expiresAt = System.currentTimeMillis() + LAN_BACKUP_SESSION_MS
            val encryptedFile = File(directory, "backup.enc")
            require(backupFile.length() in 1L..(LAN_BACKUP_MAX_ENCRYPTED_BYTES - 16)) {
                "备份文件超过局域网传输上限"
            }
            require(
                directory.usableSpace >= backupFile.length() + 16 + MIN_FREE_SPACE_BYTES
            ) { "存储空间不足" }
            encryptBackup(backupFile, encryptedFile, key, iv, token)
            check(backupFile.delete()) { "无法清理临时备份" }
            require(encryptedFile.length() in 17..LAN_BACKUP_MAX_ENCRYPTED_BYTES) {
                "备份文件超过局域网传输上限"
            }

            val activeServer = LanBackupServer(encryptedFile, token, expiresAt).also {
                it.start(READ_TIMEOUT_MS, false)
            }
            server = activeServer
            val descriptor = LanBackupDescriptor(
                hosts = hosts,
                port = activeServer.listeningPort,
                token = token,
                key = key.toHex(),
                iv = iv.toHex(),
                size = encryptedFile.length(),
                sha256 = sha256(encryptedFile).toHex(),
                deviceName = deviceName.take(80),
                expiresAt = expiresAt,
            )
            validateDescriptor(descriptor).getOrThrow()
            LanBackupSession(descriptor, activeServer, directory)
        } catch (error: Throwable) {
            server?.stop()
            directory.deleteRecursively()
            throw error
        }
    }

    suspend fun receive(context: Context, qrText: String): ReceivedLanBackup = withContext(IO) {
        val descriptor = decodeDescriptor(qrText).getOrThrow()
        val directory = File(
            context.cacheDir,
            "lan_backup/receive/${UUID.randomUUID()}",
        ).apply {
            check(mkdirs() || isDirectory) { "无法创建接收目录" }
        }
        val requiredSpace = descriptor.size * 2 + MIN_FREE_SPACE_BYTES
        require(directory.usableSpace >= requiredSpace) { "存储空间不足" }

        val encryptedFile = File(directory, "backup.enc")
        val zipFile = File(directory, "backup.zip")
        try {
            download(descriptor, encryptedFile)
            decryptBackup(
                encryptedFile,
                zipFile,
                descriptor.key.hexToBytes(64),
                descriptor.iv.hexToBytes(24),
                descriptor.token,
            )
            val uncompressedBytes = validateBackupArchive(zipFile)
            requireRestoreSpace(context, uncompressedBytes)
            encryptedFile.delete()
            ReceivedLanBackup(descriptor, zipFile, uncompressedBytes)
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun decodeDescriptor(
        text: String,
        now: Long = System.currentTimeMillis(),
    ): Result<LanBackupDescriptor> = runCatching {
        val descriptor = GSON.fromJsonObject<LanBackupDescriptor>(text).getOrThrow()
        validateDescriptor(descriptor, now).getOrThrow()
    }

    fun validateDescriptor(
        descriptor: LanBackupDescriptor,
        now: Long = System.currentTimeMillis(),
    ): Result<LanBackupDescriptor> = runCatching {
        require(descriptor.protocol == LAN_BACKUP_PROTOCOL) { "不是阅读局域网备份" }
        require(descriptor.version == LAN_BACKUP_VERSION) { "不支持的局域网备份版本" }
        require(descriptor.hosts.size in 1..8) { "局域网地址无效" }
        descriptor.hosts.forEach { host ->
            require(NetworkUtils.isIPv4Address(host)) { "局域网地址无效" }
            require(InetAddress.getByName(host).isSiteLocalAddress) { "只允许局域网地址" }
        }
        require(descriptor.port in 1024..65535) { "局域网端口无效" }
        descriptor.token.requireHex(32, "会话令牌无效")
        descriptor.key.requireHex(64, "加密密钥无效")
        descriptor.iv.requireHex(24, "加密随机数无效")
        descriptor.sha256.requireHex(64, "文件摘要无效")
        require(descriptor.size in 17..LAN_BACKUP_MAX_ENCRYPTED_BYTES) {
            "备份文件大小无效"
        }
        require(descriptor.deviceName.length <= 80) { "设备名称过长" }
        require(descriptor.expiresAt >= now - CLOCK_SKEW_MS) { "传输会话已过期" }
        require(descriptor.expiresAt <= now + LAN_BACKUP_SESSION_MS + CLOCK_SKEW_MS) {
            "传输会话时间无效"
        }
        descriptor
    }

    fun encryptBackup(
        input: File,
        output: File,
        key: ByteArray,
        iv: ByteArray,
        token: String,
    ) {
        require(key.size == 32)
        require(iv.size == 12)
        output.parentFile?.mkdirs()
        try {
            val cipher = createCipher(Cipher.ENCRYPT_MODE, key, iv, token)
            input.inputStream().buffered().use { source ->
                CipherOutputStream(output.outputStream().buffered(), cipher).use(source::copyTo)
            }
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    fun decryptBackup(
        input: File,
        output: File,
        key: ByteArray,
        iv: ByteArray,
        token: String,
    ) {
        output.parentFile?.mkdirs()
        try {
            val cipher = createCipher(Cipher.DECRYPT_MODE, key, iv, token)
            input.inputStream().buffered().use { source ->
                output.outputStream().buffered().use { target ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        cipher.update(buffer, 0, count)?.let(target::write)
                    }
                    cipher.doFinal().takeIf { it.isNotEmpty() }?.let(target::write)
                }
            }
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    fun validateBackupArchive(
        file: File,
        maxEntries: Int = LAN_BACKUP_MAX_ENTRIES,
        maxBytes: Long = LAN_BACKUP_MAX_UNCOMPRESSED_BYTES,
    ): Long {
        require(file.isFile) { "备份文件不存在" }
        val validationRoot = File(file.parentFile, "validation")
        var entryCount = 0
        var totalBytes = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                require(entryCount <= maxEntries) { "备份条目过多" }
                resolveArchiveEntryFile(validationRoot, entry.name)
                if (!entry.isDirectory) {
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        totalBytes += count
                        require(totalBytes <= maxBytes) { "备份解压后过大" }
                    }
                }
                zip.closeEntry()
            }
        }
        require(entryCount > 0) { "备份压缩包为空" }
        return totalBytes
    }

    fun requireRestoreSpace(context: Context, uncompressedBytes: Long) {
        require(
            context.filesDir.usableSpace >= uncompressedBytes + MIN_FREE_SPACE_BYTES
        ) { "恢复空间不足" }
    }

    fun requireRestoreMediaSpace(
        context: Context,
        backupRoot: File,
        includeBackgrounds: Boolean,
    ) {
        val directoryNames = if (includeBackgrounds) {
            backupMediaDirectoryNames
        } else {
            listOf("covers")
        }
        val requiredBytes = requiredRestoreMediaBytes(
            backupRoot,
            context.externalFiles,
            directoryNames,
        )
        require(
            context.externalFiles.usableSpace >=
                Math.addExact(requiredBytes, MIN_FREE_SPACE_BYTES)
        ) { "媒体恢复空间不足" }
    }

    private suspend fun download(descriptor: LanBackupDescriptor, output: File) {
        var lastError: Throwable? = null
        for (host in descriptor.hosts) {
            currentCoroutineContext().ensureActive()
            output.delete()
            try {
                downloadFromHost(descriptor, host, output)
                return
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                lastError = error
            }
        }
        throw IOException("无法连接发送设备", lastError)
    }

    private suspend fun downloadFromHost(
        descriptor: LanBackupDescriptor,
        host: String,
        output: File,
    ) {
        val connection = URL(
            "http://$host:${descriptor.port}/backup/${descriptor.token}"
        ).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept-Encoding", "identity")
            require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "发送设备拒绝了下载请求"
            }
            require(
                connection.getHeaderField("Content-Length")?.toLongOrNull() == descriptor.size
            ) { "备份文件大小不一致" }
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            output.outputStream().buffered().use { target ->
                connection.inputStream.buffered().use { source ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        received += count
                        require(received <= descriptor.size) { "接收数据超过声明大小" }
                        digest.update(buffer, 0, count)
                        target.write(buffer, 0, count)
                    }
                }
            }
            require(received == descriptor.size) { "备份文件未接收完整" }
            require(
                MessageDigest.isEqual(
                    descriptor.sha256.hexToBytes(64),
                    digest.digest(),
                )
            ) { "备份文件摘要不一致" }
        } finally {
            connection.disconnect()
        }
    }

    private fun createCipher(
        mode: Int,
        key: ByteArray,
        iv: ByteArray,
        token: String,
    ): Cipher = Cipher.getInstance(AES_TRANSFORMATION).apply {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        updateAAD("$LAN_BACKUP_PROTOCOL:$LAN_BACKUP_VERSION:$token".toByteArray())
    }

    private fun randomBytes(size: Int) = ByteArray(size).also(secureRandom::nextBytes)
}

internal class LanBackupServer(
    private val file: File,
    private val token: String,
    private val expiresAt: Long,
) : NanoHTTPD(0) {

    override fun serve(session: IHTTPSession): Response {
        if (session.method != Method.GET) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "")
        }
        val requestedToken = session.uri.removePrefix("/backup/")
        if (session.uri == requestedToken || !tokensEqual(token, requestedToken)) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
        }
        if (System.currentTimeMillis() > expiresAt) {
            return newFixedLengthResponse(Response.Status.GONE, MIME_PLAINTEXT, "")
        }

        return newFixedLengthResponse(
            Response.Status.OK,
            "application/octet-stream",
            FileInputStream(file),
            file.length(),
        ).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("Content-Disposition", "attachment; filename=backup.enc")
        }
    }
}

private fun String.requireHex(length: Int, message: String) {
    require(this.length == length && HEX_PATTERN.matches(this)) { message }
}

private fun String.hexToBytes(expectedLength: Int): ByteArray {
    requireHex(expectedLength, "十六进制数据无效")
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}

private fun sha256(file: File): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest()
}

private fun tokensEqual(expected: String, actual: String): Boolean {
    return MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())
}
