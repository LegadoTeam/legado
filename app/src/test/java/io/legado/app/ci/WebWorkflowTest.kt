package io.legado.app.ci

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebWorkflowTest {

    private val workflowText by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val workflowFile = generateSequence(File(userDir)) {
            it.parentFile
        }.map {
            File(it, ".github/workflows/web.yml")
        }.first { it.isFile }
        workflowFile.readText().replace("\r\n", "\n")
    }

    @Test
    fun `web builds serialize generated asset updates`() {
        assertTrue(workflowText.contains("- '.github/workflows/web.yml'"))
        assertTrue(workflowText.contains("- '.github/scripts/push-web-assets.sh'"))
        assertTrue(workflowText.contains("group: build-web-" + "$" + "{{ github.ref }}"))
        assertTrue(workflowText.contains("cancel-in-progress: true"))
        assertTrue(workflowText.contains("fetch-depth: 0"))
    }

    @Test
    fun `web asset commit rebases before a separate push`() {
        val masterPush = "github.event_name == 'push' && " +
                "github.ref == 'refs/heads/master'"

        assertTrue(workflowText.contains(masterPush))
        assertTrue(workflowText.contains("skip_fetch: true"))
        assertTrue(workflowText.contains("skip_push: true"))
        assertTrue(workflowText.contains("bash .github/scripts/push-web-assets.sh"))
    }
}
