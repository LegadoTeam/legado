package io.legado.app.ci

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderIdentityTest {

    private fun repoFile(path: String): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir)) { it.parentFile }
            .map { File(it, path) }
            .first { it.exists() }
    }

    @Test
    fun `reader keeps an independent Android identity without renaming source namespace`() {
        val buildGradle = repoFile("app/build.gradle").readText()

        assertTrue(buildGradle.contains("def readerApplicationId = \"io.github.yuanxiaoaihezhou.reader\""))
        assertTrue(buildGradle.contains("applicationId readerApplicationId"))
        assertTrue(buildGradle.contains("namespace = 'io.legado.app'"))
        assertFalse(buildGradle.contains("applicationId \"com.legado.app\""))
        assertTrue(buildGradle.contains("applicationIdSuffix '.debug'"))
    }

    @Test
    fun `reader update channel and visible names belong to the fork`() {
        val updateSource = repoFile(
            "app/src/main/java/io/legado/app/help/update/AppUpdateGitHub.kt"
        ).readText()
        val releaseSource = repoFile(
            "app/src/main/java/io/legado/app/help/update/AppReleaseInfo.kt"
        ).readText()
        val defaultStrings = repoFile("app/src/main/res/values/strings.xml").readText()
        val zhStrings = repoFile("app/src/main/res/values-zh/strings.xml").readText()

        assertTrue(updateSource.contains("yuanxiaoaihezhou/Reader"))
        assertTrue(updateSource.contains("AppConst.betaUpdateVariant"))
        assertTrue(releaseSource.contains("startsWith(\"reader_\", ignoreCase = true)"))
        assertTrue(defaultStrings.contains("<string name=\"app_name\">Reader</string>"))
        assertTrue(zhStrings.contains("<string name=\"app_name\">阅读</string>"))
    }

    @Test
    fun `fork build does not depend on upstream Firebase configuration`() {
        val buildGradle = repoFile("app/build.gradle").readText()

        assertFalse(buildGradle.contains("alias libs.plugins.google.services"))
        assertFalse(buildGradle.contains("implementation libs.firebase.analytics"))
        assertFalse(buildGradle.contains("implementation libs.firebase.perf"))
        assertFalse(repoFile("app").resolve("google-services.json").exists())
    }
    @Test
    fun `reader workflows keep fork-specific artifacts and upstream-only checks isolated`() {
        val testWorkflow = repoFile(".github/workflows/test.yml").readText()
        val releaseWorkflow = repoFile(".github/workflows/release.yml").readText()
        val testReleaseWorkflow = repoFile(".github/workflows/TestRelease.yml").readText()

        assertTrue(testWorkflow.contains("github.repository == 'LegadoTeam/legado'"))
        assertTrue(testWorkflow.contains("-type f -name 'reader_*.apk'"))
        assertTrue(testWorkflow.contains("-PreaderReleaseA=true"))
        assertFalse(testWorkflow.contains("readerApplicationIdSuffix"))
        assertFalse(testWorkflow.contains("readerAppNameA"))
        assertTrue(releaseWorkflow.contains("reader-version.sh"))
        assertTrue(releaseWorkflow.contains("-PreaderReleaseA=true"))
        assertTrue(releaseWorkflow.contains("--latest"))
        assertTrue(testReleaseWorkflow.contains("needs.credentials.outputs.available == 'true'"))
        assertTrue(testReleaseWorkflow.contains("<!-- reader-version-code:%s -->"))
    }

}
