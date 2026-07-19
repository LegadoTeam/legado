package io.legado.app.ui.code

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SafeEditorResultCodecTest {

    private val gson = Gson()

    @Test
    fun `decodes text returned by evaluate javascript`() {
        val text = "中文\nquote=\" slash=\\ emoji=😀 mark=a${"\u0301".repeat(12)}"
        val payload = gson.toJson(
            mapOf(
                "text" to text,
                "cursorPosition" to 17,
                "dirty" to true
            )
        )

        val result = SafeEditorResultCodec.decode(gson.toJson(payload))

        assertEquals(text, result?.text)
        assertEquals(17, result?.cursorPosition)
        assertTrue(result?.dirty == true)
    }

    @Test
    fun `missing cursor defaults to zero and negative cursor is clamped`() {
        val withoutCursor = gson.toJson(
            gson.toJson(mapOf("text" to "value", "dirty" to true))
        )
        val negativeCursor = gson.toJson(
            gson.toJson(
                mapOf("text" to "value", "cursorPosition" to -5, "dirty" to true)
            )
        )

        assertEquals(0, SafeEditorResultCodec.decode(withoutCursor)?.cursorPosition)
        assertEquals(0, SafeEditorResultCodec.decode(negativeCursor)?.cursorPosition)
    }

    @Test
    fun `unchanged content preserves original bom and mixed line endings`() {
        val original = "\uFEFFa\r\nb\rc\nd"
        val normalized = "\uFEFFa\nb\nc\nd"
        val payload = gson.toJson(
            gson.toJson(
                mapOf(
                    "text" to normalized,
                    "cursorPosition" to 7,
                    "dirty" to false
                )
            )
        )

        val resolved = SafeEditorResultCodec.decode(payload)?.resolveAgainst(original)

        assertEquals(original, resolved?.text)
        assertEquals(8, resolved?.cursorPosition)
        assertFalse(resolved?.dirty ?: true)
    }

    @Test
    fun `changed content uses editor text and clamps cursor`() {
        val payload = gson.toJson(
            gson.toJson(
                mapOf(
                    "text" to "changed\n",
                    "cursorPosition" to 99,
                    "dirty" to true
                )
            )
        )

        val resolved = SafeEditorResultCodec.decode(payload)?.resolveAgainst("original\r\n")

        assertEquals("changed\n", resolved?.text)
        assertEquals(8, resolved?.cursorPosition)
        assertTrue(resolved?.dirty == true)
    }

    @Test
    fun `malformed results are rejected`() {
        assertNull(SafeEditorResultCodec.decode(null))
        assertNull(SafeEditorResultCodec.decode(""))
        assertNull(SafeEditorResultCodec.decode("not-json"))
        assertNull(SafeEditorResultCodec.decode("null"))
        assertNull(
            SafeEditorResultCodec.decode(
                gson.toJson(gson.toJson(mapOf("cursorPosition" to 4)))
            )
        )
        assertNull(
            SafeEditorResultCodec.decode(
                gson.toJson(gson.toJson(mapOf("text" to "value")))
            )
        )
    }

    @Test
    fun `normal code editor layout does not eagerly inflate a webview`() {
        val layout = File(
            repositoryRoot(),
            "app/src/main/res/layout/activity_code_edit.xml"
        ).readText()

        assertFalse(layout.contains("<WebView"))
        assertTrue(layout.contains("android:id=\"@+id/editorContainer\""))
    }

    @Test
    fun `optional source debug action reads the active safe editor`() {
        val activity = activitySource()

        assertTrue(activity.contains("menu.findItem(R.id.menu_debug_source)?.apply"))
        assertTrue(activity.contains("readSafeEditorState { state ->"))
        assertTrue(
            activity.contains(
                "returnText(action, resolvedState.text, resolvedState.cursorPosition)"
            )
        )
    }

    @Test
    fun `safe editor locks snapshots and preserves bom decoding`() {
        val activity = activitySource()
        val lock = activity.indexOf("window.__setEditorReadOnly(true);")
        val snapshot = activity.indexOf("return JSON.stringify({", lock)

        assertTrue(activity.contains("new TextDecoder(\"utf-8\", { ignoreBOM: true })"))
        assertTrue(activity.contains("dirty: editor.value !== initialValue"))
        assertTrue(lock >= 0)
        assertTrue(snapshot > lock)
        assertTrue(activity.contains("!safeEditorReadPending"))
    }

    private fun activitySource(): String {
        return File(
            repositoryRoot(),
            "app/src/main/java/io/legado/app/ui/code/CodeEditActivity.kt"
        ).readText()
    }

    private fun repositoryRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }
}
