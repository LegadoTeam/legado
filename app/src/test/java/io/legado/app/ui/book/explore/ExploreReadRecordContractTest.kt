package io.legado.app.ui.book.explore

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExploreReadRecordContractTest {

    @Test
    fun `discover list observes read records like the search page`() {
        val viewModel = projectFile(
            "src/main/java/io/legado/app/ui/book/explore/ExploreShowViewModel.kt"
        ).normalized()

        assertTrue(viewModel.contains("appDb.readRecordDao.flowBooks().distinctUntilChanged()"))
        assertTrue(viewModel.contains("readRecordIndex = ReadRecordIndex.of(it)"))
        assertTrue(viewModel.contains("""upAdapterLiveData.postValue("hasReadRecord")"""))
        assertTrue(viewModel.contains("if (!AppConfig.showSearchReadRecord) { return false }"))
        assertTrue(viewModel.contains("readRecordIndex.contains(book.name, book.author)"))
    }

    @Test
    fun `discover rows show the orange dot only for books off the shelf`() {
        val adapter = projectFile(
            "src/main/java/io/legado/app/ui/book/explore/ExploreShowAdapter.kt"
        ).normalized()

        assertTrue(adapter.contains(""""isInBookshelf", "hasReadRecord" -> upIndicator(binding, item)"""))
        assertTrue(
            adapter.contains(
                "binding.ivReadRecord.isVisible = !isInBookshelf && callBack.hasReadRecord(item)"
            )
        )
    }

    @Test
    fun `discover menu shares the read record switch with search`() {
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/book/explore/ExploreShowActivity.kt"
        ).normalized()

        assertTrue(activity.contains("menu.add(R.string.show_search_read_record)"))
        assertTrue(activity.contains("isChecked = AppConfig.showSearchReadRecord"))
        assertTrue(activity.contains("return viewModel.hasReadRecord(book)"))
    }

    private fun File.normalized() = readText().replace(Regex("\\s+"), " ")

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
