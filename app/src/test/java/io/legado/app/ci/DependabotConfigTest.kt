package io.legado.app.ci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DependabotConfigTest {

    private val repositoryRoot by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, ".github/dependabot.yml").isFile }
    }

    private val configText by lazy {
        File(repositoryRoot, ".github/dependabot.yml")
            .readText()
            .replace("\r\n", "\n")
    }

    @Test
    fun `public Gradle repositories do not use placeholder credentials`() {
        assertFalse(configText.contains("registries:"))
        assertFalse(configText.contains("username: dummy"))
        assertFalse(configText.contains("password: dummy"))
    }

    @Test
    fun `Gradle updates stay grouped and bounded`() {
        val block = ecosystemBlock("gradle")

        assertTrue(block.contains("directory: \"/\""))
        assertTrue(block.contains("interval: \"weekly\""))
        assertTrue(block.contains("day: \"monday\""))
        assertTrue(block.contains("open-pull-requests-limit: 5"))
        assertTrue(block.contains("kotlin_KSP:"))
        assertTrue(block.contains("\"org.jetbrains.kotlin:*\""))
        assertTrue(block.contains("\"com.google.devtools.ksp\""))
    }

    @Test
    fun `npm updates target the Web module instead of the repository root`() {
        val block = ecosystemBlock("npm")

        assertTrue(File(repositoryRoot, "modules/web/package.json").isFile)
        assertTrue(block.contains("directory: \"/modules/web\""))
        assertTrue(block.contains("interval: \"weekly\""))
        assertTrue(block.contains("day: \"monday\""))
        assertTrue(block.contains("open-pull-requests-limit: 5"))
    }

    @Test
    fun `all update ecosystems use the same pull request limit`() {
        val actionsBlock = ecosystemBlock("github-actions")

        assertTrue(actionsBlock.contains("directory: \"/\""))
        assertTrue(actionsBlock.contains("day: \"monday\""))
        assertEquals(3, "open-pull-requests-limit: 5".toRegex().findAll(configText).count())
    }

    private fun ecosystemBlock(ecosystem: String): String {
        val marker = "  - package-ecosystem: \"$ecosystem\""
        val start = configText.indexOf(marker)
        require(start >= 0) { "Missing Dependabot ecosystem: $ecosystem" }
        val next = configText.indexOf("\n  - package-ecosystem:", start + marker.length)
        return if (next < 0) configText.substring(start) else configText.substring(start, next)
    }
}
