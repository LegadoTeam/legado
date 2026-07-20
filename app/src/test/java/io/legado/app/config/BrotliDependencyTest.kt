package io.legado.app.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BrotliDependencyTest {

    @Test
    fun `brotli uses the direct decoder without the okhttp wrapper`() {
        val root = repositoryRoot()
        val versionCatalog = File(root, "gradle/libs.versions.toml").readText()
        val appBuild = File(root, "app/build.gradle").readText()

        assertTrue(versionCatalog.contains("brotli = \"0.1.2\""))
        assertTrue(
            versionCatalog.contains(
                "brotli-dec = { module = \"org.brotli:dec\", version.ref = \"brotli\" }"
            )
        )
        assertFalse(versionCatalog.contains("okhttp-brotli"))
        assertTrue(appBuild.contains("implementation(libs.brotli.dec)"))
        assertFalse(appBuild.contains("implementation(libs.okhttpBrotli)"))
    }

    private fun repositoryRoot(): File {
        val userDirectory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        return generateSequence(userDirectory) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }
}
