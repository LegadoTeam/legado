package io.legado.app.lib.cronet

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CronetInitializationContractTest {

    @Test
    fun `cronet initialization and interceptors keep a safe fallback boundary`() {
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
        assertTrue(interceptor.contains("getCronetEngineOrNull()"))
        assertTrue(interceptor.contains("catch (e: Throwable)"))
        assertTrue(coroutineInterceptor.contains("getCronetEngineOrNull()"))
        assertTrue(coroutineInterceptor.contains("catch (e: Throwable)"))
        assertTrue(app.contains("runCatching { Cronet.preDownload() }"))
        assertTrue(config.contains("val isCronet = appCtx.getPrefBoolean(PreferKey.cronet)"))
        assertTrue(httpHelper.contains("if (AppConfig.isCronet)"))
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
