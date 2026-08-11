package io.legado.app.ci

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TestReleaseWorkflowTest {

    private val workflowText by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val workflowFile = generateSequence(File(userDir)) {
            it.parentFile
        }.map {
            File(it, ".github/workflows/TestRelease.yml")
        }.first { it.isFile }
        workflowFile.readText().replace("\r\n", "\n")
    }

    @Test
    fun `test release only accepts trusted repository pull requests`() {
        assertTrue(workflowText.contains("pull_request:"))
        assertTrue(workflowText.contains("github.event.pull_request.user.login == 'mgz0227'"))
        assertTrue(
            workflowText.contains(
                "github.event.pull_request.head.repo.full_name == github.repository"
            )
        )
        assertFalse(workflowText.contains("pull_request_target:"))
    }

    @Test
    fun `test release only publishes beta and lanzou artifacts`() {
        assertTrue(workflowText.contains("tag: beta"))
        assertTrue(workflowText.contains("prerelease: true"))
        assertTrue(workflowText.contains("removeArtifacts: true"))
        assertTrue(workflowText.contains("queue: max"))
        assertTrue(workflowText.contains("_测试版_PR"))
        assertTrue(workflowText.contains("lzy_web.py"))
        assertFalse(workflowText.contains("Deploy apk to server"))
        assertFalse(workflowText.contains("Post to Telegram Channel"))
        assertFalse(workflowText.contains("Push To \"test\" Branch"))
    }
}
