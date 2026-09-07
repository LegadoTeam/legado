package io.legado.app.ui.file

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HandleFilePickerContractTest {

    @Test
    fun `system picker advertises both common JavaScript mime types`() {
        val activity = sequenceOf(
            File("src/main/java/io/legado/app/ui/file/HandleFileActivity.kt"),
            File("app/src/main/java/io/legado/app/ui/file/HandleFileActivity.kt")
        ).firstOrNull(File::isFile)?.readText()
            ?: error("Missing HandleFileActivity.kt")

        val jsBranch = activity.substringAfter("\"js\" -> {")
            .substringBefore("                    else ->")
        assertTrue(jsBranch.contains("types.add(\"application/javascript\")"))
        assertTrue(jsBranch.contains("types.add(\"text/javascript\")"))
    }
}
