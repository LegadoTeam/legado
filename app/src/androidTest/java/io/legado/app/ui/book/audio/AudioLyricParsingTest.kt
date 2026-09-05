package io.legado.app.ui.book.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dirror.lyricviewx.LyricUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioLyricParsingTest {

    @Test
    fun missingAndMetadataOnlySubtitlesHaveNoEntries() {
        listOf(
            null,
            "",
            " \n\t",
            "null",
            " null ",
            "[ti:Episode]\n[ar:Author]\n[re:0 comments]\n[by:Source]",
            "[00:01.00]\n[00:02.000] ",
        ).forEach { lyric ->
            assertTrue(LyricUtil.parseLrc(arrayOf(lyric, null)).isNullOrEmpty())
        }
    }

    @Test
    fun metadataDoesNotHideValidTimedSubtitles() {
        val entries = LyricUtil.parseLrc(
            arrayOf("[ti:Episode]\n[00:01.00]First\n[00:02.125]Second", null)
        ).orEmpty()

        assertEquals(listOf("First", "Second"), entries.map { it.text })
        assertEquals(listOf(1000L, 2125L), entries.map { it.time })
    }
}
