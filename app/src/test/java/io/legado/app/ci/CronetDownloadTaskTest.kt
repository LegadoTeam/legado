package io.legado.app.ci

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CronetDownloadTaskTest {

    private val downloadTask by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val downloadFile = generateSequence(File(userDir)) {
            it.parentFile
        }.map {
            File(it, "app/download.gradle")
        }.first { it.isFile }
        downloadFile.readText().replace("\r\n", "\n")
    }

    @Test
    fun `cronet metadata resolves ABI library file names explicitly`() {
        val safeFileName = "\"${'$'}{abi}.so\""
        val ambiguousFileName = "\"${'$'}abi.so\""

        assertTrue(downloadTask.contains("new File(soPath, $safeFileName)"))
        assertFalse(downloadTask.contains("new File(soPath, $ambiguousFileName)"))
    }
}
