package io.legado.app.model

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AnalyzeUrlUploadClientTest {

    @Test
    fun uploadUsesConfiguredClientPath() {
        val lines = analyzeUrlSource().readLines()
        val uploadStart = lines.indexOfFirst { it.contains("suspend fun upload(") }
        assertTrue(uploadStart >= 0)
        val clientCall = lines.drop(uploadStart).first { it.contains("newCallStrResponse") }
        assertTrue(clientCall.contains("getClient().newCallStrResponse(retry)"))
    }

    private fun analyzeUrlSource(): File = listOf(
        File("src/main/java/io/legado/app/model/analyzeRule/AnalyzeUrl.kt"),
        File("app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeUrl.kt"),
    ).firstOrNull { it.isFile } ?: error("AnalyzeUrl.kt not found")
}
