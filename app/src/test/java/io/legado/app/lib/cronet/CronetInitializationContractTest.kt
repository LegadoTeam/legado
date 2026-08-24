package io.legado.app.lib.cronet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CronetInitializationContractTest {

    @Test
    fun `cronet initialization keeps failures isolated from startup`() {
        val helper = readProjectFile(
            "app/src/main/java/io/legado/app/lib/cronet/CronetHelper.kt"
        )
        val interceptor = readProjectFile(
            "app/src/main/java/io/legado/app/lib/cronet/CronetInterceptor.kt"
        )
        val coroutineInterceptor = readProjectFile(
            "app/src/main/java/io/legado/app/lib/cronet/CronetCoroutineInterceptor.kt"
        )
        val app = readProjectFile("app/src/main/java/io/legado/app/App.kt")
        val config = readProjectFile("app/src/main/java/io/legado/app/help/config/AppConfig.kt")
        val httpHelper = readProjectFile("app/src/main/java/io/legado/app/help/http/HttpHelper.kt")

        assertTrue(helper.indexOf("try {") < helper.indexOf("CronetLoader.preDownload()"))
        assertTrue(helper.contains("ExperimentalCronetEngine.Builder(appCtx)"))
        assertTrue(helper.contains("catch (e: Throwable)"))
        assertTrue(helper.contains("cronetEngineFailure = e"))
        assertTrue(helper.contains("CronetUnavailableException"))
        assertTrue(interceptor.contains("if (!AppConfig.isCronet) return chain.proceed(original)"))
        assertTrue(coroutineInterceptor.contains("if (!AppConfig.isCronet) return chain.proceed(original)"))
        assertTrue(interceptor.contains("throw cronetUnavailableException"))
        assertTrue(coroutineInterceptor.contains("throw cronetUnavailableException"))
        val interceptorStrictPath = interceptor.substringAfter(
            "// Cronet is the selected transport. Do not silently switch to OkHttp."
        )
        val coroutineStrictPath = coroutineInterceptor.substringAfter(
            "// Cronet is the selected transport. Do not silently switch to OkHttp."
        )
        assertFalse(interceptorStrictPath.contains("chain.proceed(original)"))
        assertFalse(coroutineStrictPath.contains("chain.proceed(original)"))
        assertTrue(interceptor.contains("getCronetEngineOrNull()"))
        assertTrue(interceptor.contains("catch (e: Throwable)"))
        assertTrue(coroutineInterceptor.contains("getCronetEngineOrNull()"))
        assertTrue(coroutineInterceptor.contains("catch (e: Throwable)"))
        assertTrue(app.contains("runCatching { Cronet.preDownload() }"))
        assertTrue(config.contains("val isCronet = appCtx.getPrefBoolean(PreferKey.cronet)"))
        assertTrue(httpHelper.contains("if (AppConfig.isCronet)"))
    }

    @Test
    fun `cronet interceptors preserve explicit proxy bypass`() {
        val httpHelper = readProjectFile("app/src/main/java/io/legado/app/help/http/HttpHelper.kt")

        assertTrue(httpHelper.contains("Cronet.interceptor?.let { builder.interceptors().remove(it) }"))
    }

    private fun readProjectFile(path: String): String {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir)) {
            it.parentFile
        }.map {
            File(it, path)
        }.first { it.isFile }.readText()
    }
}
