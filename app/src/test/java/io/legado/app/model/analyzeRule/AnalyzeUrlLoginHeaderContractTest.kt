package io.legado.app.model.analyzeRule

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AnalyzeUrlLoginHeaderContractTest {

    @Test
    fun `login headers are restricted to the source site`() {
        val source = File("src/main/java/io/legado/app/model/analyzeRule/AnalyzeUrl.kt").readText()
        assertTrue(source.contains("hasLoginHeader && isLoginHeaderSite(mUrl)"))
        assertTrue(source.contains("NetworkUtils.getSubDomainOrNull(source.getKey())"))
        assertTrue(source.contains("targetDomain.equals(sourceDomain, ignoreCase = true)"))
    }
}
