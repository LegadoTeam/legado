package io.legado.app.ui.code

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CodeTextTransferContractTest {
    @Test
    fun `large result file transport is opt in for replacement editor`() {
        val activity = projectFile("app/src/main/java/io/legado/app/ui/code/CodeEditActivity.kt").readText()
        val replacement = projectFile(
            "app/src/main/java/io/legado/app/ui/replace/edit/ReplaceEditActivity.kt"
        ).readText()
        assertTrue(activity.contains("intent.getBooleanExtra(\"useTextFile\", false)"))
        assertTrue(replacement.contains("putExtra(\"useTextFile\", true)"))
    }

    private fun projectFile(path: String): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir)) { it.parentFile }
            .map { File(it, path) }
            .first { it.exists() }
    }
}
