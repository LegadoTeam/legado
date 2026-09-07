package io.legado.app.model

import io.legado.app.data.entities.Book
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManualReplaceRuleContractTest {

    @Test
    fun `manual rule ids survive read config serialization`() {
        val config = Book.ReadConfig(manualReplaceRuleIds = listOf(9L, 4L))
        val restored = GSON.fromJsonObject<Book.ReadConfig>(GSON.toJson(config)).getOrThrow()
        val restoredLegacy = GSON.fromJsonObject<Book.ReadConfig>("{}").getOrThrow()

        assertEquals(listOf(9L, 4L), restored.manualReplaceRuleIds)
        assertTrue(Book.ReadConfig().manualReplaceRuleIds.isEmpty())
        assertTrue(restoredLegacy.manualReplaceRuleIds.isEmpty())
    }

    @Test
    fun `manual mode keeps candidate and reader contracts separated from global rules`() {
        val dao = source("app/src/main/java/io/legado/app/data/dao/ReplaceRuleDao.kt")
        assertTrue(dao.contains("NOT (scopeSource = 1 AND scopeTitle = 0 AND scopeContent = 0)"))
        assertTrue(dao.contains("ORDER BY sortOrder ASC"))

        val processor = source("app/src/main/java/io/legado/app/help/book/ContentProcessor.kt")
        assertTrue(processor.contains("titleReplaceRulesOverride"))
        assertTrue(processor.contains("contentReplaceRulesOverride"))
        assertTrue(processor.contains("contentReplaceEnabled"))

        val reader = source("app/src/main/java/io/legado/app/model/ReadBook.kt")
        assertTrue(reader.contains("manualReplaceRuleIds"))
        assertTrue(reader.contains("replaceEnabledOverride = manualRules?.enabled"))

        val viewModel = source("app/src/main/java/io/legado/app/ui/book/read/ReadBookViewModel.kt")
        assertTrue(viewModel.contains("ReadBook.clearTextChapter()"))

        val menu = source("app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
        assertTrue(menu.contains("R.id.menu_manual_replace_rule -> showDialogFragment<ManualReplaceRulesDialog>()"))
        assertTrue(menu.contains("item.isVisible = !AppConfig.manualReplaceRule"))

        val dialog = source("app/src/main/java/io/legado/app/ui/book/read/ManualReplaceRulesDialog.kt")
        assertTrue(dialog.contains("selectedIds.retainAll"))
        assertTrue(dialog.contains("Mode.ToggleAndReverse"))

        val replaceActivity = source("app/src/main/java/io/legado/app/ui/replace/ReplaceRuleActivity.kt")
        assertTrue(replaceActivity.contains("setResult(RESULT_OK)"))
    }

    @Test
    fun `source changes retain old book read config at the database boundary`() {
        val dao = source("app/src/main/java/io/legado/app/data/dao/BookDao.kt")
        assertTrue(dao.contains("oldBook.readConfig"))
        assertTrue(dao.contains("getReadConfigJson(oldBook.bookUrl)"))
        assertTrue(dao.contains("readConfig = readConfig"))
    }

    private fun source(relativePath: String): String {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, relativePath).readText().replace("\r\n", "\n")
    }
}
