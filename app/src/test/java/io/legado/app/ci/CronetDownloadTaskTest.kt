package io.legado.app.ci

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CronetDownloadTaskTest {

    private val downloadTask by lazy {
        readProjectFile("app/download.gradle").replace("\r\n", "\n")
    }

    @Test
    fun `cronet metadata resolves ABI library file names explicitly`() {
        val safeFileName = "\"${'$'}{abi}.so\""
        val ambiguousFileName = "\"${'$'}abi.so\""

        assertTrue(downloadTask.contains("new File(soPath, $safeFileName)"))
        assertFalse(downloadTask.contains("new File(soPath, $ambiguousFileName)"))
    }

    @Test
    fun `cronet future platform reference has release shrinker suppression`() {
        val proguardRules = readProjectFile("app/proguard-rules.pro")

        assertTrue(
            proguardRules.contains(
                "-dontwarn android.app.privatecompute.PccSandboxManager"
            )
        )
    }

    @Test
    fun `cronet protobuf message fields survive release shrinking`() {
        val proguardRules = readProjectFile("app/cronet-proguard-rules.pro")

        assertTrue(
            proguardRules.contains(
                "-keepclassmembers class * extends " +
                    "org.chromium.net.internal.com.google.protobuf.GeneratedMessageLite"
            )
        )
    }

    @Test
    fun `cronet updater retains shared and common shrinker rules`() {
        val updater = readProjectFile(".github/scripts/cronet.sh")
        val syncRules = updater.substringAfter("sync_proguard_rules() {")
            .substringBefore("properties_file=")

        assertTrue(syncRules.contains("cronet_shared_proguard.cfg"))
        assertTrue(syncRules.contains("cronet_impl_common_proguard.cfg"))
    }

    private fun readProjectFile(path: String): String {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir)) {
            it.parentFile
        }.map {
            File(it, path)
        }.first { it.isFile }.readText()
    }
}
