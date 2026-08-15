package io.legado.app.ui.book.read.config

import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewIconSvgTemplateTest {

    @Test
    fun `legacy config starts with no svg templates`() {
        val config = GSON.fromJsonObject<ReadBookConfig.Config>("{}").getOrThrow()

        assertTrue(config.reviewIconSvgTemplates.isEmpty())
    }

    @Test
    fun `svg templates are normalized deduplicated and independently copied`() {
        val config = ReadBookConfig.Config()
        config.putReviewIconSvgTemplate(" First ", " <svg>first</svg> ")
        config.putReviewIconSvgTemplate("Renamed", "<svg>first</svg>")

        assertEquals(
            listOf(ReadBookConfig.ReviewIconSvgTemplate("Renamed", "<svg>first</svg>")),
            config.reviewIconSvgTemplates
        )

        val copy = config.copy()
        copy.putReviewIconSvgTemplate("Second", "<svg>second</svg>")
        copy.removeReviewIconSvgTemplate(" <svg>first</svg> ")

        assertEquals(1, config.reviewIconSvgTemplates.size)
        assertEquals(
            listOf(ReadBookConfig.ReviewIconSvgTemplate("Second", "<svg>second</svg>")),
            copy.reviewIconSvgTemplates
        )

        val restored = GSON.fromJsonObject<ReadBookConfig.Config>(
            GSON.toJson(config)
        ).getOrThrow()
        assertEquals(config.reviewIconSvgTemplates, restored.reviewIconSvgTemplates)
        assertEquals(
            config.reviewIconSvgTemplates,
            config.toMap()["reviewIconSvgTemplates"]
        )
    }
}
