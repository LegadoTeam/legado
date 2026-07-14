package io.legado.app.ui.book.toc

internal fun <T> clearCallbackIfOwned(callback: T?, owner: T): T? {
    return if (callback === owner) null else callback
}
