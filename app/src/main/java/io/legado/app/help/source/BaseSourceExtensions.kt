package io.legado.app.help.source

import com.script.ScriptBindings
import com.script.SharedGlobalStateHandle
import io.legado.app.constant.SourceType
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.model.SharedJsScope
import io.legado.app.utils.MD5Utils
import kotlin.coroutines.CoroutineContext

fun BaseSource.getShareScope(coroutineContext: CoroutineContext? = null): ScriptBindings? {
    return SharedJsScope.getScope(jsLib, coroutineContext)
}

fun BaseSource.getSharedGlobalStateKey(): SharedGlobalStateHandle? {
    val library = jsLib?.takeIf { it.isNotBlank() } ?: return null
    val key = "${MD5Utils.md5Encode(library)}:${javaClass.name}:${MD5Utils.md5Encode(getKey())}"
    return ScriptBindings.getSharedGlobalStateHandle(key)
}

fun BaseSource.clearSharedGlobalState() {
    getSharedGlobalStateKey()?.let(ScriptBindings::removeSharedGlobalState)
}

fun clearSharedGlobalStateBySourceKey(
    sourceClass: Class<out BaseSource>,
    sourceKey: String,
) {
    ScriptBindings.removeSharedGlobalStatesBySource(
        sourceClass.name,
        MD5Utils.md5Encode(sourceKey),
    )
}

fun BaseSource.getSourceType(): Int {
    return when (this) {
        is BookSource -> SourceType.book
        is RssSource -> SourceType.rss
        else -> error("unknown source type: ${this::class.simpleName}.")
    }
}
