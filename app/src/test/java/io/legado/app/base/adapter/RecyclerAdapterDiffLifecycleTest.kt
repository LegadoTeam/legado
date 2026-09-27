package io.legado.app.base.adapter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecyclerAdapterDiffLifecycleTest {

    @Test
    fun `diff result is applied on main only while resumed`() {
        val source = projectFile(
            "src/main/java/io/legado/app/base/adapter/RecyclerAdapter.kt"
        ).readText().replace("\r\n", "\n")
        val diffBlock = source.substringAfter("diffJob = Coroutine.async")
            .substringBefore("fun setItem(")
        val lifecycleBlock = source.substringAfter("fun upResumed(")
            .substringBefore("private fun isHeader")

        assertTrue(diffBlock.contains("withContext(Main)"))
        assertTrue(diffBlock.contains("if (!isResumed || diffResult == null)"))
        assertFalse(diffBlock.contains("handler.post"))
        assertFalse(diffBlock.contains("@post"))
        assertFalse(lifecycleBlock.contains("removeCallbacksAndMessages"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
