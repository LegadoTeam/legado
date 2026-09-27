package io.legado.app.ui.login

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LoginEntryRoutingTest {

    @Test
    fun `login entry points use unified login capability`() {
        val paths = listOf(
            "src/main/java/io/legado/app/ui/autoTask/AutoTaskAdapter.kt",
            "src/main/java/io/legado/app/ui/autoTask/AutoTaskEditActivity.kt",
            "src/main/java/io/legado/app/ui/book/info/BookInfoActivity.kt",
            "src/main/java/io/legado/app/ui/book/audio/AudioPlayActivity.kt",
            "src/main/java/io/legado/app/ui/book/read/ReadMenu.kt",
            "src/main/java/io/legado/app/ui/book/source/edit/BookSourceEditActivity.kt",
            "src/main/java/io/legado/app/ui/video/VideoPlayerActivity.kt",
            "src/main/java/io/legado/app/ui/rss/read/RssJsExtensions.kt",
        )

        paths.forEach { path ->
            assertTrue("$path should use hasLogin()", File(path).readText().contains("hasLogin()"))
        }
    }

    @Test
    fun `auto task management login is first and routes by task id`() {
        val source = File(
            "src/main/java/io/legado/app/ui/autoTask/AutoTaskAdapter.kt"
        ).readText()
        val loginItem =
            "item(context.getString(R.string.login), \"login\", AutoTask.buildSource(task).hasLogin())"

        assertTrue(source.indexOf(loginItem) in 0 until source.indexOf("R.string.auto_task_log"))
        assertTrue(source.contains("\"login\" -> context.startActivity<SourceLoginActivity>"))
        assertTrue(source.contains("putExtra(\"type\", \"autoTask\")"))
        assertTrue(source.contains("putExtra(\"key\", task.id)"))
    }
}
