package io.legado.app.model.jsSource

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JsSourceTocWriteBackSentinelTest {

    @Test
    fun `chapter parsing writes book metadata after empty check`() {
        val source = readProjectFile(
            "app/src/main/java/io/legado/app/model/jsSource/JsSourceBook.kt"
        )
        val method = suspendMethodBody(source, "getChapterListAwait")
        val steps = listOf(
            "JsSourceMarshaller.parseChapters",
            "if (chapters.isEmpty())",
            "BookChapterList.updateBookTocInfo(book, chapters)",
            "\n            chapters",
        )
        val positions = steps.map { step ->
            method.indexOf(step).also { position ->
                assertTrue("getChapterListAwait is missing step: $step", position >= 0)
            }
        }

        positions.zipWithNext().forEach { (first, second) ->
            assertTrue("getChapterListAwait steps are out of order", first < second)
        }
    }

    private fun suspendMethodBody(source: String, methodName: String): String {
        val start = source.indexOf("suspend fun $methodName(")
        check(start >= 0) { "Missing suspend method $methodName" }
        val bodyStart = source.indexOf('{', start)
        check(bodyStart >= 0) { "Missing body for suspend method $methodName" }
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart + 1, index)
                }
            }
        }
        error("Unclosed body for suspend method $methodName")
    }

    private fun readProjectFile(path: String): String {
        val userDirectory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        val repositoryRoot = generateSequence(userDirectory) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
        requireNotNull(repositoryRoot) { "Repository root not found from $userDirectory" }
        val file = File(repositoryRoot, path)
        require(file.isFile) { "Project file not found: $file" }
        return file.readText()
    }
}
