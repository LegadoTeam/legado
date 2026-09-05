package io.legado.app.ui.book.audio

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class AudioPlayLayoutContractTest {

    @Test
    fun subtitleRemainsAnchoredWhenLyricViewIsGone() {
        val layout = File("src/main/res/layout/activity_audio_play.xml").readText()
        val subtitle = layout.substringAfter("android:id=\"@+id/tv_sub_title\"")
            .substringBefore("<TextView")

        assertTrue(
            subtitle.contains("app:layout_constraintTop_toBottomOf=\"@+id/lyricViewX\""),
            "The subtitle must stay below the lyric area when LyricViewX is GONE",
        )
        assertTrue(
            subtitle.contains("app:layout_constraintBottom_toTopOf=\"@+id/tv_chapter_index\""),
            "The subtitle must remain above the chapter index",
        )
    }
}
