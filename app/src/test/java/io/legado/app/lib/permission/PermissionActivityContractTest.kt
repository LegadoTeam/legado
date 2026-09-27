package io.legado.app.lib.permission

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PermissionActivityContractTest {

    @Test
    fun `suppressed rationale completes the denied request before finishing`() {
        val source = projectFile(
            "app/src/main/java/io/legado/app/lib/permission/PermissionActivity.kt"
        ).readText()
        val marker = "if (getDenyCount(it) > 5) {"
        assertTrue(source.contains(marker))
        val suppressedRequest = source
            .substringAfter(marker)
            .substringBefore("return")

        val callback = "RequestPlugins.sRequestCallback?.onRequestPermissionsResult("
        val notice = "toastOnUi(rationale)"
        assertTrue(suppressedRequest.contains(notice))
        assertTrue(suppressedRequest.contains(callback))
        assertTrue(suppressedRequest.contains("IntArray(0)"))
        assertTrue(suppressedRequest.indexOf(notice) < suppressedRequest.indexOf(callback))
        assertTrue(suppressedRequest.indexOf(callback) < suppressedRequest.indexOf("finish()"))
    }

    private fun projectFile(path: String): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir)) { it.parentFile }
            .map { File(it, path) }
            .first { it.exists() }
    }
}
