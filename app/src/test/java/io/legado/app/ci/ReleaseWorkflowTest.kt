package io.legado.app.ci

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReleaseWorkflowTest {
    private val workflow = repoFile(".github/workflows/release.yml").readText().replace("\r\n", "\n")

    @Test
    fun `stable release is manual and requires stable release train`() {
        assertTrue(workflow.contains("workflow_dispatch:"))
        assertTrue(workflow.contains("channel=stable"))
        assertTrue(workflow.contains("reader-version.sh"))
        assertTrue(workflow.contains("--latest"))
        assertTrue(workflow.contains("reader-version-code:"))
    }

    private fun repoFile(path: String): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, path)
    }
}
