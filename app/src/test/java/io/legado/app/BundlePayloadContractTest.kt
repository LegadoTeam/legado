package io.legado.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BundlePayloadContractTest {

    @Test
    fun `migrated recycler payloads use platform Bundle`() {
        val sourceRoot = sourceRoot()
        val violations = migratedPayloadSources.mapNotNull { relativePath ->
            val file = File(sourceRoot, relativePath)
            val source = file.readText()
            val importsAndroidxBundleOf =
                source.contains("import androidx.core.os.bundleOf")
            val callsBundleOf = Regex("""\bbundleOf\s*\(""").containsMatchIn(source)
            val usesPlatformBundle = Regex("""\bBundle\s*\(""").containsMatchIn(source)
            if (importsAndroidxBundleOf || callsBundleOf || !usesPlatformBundle) {
                relativePath
            } else {
                null
            }
        }

        assertTrue(
            "Migrated RecyclerView payloads must use android.os.Bundle directly: $violations",
            violations.isEmpty(),
        )
    }

    private fun sourceRoot(): File {
        return sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .first { it.isDirectory }
    }

    private companion object {
        val migratedPayloadSources = listOf(
            "io/legado/app/ui/book/changesource/ChangeBookSourceDialog.kt",
            "io/legado/app/ui/book/changesource/ChangeChapterSourceDialog.kt",
            "io/legado/app/ui/book/explore/ExploreShowActivity.kt",
            "io/legado/app/ui/book/manage/BookAdapter.kt",
            "io/legado/app/ui/book/search/SearchActivity.kt",
            "io/legado/app/ui/book/source/manage/BookSourceActivity.kt",
            "io/legado/app/ui/book/source/manage/BookSourceAdapter.kt",
            "io/legado/app/ui/book/toc/rule/TxtTocRuleAdapter.kt",
            "io/legado/app/ui/book/toc/rule/TxtTocRuleDialog.kt",
            "io/legado/app/ui/dict/rule/DictRuleAdapter.kt",
            "io/legado/app/ui/main/bookshelf/style1/books/BaseBooksAdapter.kt",
            "io/legado/app/ui/main/bookshelf/style2/BaseBooksAdapter.kt",
            "io/legado/app/ui/replace/ReplaceRuleAdapter.kt",
            "io/legado/app/ui/rss/source/manage/RssSourceAdapter.kt",
        )
    }
}
