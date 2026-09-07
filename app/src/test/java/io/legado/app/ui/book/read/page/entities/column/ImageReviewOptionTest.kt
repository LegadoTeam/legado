package io.legado.app.ui.book.read.page.entities.column

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageReviewOptionTest {

    @Test
    fun `requires positive integer count and click`() {
        val src = "data:image/svg+xml;base64,QQ"

        assertEquals(
            37 to "getDP(1,37)",
            parseImageReviewOption(
                "$src,{\"type\":\"jjwx\",\"style\":\"TEXT\"," +
                    "\"reviewCount\":\"37\",\"click\":\"getDP(1,37)\"}",
                null
            )
        )
        assertEquals(
            38 to "existingClick()",
            parseImageReviewOption(
                "$src,{\"style\":\"text\",\"reviewCount\":38," +
                    "\"click\":\"optionClick()\"}",
                "existingClick()"
            )
        )

        listOf(
            "$src,{\"type\":\"jjwx\",\"click\":\"toReview(37)\"}",
            "$src,{\"reviewCount\":\"37\",\"click\":\"getDP()\"}",
            "$src,{\"style\":\"full\",\"reviewCount\":\"37\"," +
                "\"click\":\"getDP()\"}",
            "$src,{\"style\":\"text\",\"reviewCount\":\"37\"}",
            "$src,{\"style\":\"text\",\"reviewCount\":\"37\",\"click\":\" \"}",
            "$src,{\"style\":\"text\",\"reviewCount\":\"0\",\"click\":\"getDP()\"}",
            "$src,{\"style\":\"text\",\"reviewCount\":\"-1\",\"click\":\"getDP()\"}",
            "$src,{\"style\":\"text\",\"reviewCount\":\"1.5\",\"click\":\"getDP()\"}",
            "$src,{\"style\":\"text\",\"reviewCount\":\"2147483648\"," +
                "\"click\":\"getDP()\"}",
            "$src,{invalid"
        ).forEach { assertNull(parseImageReviewOption(it, null)) }
    }
}
