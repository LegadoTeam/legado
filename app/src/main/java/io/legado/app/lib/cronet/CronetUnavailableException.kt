package io.legado.app.lib.cronet

import java.io.IOException

internal class CronetUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
