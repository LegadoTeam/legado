package io.legado.app.lib.cronet

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CronetPhoneReportTest {
    @Test fun reportsNestedLibrariesAndTemporaryCopiesWithoutChangingFiles() {
        val root = Files.createTempDirectory("cronet-phone-report").toFile()
        try {
            val components = File(root, "components")
            val temporary = File(root, "download")
            val native = File(components, "arm64-v8a/libcronet.so").apply {
                parentFile!!.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3))
            }
            val unchanged = native.lastModified()
            var report = cronetPhoneStorageReport(components, temporary)
            assertTrue(report.contains("componentFiles=1; componentBytes=3"))
            assertTrue(report.contains("temporaryFiles=0; temporaryBytes=0"))
            temporary.mkdirs()
            val copy = File(temporary, "libcronet.so").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            report = cronetPhoneStorageReport(components, temporary)
            assertTrue(report.contains("temporaryFiles=1; temporaryBytes=3"))
            assertTrue(report.contains("arm64-v8a/libcronet.so; bytes=3; mtime=$unchanged"))
            assertTrue(native.readBytes().contentEquals(copy.readBytes()))
            assertTrue(native.lastModified() == unchanged)
        } finally { root.deleteRecursively() }
    }
}
