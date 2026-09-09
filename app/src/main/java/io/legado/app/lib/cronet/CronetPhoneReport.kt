package io.legado.app.lib.cronet

import java.io.File

/** Read-only component evidence for a manually installed phone test build. */
internal fun cronetPhoneStorageReport(componentDir: File, downloadDir: File): String = buildString {
    for ((label, directory) in listOf("component" to componentDir, "temporary" to downloadDir)) {
        val files = directory.walkTopDown().filter { it.isFile }.toList()
        appendLine("${label}Files=${files.size}; ${label}Bytes=${files.sumOf { it.length() }}")
        files.forEach { file ->
            appendLine("${file.relativeTo(directory).invariantSeparatorsPath}; bytes=${file.length()}; mtime=${file.lastModified()}")
        }
    }
}
