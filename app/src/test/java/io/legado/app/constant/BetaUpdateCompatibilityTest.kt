package io.legado.app.constant

import io.legado.app.help.update.AppVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BetaUpdateCompatibilityTest {

    @Test
    fun readerPackageSelectsTheMatchingBetaVariant() {
        assertEquals(
            AppVariant.BETA_RELEASE,
            resolveBetaUpdateVariant("io.github.yuanxiaoaihezhou.reader")
        )
        assertEquals(
            AppVariant.BETA_RELEASEA,
            resolveBetaUpdateVariant("io.github.yuanxiaoaihezhou.reader.releaseA")
        )
    }

    @Test
    fun unrelatedPackagesAreRejected() {
        assertNull(resolveBetaUpdateVariant("io.github.yuanxiaoaihezhou.reader.debug"))
        assertNull(resolveBetaUpdateVariant("io.github.yuanxiaoaihezhou.reader.releaseA.extra"))
        assertNull(resolveBetaUpdateVariant("com.legado.app.release"))
    }
}
