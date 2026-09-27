package io.legado.app.model.localBook

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EpubZipErrorPropagationTest {

    @Test
    fun `zip entry enumeration keeps malformed archive errors visible`() {
        val root = repositoryRoot()
        val androidZipFile = File(
            root,
            "modules/book/src/main/java/me/ag2s/epublib/util/zip/AndroidZipFile.java",
        ).readText()
        val zipFileWrapper = File(
            root,
            "modules/book/src/main/java/me/ag2s/epublib/util/zip/ZipFileWrapper.java",
        ).readText()
        val resourcesLoader = File(
            root,
            "modules/book/src/main/java/me/ag2s/epublib/epub/ResourcesLoader.java",
        ).readText()

        assertTrue(androidZipFile.contains("public Enumeration<AndroidZipEntry> entries() throws IOException"))
        assertTrue(zipFileWrapper.contains("public Enumeration entries() throws IOException"))
        assertTrue(resourcesLoader.contains("public static Resources loadResources(") &&
            resourcesLoader.contains("    ) throws IOException") &&
            resourcesLoader.contains("Enumeration entries = zipFileWrapper.entries();"))
    }

    private fun repositoryRoot(): File {
        val userDirectory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        return generateSequence(userDirectory) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }
}
