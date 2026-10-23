package io.legado.app.ci

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BetaReleaseWorkflowTest {
    private val workflow = repoFile(".github/workflows/BetaRelease.yml").readText()

    @Test
    fun `legacy beta workflow is retained only as a manual compatibility notice`() {
        assertTrue(workflow.contains("workflow_dispatch:"))
        assertTrue(workflow.contains("TestRelease.yml"))
        assertTrue(workflow.contains("reader_*.apk"))
        assertFalse(workflow.contains("tag: beta"))
        assertFalse(workflow.contains("branches: [ master ]"))
    }

    private fun repoFile(path: String): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, path)
    }
}
