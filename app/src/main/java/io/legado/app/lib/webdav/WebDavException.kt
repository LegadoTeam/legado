package io.legado.app.lib.webdav

open class WebDavException(
    msg: String,
    val responseCode: Int? = null,
) : Exception(msg) {

    override fun fillInStackTrace(): Throwable {
        return this
    }

}

class ObjectNotFoundException(
    msg: String,
    responseCode: Int? = null,
) : WebDavException(msg, responseCode)

internal fun isWebDavOverwriteConflict(responseCode: Int?): Boolean =
    responseCode == 409 || responseCode == 412
