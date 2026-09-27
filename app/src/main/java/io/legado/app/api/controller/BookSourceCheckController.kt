package io.legado.app.api.controller

import io.legado.app.api.ReturnData
import androidx.annotation.Keep
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.BOOK_SOURCE_QUERY_CHUNK_SIZE
import io.legado.app.model.CheckSource
import io.legado.app.model.Debug
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.fromJsonArray
import splitties.init.appCtx
import java.util.UUID

object BookSourceCheckController {
    private val processToken = UUID.randomUUID().toString()

    private fun sessionToken(): String? = Debug.currentCheckSession()?.let { "$processToken:$it" }

    fun states(): ReturnData = ReturnData().setData(mapOf(
        "states" to appDb.bookSourceDao.allCheckStates(),
        "sessionToken" to sessionToken(),
    ))

    /** Sources and their local revision must be read from the same snapshot. */
    fun sources(parameters: Map<String, List<String>>): ReturnData {
        val rawUrls = parameters["urls"]?.firstOrNull()
        val urls = rawUrls?.let { GSON.fromJsonArray<String>(it).getOrNull() }
        if (rawUrls != null && urls == null) return ReturnData().setErrorMsg("书源地址参数无效")
        var result = ReturnData()
        appDb.runInTransaction {
            val sources = if (urls == null) appDb.bookSourceDao.all else urls.distinct()
                .chunked(BOOK_SOURCE_QUERY_CHUNK_SIZE).flatMap { appDb.bookSourceDao.getBookSources(it) }
            val states = if (urls == null) appDb.bookSourceDao.allCheckStates() else
                sources.mapNotNull { appDb.bookSourceDao.getCheckState(it.bookSourceUrl) }
            result = ReturnData().setData(mapOf(
                "sources" to sources,
                "states" to states,
            ))
        }
        return result
    }

    @Keep private data class StartRequest(val sources: List<BookSource>?, val keyword: String?)
    @Keep private data class StopRequest(val sessionToken: String?)

    suspend fun start(postData: String?): ReturnData {
        val request = postData?.let { GSON.fromJsonObject<StartRequest>(it).getOrNull() }
            ?: return ReturnData().setErrorMsg("检验参数无效")
        val submitted = request.sources?.distinctBy { it.bookSourceUrl }.orEmpty()
        if (submitted.isEmpty()) return ReturnData().setErrorMsg("请选择需要检验的书源")
        val parts = arrayListOf<BookSourcePart>()
        var changed = false
        appDb.runInTransaction {
            submitted.forEach { source ->
                val current = appDb.bookSourceDao.getBookSource(source.bookSourceUrl)
                val part = appDb.bookSourceDao.getBookSourcePart(source.bookSourceUrl)
                if (current == null || part == null || current.checkContent() != source.checkContent()) {
                    changed = true
                } else parts.add(part)
            }
        }
        if (changed) return ReturnData().setErrorMsg("书源尚未保存或设备版本已变更，请先保存或重新拉取")
        val session = Debug.tryStartCheckSession()
            ?: return ReturnData().setErrorMsg("书源调试通道占用中，请稍后重试")
        request.keyword?.trim()?.takeIf { it.isNotEmpty() }?.let { CheckSource.keyword = it }
        CheckSource.start(appCtx, parts, session)
        return ReturnData().setData(mapOf(
            "sessionToken" to "$processToken:$session",
            "sourceRevisions" to parts.associate { it.bookSourceUrl to it.sourceRevision },
        ))
    }

    fun stop(postData: String?): ReturnData {
        val request = postData?.let { GSON.fromJsonObject<StopRequest>(it).getOrNull() }
        val session = Debug.currentCheckSession()
        if (session == null || request?.sessionToken != "$processToken:$session") {
            return ReturnData().setErrorMsg("检验会话已结束或已更换")
        }
        CheckSource.stop(appCtx, session)
        return ReturnData().setData("")
    }
}
