package io.legado.app.constant

import io.legado.app.help.update.AppVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BetaUpdateCompatibilityTest {

    private val betaSignature =
        "E2400519DF26F329EFC3FA1288DB46E8A23C6AEEB14B5378AD80CA9F8136C146"

    @Test
    fun betaSignatureAndExactPackageSelectTheMatchingVariant() {
        assertEquals(
            AppVariant.BETA_RELEASE,
            resolveBetaUpdateVariant("com.legado.app.release", listOf(betaSignature))
        )
        assertEquals(
            AppVariant.BETA_RELEASEA,
            resolveBetaUpdateVariant("com.legado.app.releaseA", listOf(betaSignature))
        )
    }

    @Test
    fun incompatibleSignaturesAndPackagesAreRejected() {
        assertNull(
            resolveBetaUpdateVariant("com.legado.app.release", listOf("different"))
        )
        assertNull(
            resolveBetaUpdateVariant("com.legado.app.debug", listOf(betaSignature))
        )
        assertNull(
            resolveBetaUpdateVariant("com.legado.app.releaseA.extra", listOf(betaSignature))
        )
        assertNull(resolveBetaUpdateVariant("com.legado.app.release", emptyList()))
        assertNull(
            resolveBetaUpdateVariant(
                "com.legado.app.release",
                listOf(betaSignature, betaSignature)
            )
        )
    }
}
