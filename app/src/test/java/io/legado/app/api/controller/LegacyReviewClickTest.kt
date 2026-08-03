package io.legado.app.api.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyReviewClickTest {

    @Test
    fun `only legacy paragraph and chapter review clicks are accepted`() {
        assertEquals(
            "getDP(12,3)",
            parseLegacyReviewClickScript(
                "http://,{\"style\":\"text\",\"js\":\"getDPSvg(3,0)\",\"click\":\"getDP(12,3)\"}"
            )
        )
        assertEquals(
            "getZP(3)",
            parseLegacyReviewClickScript(
                "http://,{'style':'full','click':'getZP(3)'}"
            )
        )
        assertNull(
            parseLegacyReviewClickScript(
                "http://,{\"style\":\"text\",\"click\":\"getZS(3)\"}"
            )
        )
        assertNull(
            parseLegacyReviewClickScript(
                "http://,{\"style\":\"text\",\"click\":\"getDP(3)\"}"
            )
        )
    }
}
