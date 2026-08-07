package io.legado.app.base

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PredictiveBackTest {

    @Test
    fun `base activity leaves default back navigation to the system`() {
        val baseActivity = File(
            "src/main/java/io/legado/app/base/BaseActivity.kt"
        ).readText()
        val blanketFinishCallback = Regex(
            """onBackPressedDispatcher\.addCallback\(this\)\s*\{\s*finish\(\)\s*}"""
        )

        assertFalse(blanketFinishCallback.containsMatchIn(baseActivity))

        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("""android:enableOnBackInvokedCallback="true"""))
    }
}
