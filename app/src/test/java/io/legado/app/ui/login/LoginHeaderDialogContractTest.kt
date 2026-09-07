package io.legado.app.ui.login

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LoginHeaderDialogContractTest {

    @Test
    fun `login header dialogs render empty content instead of a title only dialog`() {
        val sourceLogin = readProjectFile(
            "src/main/java/io/legado/app/ui/login/SourceLoginDialog.kt"
        )
        val httpTts = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/config/HttpTtsEditDialog.kt"
        )

        val sourceLoginAction = loginHeaderAction(sourceLogin)
        val httpTtsAction = loginHeaderAction(httpTts)

        assertEmptyHeaderFallback(sourceLoginAction)
        assertEmptyHeaderFallback(httpTtsAction)
        val populatedHeaderBranch = sourceLoginAction.substringAfter(
            "?.let { loginHeader ->",
            missingDelimiterValue = ""
        ).substringBefore(
            "} ?: setMessage(R.string.empty)",
            missingDelimiterValue = ""
        )
        assertTrue(populatedHeaderBranch.contains("positiveButton(R.string.copy_text)"))
    }

    private fun loginHeaderAction(source: String): String =
        source.substringAfter(
            "R.id.menu_show_login_header -> alert {",
            missingDelimiterValue = ""
        ).substringBefore(
            "R.id.menu_del_login_header",
            missingDelimiterValue = ""
        )

    private fun assertEmptyHeaderFallback(action: String) {
        assertTrue(action.isNotEmpty())
        assertTrue(action.contains("takeIf { it.isNotBlank() }"))
        assertTrue(action.contains("?: setMessage(R.string.empty)"))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
