package io.legado.app.help.storage

import android.content.Context
import android.content.SharedPreferences
import io.legado.app.utils.getSharedPreferences
import java.io.File
import java.util.UUID

internal fun writePreferenceSnapshot(
    context: Context,
    path: String,
    fileName: String,
    populate: SharedPreferences.Editor.() -> Unit,
) {
    val directory = File(path).apply {
        check(mkdirs() || isDirectory) { "无法创建配置备份目录" }
    }
    val temporaryName = "${fileName}_${UUID.randomUUID()}"
    val temporaryFile = File(directory, "${temporaryName}.xml")
    var preferences: SharedPreferences? = null
    try {
        preferences = checkNotNull(context.getSharedPreferences(path, temporaryName)) {
            "无法创建配置备份"
        }
        val editor = preferences.edit().clear()
        editor.populate()
        check(editor.commit()) { "写入配置备份失败" }
        check(temporaryFile.isFile) { "配置备份文件不存在" }
        temporaryFile.copyTo(File(directory, "${fileName}.xml"), overwrite = true)
    } finally {
        clearTemporaryPreferences(preferences, temporaryFile)
    }
}

internal fun readPreferenceSnapshot(
    context: Context,
    path: String,
    fileName: String,
): Map<String, *>? {
    val directory = File(path)
    val source = File(directory, "${fileName}.xml").takeIf { it.isFile } ?: return null
    val temporaryName = "${fileName}_${UUID.randomUUID()}"
    val temporaryFile = File(directory, "${temporaryName}.xml")
    var preferences: SharedPreferences? = null
    try {
        source.copyTo(temporaryFile, overwrite = true)
        preferences = checkNotNull(context.getSharedPreferences(path, temporaryName)) {
            "无法读取配置备份"
        }
        return HashMap(preferences.all)
    } finally {
        clearTemporaryPreferences(preferences, temporaryFile)
    }
}

private fun clearTemporaryPreferences(
    preferences: SharedPreferences?,
    file: File,
) {
    runCatching { preferences?.edit()?.clear()?.commit() }
    file.delete()
    File("${file.absolutePath}.bak").delete()
}
