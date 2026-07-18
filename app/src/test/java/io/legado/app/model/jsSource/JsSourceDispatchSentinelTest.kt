package io.legado.app.model.jsSource

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JsSourceDispatchSentinelTest {

    @Test
    fun `web book entries dispatch before declarative handling`() {
        val webBook = readProjectFile(
            "app/src/main/java/io/legado/app/model/webBook/WebBook.kt"
        )
        val contracts = listOf(
            DispatchContract("searchBookAwait", "JsSourceBook.searchAwait", "val searchUrl"),
            DispatchContract("exploreBookAwait", "JsSourceBook.exploreAwait", "val ruleData"),
            DispatchContract("getBookInfoAwait", "JsSourceBook.getBookInfoAwait", "book.removeAllBookType()"),
            DispatchContract("getChapterListAwait", "JsSourceBook.getChapterListAwait", "book.removeAllBookType()"),
            DispatchContract("getContentAwait", "JsSourceBook.getContentAwait", "val contentRule"),
        )

        contracts.forEach { contract ->
            val method = suspendMethodBody(webBook, contract.methodName)
            val dispatch = method.indexOf(contract.dispatchCall)
            val declarativeStart = method.indexOf(contract.declarativeStart)

            assertTrue("${contract.methodName} is missing ${contract.dispatchCall}", dispatch >= 0)
            assertTrue(
                "${contract.methodName} is missing declarative marker ${contract.declarativeStart}",
                declarativeStart >= 0,
            )
            assertTrue(
                "${contract.methodName} must dispatch JS sources before declarative handling",
                dispatch < declarativeStart,
            )
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

    private data class DispatchContract(
        val methodName: String,
        val dispatchCall: String,
        val declarativeStart: String,
    )
}
