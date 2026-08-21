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

    private val lanzouUploaderText by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val scriptFile = generateSequence(File(userDir)) {
            it.parentFile
        }.map {
            File(it, ".github/scripts/lzy_web.py")
        }.first { it.isFile }
        scriptFile.readText().replace("\r\n", "\n")
    }

    @Test
    fun `test release runs for every master push`() {
        assertTrue(workflowText.contains("push:"))
        assertTrue(workflowText.contains("- master"))
        assertTrue(workflowText.contains("workflow_dispatch:"))
        assertTrue(workflowText.contains("commit_sha:"))
        assertTrue(workflowText.contains("ref: ${'$'}{{ inputs.commit_sha || github.sha }}"))
        assertTrue(workflowText.contains("ref: ${'$'}{{ needs.prepare.outputs.commit }}"))
        assertTrue(workflowText.contains("if: ${'$'}{{ !github.event.deleted }}"))
        assertTrue(workflowText.contains("group: test-release"))
        assertTrue(workflowText.contains("cancel-in-progress: false"))
        assertTrue(workflowText.contains("queue: max"))
        assertFalse(workflowText.contains("pull_request:"))
        assertFalse(workflowText.contains("github.event.pull_request"))
        assertFalse(workflowText.contains("github.event.head_commit"))
    }

    @Test
    fun `test release only publishes beta and lanzou artifacts`() {
        val publish = workflowText.substringAfter("  publish:")
        val publishAction = publish.indexOf("uses: ncipollo/release-action@v1")
        val releaseNotesUpdate = publish.indexOf("gh release edit beta")

        assertTrue(workflowText.contains("tag: beta"))
        assertTrue(workflowText.contains("prerelease: true"))
        assertTrue(workflowText.contains("removeArtifacts: true"))
        assertTrue(workflowText.contains("name: legado_app_${'$'}{{ env.VERSION }}"))
        assertTrue(workflowText.contains("versionCode: ${'$'}{{ steps.set-ver.outputs.versionCode }}"))
        assertTrue(workflowText.contains("git rev-list \"${'$'}{base_commit}..HEAD\" --count --no-merges"))
        assertTrue(workflowText.contains("git show -s --format=%ct \"${'$'}COMMIT_SHA\""))
        assertTrue(workflowText.contains("date -d \"@${'$'}commit_epoch\" +%y%m%d%H"))
        assertTrue(workflowText.contains("Refusing beta version-code downgrade"))
        assertTrue(workflowText.contains("--jq '.assets[].name'"))
        assertTrue(publish.contains("VERSION_CODE: ${'$'}{{ needs.prepare.outputs.versionCode }}"))
        assertTrue(publish.contains("<!-- legado-version-code:%s -->"))
        assertTrue(publish.contains("${'$'}{renamed%.apk}_vc${'$'}{VERSION_CODE}.apk"))
        assertTrue(publish.contains("omitBodyDuringUpdate: true"))
        assertTrue(publishAction >= 0)
        assertTrue(releaseNotesUpdate > publishAction)
        assertFalse(workflowText.contains("name: legado_test_"))
        assertTrue(workflowText.contains("此版本为提交测试版"))
        assertTrue(workflowText.contains("extract-latest-update.sh"))
        assertFalse(workflowText.contains("Branch: %s"))
        assertTrue(workflowText.contains("_测试版_${'$'}{RELEASE_LABEL}"))
        assertTrue(workflowText.contains("lzy_web.py"))
        assertFalse(workflowText.contains("test_lzy_web.py"))
        assertFalse(workflowText.contains("Deploy apk to server"))
        assertFalse(workflowText.contains("Post to Telegram Channel"))
        assertFalse(workflowText.contains("Push To \"test\" Branch"))
    }

    @Test
    fun `lanzou uploader propagates login and upload failures`() {
        assertTrue(lanzouUploaderText.contains("with open(file_dir, \"rb\") as upload_stream:"))
        assertTrue(lanzouUploaderText.contains("return 0 if upload(argv[0], argv[1]) else 1"))
        assertTrue(lanzouUploaderText.contains("sys.exit(main(sys.argv[1:]))"))
        assertFalse(lanzouUploaderText.contains("\"name\": '{file_name}'"))
        assertFalse(lanzouUploaderText.contains("retry_tim+"))
    }
}
