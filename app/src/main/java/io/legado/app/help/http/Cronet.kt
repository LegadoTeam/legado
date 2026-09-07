package io.legado.app.help.http

import io.legado.app.lib.cronet.CronetInterceptor
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.cronet.getCronetEngineOrNull
import okhttp3.Interceptor

object Cronet {

    fun warmUp() {
        Coroutine.async { getCronetEngineOrNull() }
    }

    val interceptor: Interceptor? by lazy {
        CronetInterceptor(cookieJar)
    }

}
