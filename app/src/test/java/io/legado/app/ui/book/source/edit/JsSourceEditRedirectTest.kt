package io.legado.app.ui.book.source.edit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JsSourceEditRedirectTest {

    private val activity =
        File("src/main/java/io/legado/app/ui/book/source/edit/BookSourceEditActivity.kt")
            .readText()
    private val dao =
        File("src/main/java/io/legado/app/data/dao/BookSourceDao.kt").readText()

    @Test
    fun `js source detection happens before form initialization`() {
        val redirect = activity.indexOf("redirectJsSourceUrl?.let")
        val formInitialization = activity.indexOf("softKeyboardTool.attachToWindow")

        assertTrue(activity.contains("override fun onCreate(savedInstanceState: Bundle?)"))
        assertTrue(activity.contains("appDb.bookSourceDao.hasJsSource(it)"))
        assertTrue(redirect >= 0)
        assertTrue(formInitialization >= 0)
        assertTrue(redirect < formInitialization)
        assertTrue(activity.contains("if (savedInstanceState == null)"))
        assertTrue(activity.contains("if (redirectJsSourceUrl != null) return"))
    }

    @Test
    fun `early redirect preserves activity result forwarding`() {
        assertTrue(activity.contains("jsSourceEdit.launch"))
        assertTrue(activity.contains("setResult(result.resultCode, result.data)"))
        assertTrue(activity.contains("super.finish()"))
        assertFalse(activity.contains("startActivity<JsSourceEditActivity>"))
    }

    @Test
    fun `dao uses the same blank script semantics as book source`() {
        assertTrue(dao.contains("fun getMainJs(key: String): String?"))
        assertTrue(dao.contains("fun hasJsSource(key: String): Boolean"))
        assertTrue(dao.contains("!getMainJs(key).isNullOrBlank()"))
    }
}
