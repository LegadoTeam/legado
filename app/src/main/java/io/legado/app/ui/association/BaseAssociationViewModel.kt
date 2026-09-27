package io.legado.app.ui.association

import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.data.entities.HighlightRuleFile
import io.legado.app.utils.inputStream
import io.legado.app.utils.jsonPath

abstract class BaseAssociationViewModel(application: Application) : BaseViewModel(application) {

    val successLive = MutableLiveData<Pair<String, String>>()
    val errorLive = MutableLiveData<String>()

    fun importJson(uri: Uri) {
        val map = uri.inputStream(context).getOrThrow().use {
            jsonPath.parse(it).read<Map<String, *>>("$[0]")
        } ?: uri.inputStream(context).getOrThrow().use {
            jsonPath.parse(it).read("$")
        }

        val type = jsonImportType(map)
        if (type == null) errorLive.postValue("格式不对")
        else successLive.postValue(type to uri.toString())
    }
}

internal fun jsonImportType(map: Map<String, *>): String? =
        when {
            map["type"] == HighlightRuleFile.TYPE ->
                "highlightRule"

            map.containsKey("bookSourceUrl") ->
                "bookSource"

            map.containsKey("sourceUrl") ->
                "rssSource"

            map.containsKey("pattern") && map.containsKey("style") &&
                map.containsKey("uuid") && !map.containsKey("replacement") ->
                "highlightRule"

            map.containsKey("pattern") && (map.containsKey("style") || map.containsKey("uuid")) ->
                null

            map.containsKey("pattern") ->
                "replaceRule"

            map.containsKey("themeName") ->
                "theme"

            map.containsKey("showRule") ->
                "dictRule"

            map.containsKey("name") && map.containsKey("rule") ->
                "txtRule"

            map.containsKey("cron") && map.containsKey("script") ->
                "autoTask"

            map.containsKey("name") && map.containsKey("url") ->
                "httpTts"

            map.containsKey("name") && map.containsKey("author") ->
                "bookshelf"

            else -> null
        }
