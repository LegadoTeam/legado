package io.legado.app.config

import cn.hutool.crypto.SmUtil
import org.junit.Assert.assertEquals
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
}
