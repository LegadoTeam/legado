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

    private fun readProjectFile(path: String): String {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir)) {
            it.parentFile
        }.map {
            File(it, path)
        }.first { it.isFile }.readText()
    }
}
