package io.legado.app.ci

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FirebaseConfigurationTest {

    private val repositoryRoot by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "settings.gradle").isFile }
    }

    @Test
    fun `firebase telemetry configuration is removed`() {
        assertFalse(File(repositoryRoot, "app/google-services.json").exists())

        val appGradle = File(repositoryRoot, "app/build.gradle").readText()
        val rootGradle = File(repositoryRoot, "build.gradle").readText()
        val versionCatalog = File(repositoryRoot, "gradle/libs.versions.toml").readText()
        val privacyPolicy = File(repositoryRoot, "app/src/main/assets/privacyPolicy.md").readText()

        assertFalse(appGradle.contains("google.services"))
        assertFalse(appGradle.contains("firebase.analytics"))
        assertFalse(appGradle.contains("firebase.perf"))
        assertFalse(rootGradle.contains("google.services"))
        assertFalse(versionCatalog.contains("firebase-analytics"))
        assertFalse(versionCatalog.contains("firebase-perf"))
        assertFalse(versionCatalog.contains("google-services"))
        assertTrue(privacyPolicy.contains("不主动使用 Firebase Analytics 或 Performance Monitoring"))

        listOf("test.yml", "BetaRelease.yml").forEach { workflowName ->
            val workflow = File(repositoryRoot, ".github/workflows/$workflowName").readText()
            assertFalse(workflow.contains("google-services.json"))
        }
    }
}
