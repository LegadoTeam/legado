package io.legado.app.ui.about

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateDialogDownloadContractTest {

    @Test
    fun `beta update uses the system download flow`() {
        val source = File(
            "src/main/java/io/legado/app/ui/about/UpdateDialog.kt"
        ).readText()
        val clickHandler = source.substringAfter(
            "binding.btnBetaUpdate.setOnClickListener"
        ).substringBefore("if (!isBetaUpdate)")

        assertTrue(clickHandler.contains("startDownload(arguments?.getString(\"url\"))"))
        assertFalse(clickHandler.contains("openUrl"))
    }
}
