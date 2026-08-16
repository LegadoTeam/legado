package io.legado.app.ui.association

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImportBookSourceStateTest {

    @Test
    fun `classifies new updated and existing sources`() {
        assertEquals(
            ImportBookSourceStatus(isNew = true, isUpdate = false),
            resolveImportBookSourceStatus(importedLastUpdateTime = 100, localLastUpdateTime = null),
        )
        assertEquals(
            ImportBookSourceStatus(isNew = false, isUpdate = true),
            resolveImportBookSourceStatus(importedLastUpdateTime = 101, localLastUpdateTime = 100),
        )
        assertEquals(
            ImportBookSourceStatus(isNew = false, isUpdate = false),
            resolveImportBookSourceStatus(importedLastUpdateTime = 100, localLastUpdateTime = 100),
        )
        assertEquals(
            ImportBookSourceStatus(isNew = false, isUpdate = false),
            resolveImportBookSourceStatus(importedLastUpdateTime = 99, localLastUpdateTime = 100),
        )
    }

    @Test
    fun `default selection follows source status`() {
        val update = ImportBookSourceStatus(isNew = false, isUpdate = true)
        val existing = ImportBookSourceStatus(isNew = false, isUpdate = false)

        assertTrue(resolveImportSourceSelection(update, manualSelection = null))
        assertFalse(resolveImportSourceSelection(existing, manualSelection = null))
    }

    @Test
    fun `manual selection override survives repeated status changes`() {
        val newSource = ImportBookSourceStatus(isNew = true, isUpdate = false)
        val existing = ImportBookSourceStatus(isNew = false, isUpdate = false)

        assertFalse(resolveImportSourceSelection(newSource, manualSelection = false))
        assertFalse(resolveImportSourceSelection(existing, manualSelection = false))
        assertFalse(resolveImportSourceSelection(newSource, manualSelection = false))
        assertTrue(resolveImportSourceSelection(existing, manualSelection = true))
    }

    @Test
    fun `direct JS source import preserves coroutine cancellation`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/ui/association/ImportBookSourceViewModel.kt"
        )
        assertTrue(source.contains("else -> runCatchingCancellable"))
        val directImport = source.substringAfter("else -> runCatchingCancellable")
            .substringBefore("}.getOrElse")

        assertTrue(directImport.contains("JsSourceConfig.extract(mText, coroutineContext)"))
    }

    @Test
    fun `book source import shows icon for empty states and hides it for results`() {
        val dialog = readProjectFile(
            "src/main/java/io/legado/app/ui/association/ImportBookSourceDialog.kt"
        )
        val errorState = dialog.substringAfter("viewModel.errorLiveData.observe")
            .substringBefore("viewModel.successLiveData.observe")
        assertTrue(errorState.contains("binding.ivEmpty.visible()"))
        val successState = dialog.substringAfter("viewModel.successLiveData.observe")
            .substringBefore("viewModel.sourceUpdatePending.observe")
        val populatedState = successState.substringAfter("if (it > 0)")
            .substringBefore("} else {")
        assertTrue(populatedState.contains("binding.ivEmpty.gone()"))
        assertTrue(populatedState.contains("binding.tvMsg.gone()"))
        assertTrue(successState.substringAfter("} else {").contains("binding.ivEmpty.visible()"))

        val layout = readProjectFile("src/main/res/layout/dialog_recycler_view.xml")
        assertTrue(layout.contains("@+id/ll_empty"))
        val emptyIcon = layout.substringAfter("@+id/iv_empty").substringBefore("/>")
        assertTrue(emptyIcon.contains("@drawable/ic_description"))
        assertTrue(emptyIcon.contains("android:visibility=\"gone\""))
        val message = layout.substringAfter("@+id/tv_msg").substringBefore("/>")
        assertTrue(message.contains("android:layout_width=\"match_parent\""))
        assertTrue(message.contains("android:visibility=\"gone\""))

        val icon = readProjectFile("src/main/res/drawable/ic_description.xml")
        assertTrue(icon.contains("android:pathData="))
    }

    @Test
    fun `import comment rows reset collapsed state when rebound`() {
        listOf(
            "src/main/java/io/legado/app/ui/association/ImportBookSourceDialog.kt",
            "src/main/java/io/legado/app/ui/association/ImportRssSourceDialog.kt",
            "src/main/java/io/legado/app/ui/association/ImportTxtTocRuleDialog.kt",
        ).forEach { path ->
            val source = readProjectFile(path)
            val textIndex = source.indexOf("showComment.text =")
            val resetIndex = source.indexOf("showComment.maxLines = 3", textIndex)
            val visibleIndex = source.indexOf("showComment.visible()", textIndex)
            assertTrue(textIndex >= 0 && resetIndex > textIndex && visibleIndex > resetIndex)
        }

        val layout = readProjectFile("src/main/res/layout/item_source_import.xml")
        assertTrue(layout.contains("android:maxLines=\"3\""))
    }

    @Test
    fun `association import status labels use localized resources`() {
        val importDialogs = listOf(
            "ImportBookSourceDialog.kt",
            "ImportDictRuleDialog.kt",
            "ImportHttpTtsDialog.kt",
            "ImportReplaceRuleDialog.kt",
            "ImportRssSourceDialog.kt",
            "ImportThemeDialog.kt",
            "ImportTxtTocRuleDialog.kt",
        )

        importDialogs.forEach { fileName ->
            val source = readProjectFile(
                "src/main/java/io/legado/app/ui/association/$fileName"
            )
            assertTrue(source.contains("R.string.import_status_new"))
            assertTrue(source.contains("R.string.import_status_exist"))
            assertFalse(source.contains("\"新增\""))
            assertFalse(source.contains("\"更新\""))
            assertFalse(source.contains("\"已有\""))
        }

        importDialogs
            .filterNot { it == "ImportDictRuleDialog.kt" }
            .forEach { fileName ->
                val source = readProjectFile(
                    "src/main/java/io/legado/app/ui/association/$fileName"
                )
                assertTrue(source.contains("R.string.import_status_update"))
            }
    }

    @Test
    fun `book source replacement preview is isolated from other import dialogs`() {
        val dialog = readProjectFile(
            "src/main/java/io/legado/app/ui/association/ImportBookSourceDialog.kt"
        )
        assertTrue(dialog.contains("viewModel.setUseSourceReplacement(item.isChecked)"))
        assertTrue(dialog.contains("viewModel.originalSourceJson(position)"))
        assertTrue(dialog.contains("alternateCode = viewModel.replacedSourceJson(position)"))
        val interactionState = dialog.substringAfter("private fun updateInteractionState()")
            .substringBefore("override fun onCodeSave")
        assertTrue(interactionState.contains("menu_select_new_source)?.isEnabled = importEnabled"))
        assertTrue(interactionState.contains("menu_select_update_source)?.isEnabled = importEnabled"))

        val viewModel = readProjectFile(
            "src/main/java/io/legado/app/ui/association/ImportBookSourceViewModel.kt"
        )
        val setReplacement = viewModel.substringAfter("fun setUseSourceReplacement")
            .substringBefore("private suspend fun importSourceUrl")
        assertTrue(setReplacement.contains("useSourceReplacement = previousMode"))
        assertTrue(setReplacement.contains("AppConfig.importReplaceSource = previousMode"))
        assertTrue(setReplacement.contains("applyCandidateSources()"))
        val setSelection = viewModel.substringAfter("fun setSelection")
            .substringBefore("fun updateSource")
        assertTrue(
            setSelection.contains(
                "if (sourceUpdatePending.value == true || !canImportSource(index)) return"
            )
        )

        val codeDialog = readProjectFile(
            "src/main/java/io/legado/app/ui/widget/dialog/CodeDialog.kt"
        )
        assertTrue(codeDialog.contains("binding.codeView.keyListener = null"))
        assertTrue(codeDialog.contains("initMenu(!disableEdit)"))
        assertTrue(codeDialog.contains("saveEnabled && !show && searchView.isIconified"))
        assertTrue(codeDialog.contains("findTextRanges("))
        assertTrue(codeDialog.contains("right - left - navigationWidth"))
        assertTrue(
            codeDialog.contains(
                "binding.toolBar.contentInsetStart - binding.toolBar.contentInsetEnd"
            )
        )
        assertTrue(
            codeDialog.contains("binding.toolBar.paddingStart - binding.toolBar.paddingEnd")
        )
        assertTrue(
            codeDialog.contains("updateSearch(keepIndex = true, selectMatch = false)")
        )
        assertTrue(codeDialog.contains("if (selectMatch) showCurrentMatch()"))
        val alternatePreview = codeDialog.substringAfter("private fun showAlternate")
            .substringBefore("private fun initMenu")
        assertTrue(
            alternatePreview.contains("if (!searchView.isIconified) showCurrentMatch()")
        )
        assertTrue(
            codeDialog.contains("R.id.menu_search_previous -> moveToMatch(searchIndex - 1)")
        )
        assertTrue(
            codeDialog.contains("R.id.menu_search_next -> moveToMatch(searchIndex + 1)")
        )
        assertTrue(codeDialog.contains("codeView.setSelection(range.first, range.last + 1)"))
        assertTrue(codeDialog.contains("codeView.bringPointIntoView(range.first)"))
        assertTrue(codeDialog.contains("searchRanges.getOrNull(searchIndex) != range"))
        assertTrue(codeDialog.contains("override fun onViewStateRestored"))
        assertTrue(
            codeDialog.contains(
                "if (!searchView.isIconified) updateSearch(keepIndex = true)"
            )
        )

        val codeMenu = readProjectFile("src/main/res/menu/code_edit.xml")
        assertTrue(codeMenu.contains("@+id/menu_search"))
        assertTrue(codeMenu.contains("@+id/menu_search_previous"))
        assertTrue(codeMenu.contains("@+id/menu_search_next"))

        val rssDialog = readProjectFile(
            "src/main/java/io/legado/app/ui/association/ImportRssSourceDialog.kt"
        )
        assertTrue(rssDialog.contains("menu_replace_source)?.isVisible = false"))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
