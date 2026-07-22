package io.legado.app.model.jsSource

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.source.getBookType
import io.legado.app.model.Debug
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils

object JsSourceMarshaller {

    private fun debugLog(sourceUrl: String, message: String) {
        runCatching { Debug.log(sourceUrl, message) }
    }

    fun validateBookType(raw: Int): Int? {
        if (raw == 0 || raw and BookType.allBookType.inv() != 0) {
            return null
        }
        return raw
    }

    private fun resolveType(jsonObject: JsonObject, source: BookSource): Int {
        if (jsonObject.has("type")) {
            val raw = runCatching { jsonObject.get("type").asInt }.getOrNull()
            raw?.let(::validateBookType)?.let { return it }
            debugLog(source.bookSourceUrl, "⇒type 非法,回退书源类型")
        }
        return source.getBookType()
    }

    fun parseSearchBooks(json: String?, source: BookSource): ArrayList<SearchBook> {
        val result = arrayListOf<SearchBook>()
        if (json.isNullOrBlank()) return result
        val array = runCatching { GSON.fromJson(json, JsonArray::class.java) }.getOrNull()
            ?: throw NoStackTraceException("search/explore 返回值不是数组")
        array.forEach { element ->
            val jsonObject = element as? JsonObject ?: return@forEach
            val book = runCatching { GSON.fromJson(jsonObject, SearchBook::class.java) }.getOrNull()
                ?: return@forEach
            if (book.name.isBlank() || book.bookUrl.isBlank()) {
                debugLog(source.bookSourceUrl, "⇒丢弃缺少 name/bookUrl 的搜索条目")
                return@forEach
            }
            book.origin = source.bookSourceUrl
            book.originName = source.bookSourceName
            book.originOrder = source.customOrder
            book.type = resolveType(jsonObject, source)
            result.add(book)
        }
        return result
    }

    fun mergeBookInfo(
        book: Book,
        json: String?,
        source: BookSource,
        canReName: Boolean,
    ) {
        if (json.isNullOrBlank()) return
        val jsonObject = runCatching { GSON.fromJson(json, JsonObject::class.java) }.getOrNull()
            ?: throw NoStackTraceException("getBookInfo 返回值不是对象")
        jsonObject.entrySet().forEach { (key, value) ->
            if (value.isJsonNull) return@forEach
            when (key) {
                "name" -> if (canReName) book.name = value.asString
                "author" -> book.author = value.asString
                "intro" -> book.intro = value.asString
                "coverUrl" -> book.coverUrl = value.asString
                "kind" -> book.kind = value.asString
                "wordCount" -> book.wordCount = value.asString
                "latestChapterTitle" -> book.latestChapterTitle = value.asString
                "tocUrl" -> book.tocUrl = value.asString
                "downloadUrls" -> {
                    val urls = runCatching {
                        value.asJsonArray.map {
                            require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
                            it.asString.trim()
                        }.filter {
                            it.isNotEmpty() && !it.startsWith("javascript", ignoreCase = true)
                        }.map {
                            NetworkUtils.getAbsoluteURL(book.bookUrl, it)
                        }.filter { it.isNotEmpty() }.distinct()
                    }.getOrNull()
                    if (urls == null) {
                        debugLog(source.bookSourceUrl, "⇒downloadUrls 不是字符串数组,忽略")
                    } else {
                        book.downloadUrls = urls
                    }
                }
                "variable" -> mergeVariable(book, value, source)
                "type" -> {
                    val raw = runCatching { value.asInt }.getOrNull()
                    raw?.let(::validateBookType)?.let { book.type = it }
                        ?: debugLog(source.bookSourceUrl, "⇒type 非法,忽略详情覆盖")
                }
            }
        }
    }

    private fun mergeVariable(book: Book, value: JsonElement, source: BookSource) {
        val type = TypeToken.getParameterized(
            Map::class.java,
            String::class.java,
            String::class.java,
        ).type
        val variables = runCatching {
            when {
                value.isJsonObject -> GSON.fromJson<Map<String, String>>(value, type)
                value.isJsonPrimitive && value.asJsonPrimitive.isString ->
                    GSON.fromJson<Map<String, String>>(value.asString, type)

                else -> null
            }
        }.getOrNull()
        if (variables == null) {
            debugLog(source.bookSourceUrl, "⇒variable 不是合法 JSON 对象,忽略")
            return
        }
        book.variableMap.clear()
        book.variableMap.putAll(variables)
        book.variable = GSON.toJson(book.variableMap)
    }

    fun parseChapters(
        json: String?,
        book: Book,
        source: BookSource,
    ): ArrayList<BookChapter> {
        val chapters = arrayListOf<BookChapter>()
        if (json.isNullOrBlank()) return chapters
        val array = runCatching { GSON.fromJson(json, JsonArray::class.java) }.getOrNull()
            ?: throw NoStackTraceException("getChapters 返回值不是数组")
        array.forEach { element ->
            val jsonObject = element as? JsonObject ?: return@forEach
            val chapter = runCatching { GSON.fromJson(jsonObject, BookChapter::class.java) }
                .getOrNull() ?: return@forEach
            if (chapter.title.isBlank() || chapter.url.isBlank()) {
                debugLog(source.bookSourceUrl, "⇒丢弃缺少 title/url 的章节")
                return@forEach
            }
            if (!(chapter.isVolume && chapter.url == chapter.title)) {
                chapter.url = NetworkUtils.getAbsoluteURL(book.tocUrl, chapter.url)
            }
            chapter.bookUrl = book.bookUrl
            chapter.baseUrl = book.tocUrl
            chapter.index = chapters.size
            chapters.add(chapter)
        }
        return chapters
    }
}
