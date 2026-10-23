package io.legado.app.ci

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TestReleaseWorkflowTest {
    private val workflow = repoFile(".github/workflows/TestRelease.yml").readText().replace("\r\n", "\n")

    @Test
    fun `master pushes publish immutable Reader beta releases`() {
        assertTrue(workflow.contains("branches: [ master ]"))
        assertTrue(workflow.contains("reader-version.sh"))
        assertTrue(workflow.contains("gh release create \"$TAG\""))
        assertTrue(workflow.contains("--prerelease"))
        assertTrue(workflow.contains("reader-version-code:"))
        assertFalse(workflow.contains("tag: beta"))
        assertFalse(workflow.contains("LegadoTeam/legado"))
    }

    @Test
    fun `missing signing secrets skip beta publication instead of failing unrelated CI`() {
        assertTrue(workflow.contains("available=false"))
        assertTrue(workflow.contains("Reader signing secrets are incomplete"))
    }

    private fun repoFile(path: String): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, path)
    }
}
