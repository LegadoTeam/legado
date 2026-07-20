package io.legado.app.help.http

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.internal.http.promisesBody
import okio.buffer
import okio.source
import org.brotli.dec.BrotliInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

internal const val TRANSPARENT_ACCEPT_ENCODING = "gzip, deflate, br"

internal fun Request.canUseTransparentDecompression(): Boolean {
    return header("Accept-Encoding") == null && header("Range") == null
}

internal fun decompressResponse(response: Response): Response {
    val body = response.body
    if (!response.promisesBody() || body == ResponseBody.EMPTY) {
        return response
    }

    val source = try {
        when (response.header("Content-Encoding")?.lowercase()) {
            "gzip" -> GZIPInputStream(body.byteStream()).source().buffer()
            "deflate" -> InflaterInputStream(body.byteStream(), Inflater(true)).source().buffer()
            "br" -> BrotliInputStream(body.byteStream()).source().buffer()
            else -> return response
        }
    } catch (throwable: Throwable) {
        try {
            body.close()
        } catch (closeThrowable: Throwable) {
            throwable.addSuppressed(closeThrowable)
        }
        throw throwable
    }

    return response.newBuilder()
        .removeHeader("Content-Encoding")
        .removeHeader("Content-Length")
        .body(source.asResponseBody(body.contentType(), -1))
        .build()
}

object DecompressInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val requestBuilder = request.newBuilder()

        val transparentDecompress = request.canUseTransparentDecompression()
        if (transparentDecompress) {
            requestBuilder.header("Accept-Encoding", TRANSPARENT_ACCEPT_ENCODING)
        }

        val response = chain.proceed(requestBuilder.build())
        return if (transparentDecompress) decompressResponse(response) else response
    }
}
