package io.legado.app.config

import cn.hutool.crypto.SmUtil
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HutoolCryptoDependencyTest {

    @Test
    fun `hutool sm algorithms include their provider`() {
        assertTrue(SmUtil::class.java.methods.isNotEmpty())
        assertEquals(
            "66c7f0f462eeedd9d1f2d46bdc10e4e2" +
                "4167c4875cf2f7a2297da02b8f4ba8e0",
            SmUtil.sm3("abc"),
        )
    }

    @Test
    fun `bouncy castle provider survives release shrinking`() {
        val rules = projectFile("app/proguard-rules.pro").readText()

        assertTrue(rules.contains("-keep class org.bouncycastle.jce.provider.** { *; }"))
        assertTrue(rules.contains("-keep class org.bouncycastle.jcajce.provider.** { *; }"))
        assertTrue(rules.contains("-keep class org.bouncycastle.pqc.jcajce.provider.** { *; }"))
        assertFalse(rules.contains("-keep class org.bouncycastle.** { *; }"))
    }

    private fun projectFile(path: String): File {
        var root = File(requireNotNull(System.getProperty("user.dir")))
        repeat(6) {
            val candidate = File(root, path)
            if (candidate.exists()) return candidate
            root = root.parentFile ?: error("Project root not found for: $path")
        }
        error("Project path not found: $path")
    }
}
