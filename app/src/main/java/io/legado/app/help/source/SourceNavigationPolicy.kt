package io.legado.app.help.source

import kotlin.coroutines.CoroutineContext

/** Restrict only the operation carrying this element; parallel reader/login work stays interactive. */
internal object SuppressSourceNavigation : CoroutineContext.Element,
    CoroutineContext.Key<SuppressSourceNavigation> {
    override val key: CoroutineContext.Key<*> get() = this
}

internal fun shouldSuppressSourceNavigation(enabled: Boolean, context: CoroutineContext): Boolean =
    enabled && context[SuppressSourceNavigation] != null
