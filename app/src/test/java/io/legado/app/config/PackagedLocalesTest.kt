package io.legado.app.config

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PackagedLocalesTest {

    @Test
    fun `packaged locales cover every app translation`() {
        val root = repositoryRoot()
        val buildFile = File(root, "app/build.gradle").readText()
        val marker = "resourceConfigurations += ["
        val blockStart = buildFile.indexOf(marker)
        require(blockStart >= 0) { "Missing resourceConfigurations block" }
        val blockEnd = buildFile.indexOf(']', blockStart)
        require(blockEnd > blockStart) { "Unclosed resourceConfigurations block" }
        val configuredLocales = Regex("'([^']+)'")
            .findAll(buildFile.substring(blockStart, blockEnd))
            .map { it.groupValues[1] }
            .toSet()

        val localeDirectory = Regex("values-([a-z]{2,3}(?:-r[A-Z]{2})?)")
        val translatedLocales = File(root, "app/src/main/res")
            .listFiles()
            .orEmpty()
            .mapNotNull { directory ->
                localeDirectory.matchEntire(directory.name)?.groupValues?.get(1)
            }
            .toSet()
        val expectedLocales = translatedLocales + "en"

        assertEquals(
            "Update resourceConfigurations when adding or removing translations",
            expectedLocales.sorted(),
            configuredLocales.sorted(),
        )
    }

    @Test
    fun `runtime orphan exclusions stay narrow`() {
        val buildFile = File(repositoryRoot(), "app/build.gradle").readText()
        val marker = "resources.excludes.addAll(["
        val blockStart = buildFile.indexOf(marker)
        require(blockStart >= 0) { "Missing resource exclusion block" }
        val blockEnd = buildFile.indexOf(']', blockStart)
        require(blockEnd > blockStart) { "Unclosed resource exclusion block" }
        val configuredExclusions = Regex("'([^']+)'")
            .findAll(buildFile.substring(blockStart, blockEnd))
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(
            setOf(
                "tables/Transcoder_*.bin",
                "*.proto",
                "**/*.proto",
                "src/**",
                "kotlin/*.kotlin_builtins",
                "kotlin/**/*.kotlin_builtins",
            ),
            configuredExclusions,
        )
    }

    private fun repositoryRoot(): File {
        val userDirectory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        return generateSequence(userDirectory) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }
}
