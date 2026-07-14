package io.legado.app.ci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BetaReleaseWorkflowTest {

    private val workflowText by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val workflowFile = generateSequence(File(userDir)) {
            it.parentFile
        }.map {
            File(it, ".github/workflows/BetaRelease.yml")
        }.first { it.isFile }
        workflowFile
            .readText()
            .replace("\r\n", "\n")
    }

    @Test
    fun `beta release only exposes the manual trigger`() {
        val triggerBlock = workflowText
            .substringAfter("on:\n")
            .substringBefore("\nconcurrency:")

        assertEquals("workflow_dispatch:", triggerBlock.trim())
    }

    @Test
    fun `beta release keeps the merge commit guard`() {
        val expected = "if: " + "$" +
                "{{ !startsWith(github.event.head_commit.message, 'Merge pull request') }}"

        assertTrue(workflowText.contains(expected))
    }
}
