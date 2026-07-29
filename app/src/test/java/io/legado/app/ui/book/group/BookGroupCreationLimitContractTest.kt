package io.legado.app.ui.book.group

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookGroupCreationLimitContractTest {

    @Test
    fun `only 63 positive group ids can be created`() {
        val daoSource = projectFile("src/main/java/io/legado/app/data/dao/BookGroupDao.kt")
            .readText()
        val manageSource = projectFile(
            "src/main/java/io/legado/app/ui/book/group/GroupManageDialog.kt"
        ).readText()
        val editSource = projectFile(
            "src/main/java/io/legado/app/ui/book/group/GroupEditDialog.kt"
        ).readText()

        assertTrue(
            daoSource.contains(
                "select count(*) < 63 from book_groups where groupId > 0"
            )
        )
        assertTrue(manageSource.contains("分组已达上限(63个)"))
        assertTrue(
            editSource.contains("bookGroup == null && !appDb.bookGroupDao.canAddGroup")
        )
        assertTrue(editSource.contains("分组已达上限(63个)"))
    }

    private fun projectFile(pathInApp: String): File =
        listOf(File(pathInApp), File("app/$pathInApp")).first { it.isFile }
}
