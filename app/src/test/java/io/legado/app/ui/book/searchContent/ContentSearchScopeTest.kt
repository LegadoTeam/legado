package io.legado.app.ui.book.searchContent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import io.legado.app.ui.book.read.page.provider.ChapterTitleParser
import io.legado.app.help.book.BookContent
import io.legado.app.help.book.processBookParagraphs
import io.legado.app.ui.book.read.findContentMatches

class ContentSearchScopeTest {
    private fun hits(chapter: Int) = listOf(10, 40, 90).mapIndexed { index, position ->
        SearchResult(chapterIndex = chapter, resultCountWithinChapter = index,
            queryIndexInChapter = position, query = "hit")
    }

    @Test
    fun upwardSearchOrdersEveryChapterFromNearestToFarthestWithoutRenumbering() {
        val current = selectContentSearchResults(hits(2), ContentSearchScope.UP, 40)
        val previous = selectContentSearchResults(hits(1), ContentSearchScope.UP)
        assertEquals(listOf(2 to 1, 2 to 0, 1 to 2, 1 to 1, 1 to 0),
            (current + previous).map { it.chapterIndex to it.resultCountWithinChapter })
    }

    @Test
    fun downwardSearchIncludesBoundaryAndGlobalDoesNotFilter() {
        val results = hits(2)
        assertEquals(listOf(1, 2), selectContentSearchResults(results, ContentSearchScope.DOWN, 40)
            .map { it.resultCountWithinChapter })
        assertEquals(results, selectContentSearchResults(results, ContentSearchScope.ALL, 40))
    }

    @Test
    fun unchangedRepeatedTextKeepsItsExactPositionDespiteLayoutFinalNewline() {
        val body = "同样的段落重复出现。\n".repeat(100).removeSuffix("\n")
        val position = body.length / 2
        val anchor = ContentSearchAnchor.capture(1, body + "\n", 0, position)!!
        assertEquals(position, anchor.resolve(body, 0))
        assertEquals(position, anchor.resolve(body + "\n", 0))
    }

    @Test
    fun hidingOrChangingMultilineTitleDoesNotShiftBodyBoundary() {
        val title = "第一章\n副标题\n"
        val body = "　　这里是正文的目标，后面还有正文。"
        val bodyPosition = body.indexOf("目标")
        val anchor = ContentSearchAnchor.capture(1, title + body, title.length,
            title.length + bodyPosition)!!
        assertEquals(bodyPosition, anchor.resolve(body + "\n", 0))
        val changedTitle = "新标题\n　　带缩进的副标题\n"
        assertEquals(changedTitle.length + bodyPosition,
            anchor.resolve(changedTitle + body, changedTitle.length))
    }

    @Test
    fun hiddenTitleReadingPositionMapsIntoSearchTextIncludingTitle() {
        val body = "　　目标在没有显示标题的正文里。"
        val anchor = ContentSearchAnchor.capture(1, body, 0, 2)!!
        val title = "目标章节\n"
        assertEquals(title.length + 2, anchor.resolve(title + body, title.length))
    }

    @Test
    fun titleAnchorDoesNotJumpToSimilarBodyTextWhenTitleIsHidden() {
        val title = "目标章节\n"
        val anchor = ContentSearchAnchor.capture(1, title + "目标章节正文", title.length, 0)!!
        assertNull(anchor.resolve("目标章节正文", 0))
    }

    @Test
    fun splitHeadingSearchAndClicksUseTheSameTitleLinesAsLayout() {
        val title = "第一章 标题"
        val split = ChapterTitleParser.split(title, enabled = true, isVolume = false)!!
        val body = "正文目标"
        listOf(false, true).forEach { enabled ->
            val renderedTitle = if (enabled) "${split.first}\n${split.second}\n" else "$title\n"
            val (search, titleLength) = directionalContentSearchText(
                "$title\n$body", title.length + 1, title, enabled, false, false, "")
            assertEquals(renderedTitle + body, search)
            val snapshot = ContentSearchText(1, search, titleLength)
            val start = ContentSearchAnchor.capture(1, renderedTitle + body + "\n", renderedTitle.length, 0)!!
            assertEquals(0, snapshot.resolve(start))
            listOf("第一章", "标题").forEach { query ->
                val position = search.indexOf(query)
                val result = SearchResult(query = query, chapterIndex = 1,
                    contentAnchor = snapshot.anchor(position))
                val renderedPosition = renderedTitle.indexOf(query)
                assertEquals(renderedPosition until renderedPosition + query.length,
                    resolveContentSearchMatch(result, renderedTitle + body + "\n", renderedTitle.length))
            }
            if (enabled) {
                assertTrue(findContentMatches(search, "章 标", false, true).isEmpty())
            }
        }
    }

    @Test
    fun multilineTitleRemovesProcessorBodyIndentWithoutMovingBodyMatches() {
        val displayTitle = "第一章\n\n 副标题 "
        val paragraphs = processBookParagraphs("$displayTitle\n 正文目标 ", true, 3, ">>")
        val processed = BookContent(false, paragraphs.textList, null, paragraphs.titleLength, displayTitle)
        assertTrue(processed.toString().contains(">>副标题"))
        val (search, titleLength) = directionalContentSearchText(processed.toString(), processed.titleLength,
            displayTitle, true, false, false, ">>")
        val renderedTitle = "第一章\n副标题\n"
        assertEquals(renderedTitle + ">>正文目标", search)
        val snapshot = ContentSearchText(1, search, titleLength)
        listOf("副标题", "正文目标").forEach { query ->
            val position = search.indexOf(query)
            val result = SearchResult(query = query, chapterIndex = 1, contentAnchor = snapshot.anchor(position))
            assertEquals(position until position + query.length,
                resolveContentSearchMatch(result, search + "\n", titleLength))
        }
        val (hidden, hiddenTitleLength) = directionalContentSearchText(processed.toString(), processed.titleLength,
            displayTitle, true, true, false, ">>")
        assertEquals(">>正文目标", hidden)
        assertEquals(0, hiddenTitleLength)
    }

    @Test
    fun changedMissingOrRepeatedTitleContextCannotGuessAHit() {
        val title = "重复标题".repeat(50) + "\n"
        val snapshot = ContentSearchText(1, title + "正文", title.length)
        val result = SearchResult(query = "重复", chapterIndex = 1, contentAnchor = snapshot.anchor(40))
        assertNull(resolveContentSearchMatch(result, "正文", 0))
        val changedTitle = "其他" + title
        assertNull(resolveContentSearchMatch(result, changedTitle + "正文", changedTitle.length))
        val splitTitle = "第一章\n标题\n"
        val crossing = SearchResult(query = "章 标", chapterIndex = 1,
            contentAnchor = ContentSearchText(1, splitTitle + "正文", splitTitle.length).anchor(2))
        assertNull(resolveContentSearchMatch(crossing, splitTitle + "正文", splitTitle.length))
    }

    @Test
    fun emptyTitleDirectionSearchUsesTheReaderFirstParagraphIndent() {
        listOf("", "  ", "\n　\n").forEach { title ->
            val body = " 正文目标\n 后段 "
            val paragraphs = processBookParagraphs("$title\n$body", true, title.lines().size, ">>")
            val legacy = BookContent(false, paragraphs.textList, null, paragraphs.titleLength, title)
            assertEquals("正文目标\n>>后段", legacy.toString())
            val reader = processBookParagraphs(body, false, 0, ">>").textList.joinToString("\n")
            listOf(false, true).forEach { hideTitle ->
                val (search, titleLength) = directionalContentSearchText(legacy.toString(), legacy.titleLength,
                    title, false, hideTitle, false, ">>")
                assertEquals(reader, search)
                assertEquals(0, titleLength)
                val anchor = ContentSearchAnchor.capture(1, reader + "\n", 0, 0)!!
                assertEquals(0, ContentSearchText(1, search, 0).resolve(anchor))
                val position = search.indexOf("目标")
                val result = SearchResult(query = "目标", chapterIndex = 1,
                    contentAnchor = ContentSearchAnchor.capture(1, search, 0, position))
                assertEquals(position until position + 2, resolveContentSearchMatch(result, reader + "\n", 0))
            }
        }
    }

    @Test
    fun uniqueContextCanMoveAfterReplacementOutsideTheReadingWindow() {
        val body = "前缀".repeat(80) + "唯一的阅读位置。" + "后缀".repeat(80)
        val position = body.indexOf("唯一")
        val anchor = ContentSearchAnchor.capture(1, body, 0, position)!!
        val changed = "净化后多了这段文字" + body
        assertEquals(changed.indexOf("唯一"), anchor.resolve(changed, 0))
    }

    @Test
    fun repeatedContextInChangedTextIsRejectedEvenIfOldOffsetStillMatches() {
        val paragraph = "重复的阅读内容。".repeat(30)
        val body = paragraph + "\n" + paragraph
        val anchor = ContentSearchAnchor.capture(1, body, 0, 100)!!
        assertNull(anchor.resolve(body + "发生了变化", 0))
    }

    @Test
    fun replacementInsideContextCannotSilentlyUseOldOffset() {
        val body = "前面的正文，然后是阅读目标和后续文字。"
        val anchor = ContentSearchAnchor.capture(1, body, 0, body.indexOf("目标"))!!
        assertNull(anchor.resolve(body.replace("阅读目标", "不同内容"), 0))
        assertNull(anchor.resolve(body, -1))
        assertNull(ContentSearchAnchor.capture(1, body, -1, 0))
        assertNull(ContentSearchAnchor.capture(1, body, 0, body.length + 1))
    }

    @Test
    fun largeChapterAnchorsKeepBoundedContextAndShareOneFingerprint() {
        val body = "重复正文。".repeat(250_000)
        val snapshot = ContentSearchText(3, body, 0)
        val first = snapshot.anchor(500_000)!!
        val second = snapshot.anchor(750_000)!!
        assertTrue(first.before.length <= 32)
        assertTrue(first.after.length <= 64)
        assertEquals(32, first.sourceDigest.size)
        assertSame(first.sourceDigest, second.sourceDigest)
        assertEquals(750_000, second.resolve(body, 0))
    }

    @Test
    fun filteredHitClicksTheSameBodyOccurrenceWhenTitleMatchDisappears() {
        val title = "目标章节\n"
        val body = "目标在前，目标在后。"
        val snapshot = ContentSearchText(2, title + body, title.length)
        val positions = listOf(0, title.length, title.length + body.lastIndexOf("目标"))
        val results = positions.mapIndexed { ordinal, position ->
            SearchResult(resultCountWithinChapter = ordinal, query = "目标", chapterIndex = 2,
                queryIndexInChapter = position, contentAnchor = snapshot.anchor(position))
        }
        val selected = selectContentSearchResults(results, ContentSearchScope.DOWN, title.length + 1).single()
        assertEquals(2, selected.resultCountWithinChapter)
        assertEquals(body.lastIndexOf("目标") until body.lastIndexOf("目标") + 2,
            resolveContentSearchMatch(selected, body + "\n", 0))
    }

    @Test
    fun regexClickUsesActualMatchedLengthAndRefusesChangedMatch() {
        val body = "before target123 after"
        val position = body.indexOf("target")
        val result = SearchResult(query = "target\\d+", isRegex = true,
            contentAnchor = ContentSearchAnchor.capture(0, body, 0, position))
        assertEquals(position until position + 9, resolveContentSearchMatch(result, body, 0))
        assertNull(resolveContentSearchMatch(result, body.replace("target123", "missing"), 0))
    }

    @Test
    fun zeroWidthRegexRetainsItsPositionWithoutInventingASelectedCharacter() {
        val body = "前文目标后文"
        val position = body.indexOf("目标")
        val result = SearchResult(query = "(?=目标)", isRegex = true,
            contentAnchor = ContentSearchAnchor.capture(0, body, 0, position))
        val match = resolveContentSearchMatch(result, body + "\n", 0)!!
        assertEquals(position, match.first)
        assertEquals(0, match.last + 1 - match.first)
        assertTrue(match.isEmpty())
    }

    @Test
    fun chapterEndRegexIgnoresOnlyTheLayoutBodySeparator() {
        val title = "第一章\n"
        listOf("", "正文").forEach { body ->
            val search = title + body
            val layout = search + if (body.isEmpty()) "" else "\n"
            val result = SearchResult(query = "\\z", isRegex = true,
                contentAnchor = ContentSearchAnchor.capture(0, search, title.length, search.length))
            val match = resolveContentSearchMatch(result, layout, title.length)!!
            assertEquals(search.length, match.first)
            assertTrue(match.isEmpty())
        }
    }
}
