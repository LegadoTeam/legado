package io.legado.app.ci

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReleaseWorkflowTest {

    private val workflowText by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        generateSequence(File(userDir)) {
            it.parentFile
        }.map {
            File(it, ".github/workflows/release.yml")
        }.first { it.isFile }
            .readText()
            .replace("\r\n", "\n")
    }

    @Test
    fun `release checkout fetches full history for version code`() {
        val buildSteps = workflowText
            .substringAfter("\n  build:\n")
            .substringAfter("    steps:\n")
        val nextStepIndex = buildSteps.indexOf("\n\n      - ")
        assertTrue(nextStepIndex > 0)
        val checkoutStep = buildSteps.substring(0, nextStepIndex)

        assertTrue(checkoutStep.contains("- uses: actions/checkout@"))
        assertTrue(checkoutStep.contains("fetch-depth: 0"))
    }
}
