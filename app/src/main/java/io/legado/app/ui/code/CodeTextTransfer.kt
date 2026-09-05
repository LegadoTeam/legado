package io.legado.app.ui.code

import android.content.Context
import java.io.File
import java.util.UUID

/** Keeps large editor drafts out of Activity Intent/Bundle transactions. */
object CodeTextTransfer {
    private const val PREFIX = "code-text-"

    fun write(context: Context, text: String): String {
        val file = File(context.cacheDir, "$PREFIX${UUID.randomUUID()}.txt")
        file.outputStream().bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        return file.absolutePath
    }

    fun read(context: Context, path: String): String? = runCatching {
        val file = File(path)
        if (file.canonicalFile.parentFile != context.cacheDir.canonicalFile || !file.name.startsWith(PREFIX)) return null
        file.readText(Charsets.UTF_8)
    }.getOrNull()

    fun delete(context: Context, path: String?) {
        if (path == null) return
        runCatching {
            val file = File(path)
            if (file.canonicalFile.parentFile == context.cacheDir.canonicalFile && file.name.startsWith(PREFIX)) file.delete()
        }
    }
}
