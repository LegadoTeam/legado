package io.legado.app.web.mcp

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.ConcurrentRateLimiter.Companion.concurrentRecordMap
import io.legado.app.help.config.SourceConfig
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.model.SharedJsScope
import io.legado.app.model.jsSource.JsSourceUpsert
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

internal object McpSourceStore {

    suspend fun saveDeclarative(text: String): BookSource {
        return JsSourceUpsert.withSaveLock {
            val source = parseDeclarative(text)
            val old = appDb.bookSourceDao.getBookSource(source.bookSourceUrl)
            if (!JsSourceUpsert.prepareForSave(source, old) && old != null) {
                return@withSaveLock old
            }

            old?.let {
                if (it.exploreUrl != source.exploreUrl) {
                    it.clearExploreKindsCache()
                }
                if (it.jsLib != source.jsLib) {
                    SharedJsScope.remove(it.jsLib)
                }
                appDb.bookSourceDao.delete(it)
                SourceConfig.removeSource(it.bookSourceUrl)
            }
            appDb.bookSourceDao.insert(source)
            concurrentRecordMap.remove(source.bookSourceUrl)
            source
        }
    }

    internal fun parseDeclarative(text: String): BookSource {
        require(text.toByteArray(Charsets.UTF_8).size <= JsSourceUpsert.MAX_SOURCE_BYTES) {
            "书源 JSON 不能超过 1 MiB"
        }
        val json = text.dropWhile { it.isWhitespace() || it == '\uFEFF' }
        val source = GSON.fromJsonObject<BookSource>(json).getOrThrow()
        require(source.bookSourceName.isNotBlank() && source.bookSourceUrl.isNotBlank()) {
            "源名称和 URL 不能为空"
        }
        require(source.mainJs.isNullOrBlank()) {
            "带 mainJs 的书源必须使用 format=js 提交脚本原文"
        }
        source.mainJs = null
        return source
    }
}
