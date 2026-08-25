package io.legado.app.ui.association

import io.legado.app.ui.widget.dialog.resolveCodeDialogOriginal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImportBookSourceStateTest {

    @Test
    fun `replace manager refresh keeps the editable source draft`() {
        assertEquals(
            "edited original",
            resolveCodeDialogOriginal(true, "edited original", "replacement preview"),
        )
        assertEquals(
            "visible edit",
            resolveCodeDialogOriginal(false, "older original", "visible edit"),
        )
    }

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
        assertTrue(dialog.contains("showReplaceRules = true"))
        assertTrue(dialog.contains("override fun onOpenReplaceRules"))
        assertTrue(dialog.contains("ReplaceRuleActivity::class.java"))
        assertTrue(dialog.contains("dialog.currentOriginalCode() to dialog.requestId"))
        assertTrue(dialog.contains("viewModel.refreshSourceReplacements(index, source)"))
        assertTrue(dialog.contains("pendingReplacementRefresh"))
        assertTrue(dialog.contains("startPendingReplacementRefresh()"))
        assertTrue(
            dialog.contains(
                "if (!startPendingReplacementRefresh() && pendingReplacementRefresh == null)"
            )
        )
        assertTrue(dialog.contains("dialog.clearAlternateCode()"))
        assertTrue(dialog.contains("override fun isReplaceRuleRefreshPending"))
        assertTrue(dialog.contains("parseBookSourceJson(code, allowSourceUrls = false)"))
        assertTrue(dialog.contains("viewModel.replacedSourceJson(index)"))
        assertTrue(dialog.contains("refreshAlternateCode()"))
        val syncOpenCodeDialog = dialog.substringAfter("private fun syncOpenCodeDialog()")
            .substringBefore("private fun parseDraftSource")
        assertTrue(syncOpenCodeDialog.contains("dialog.setReplaceRuleRefreshPending(false)"))
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
        assertFalse(codeDialog.contains("ReplaceRuleActivity"))
        assertTrue(codeDialog.contains("callback()?.onOpenReplaceRules()"))
        assertTrue(codeDialog.contains("fun setReplaceRuleRefreshPending"))
        assertTrue(codeDialog.contains("if (!replaceRuleRefreshPending)"))
        assertTrue(codeDialog.contains("binding.codeView.keyListener = if (pending"))
        assertTrue(codeDialog.contains("isCancelable = !pending"))
        assertTrue(codeDialog.contains("fun clearAlternateCode()"))
        assertTrue(codeDialog.contains("callback()?.isReplaceRuleRefreshPending()"))
        val replaceMenu = codeDialog.substringAfter("R.id.menu_replace_rule ->")
            .substringBefore("R.id.menu_save ->")
        assertTrue(replaceMenu.contains("onOpenReplaceRules"))
        assertFalse(replaceMenu.contains("dismiss"))
        assertTrue(codeDialog.contains("fun refreshAlternateCode()"))
        assertTrue(codeDialog.contains("callback()?.getCodeAlternate(requestId)"))
        assertTrue(codeDialog.contains("!replaceRuleRefreshPending"))
        assertTrue(
            codeDialog.indexOf("setOnCheckedChangeListener") >
                codeDialog.indexOf("val canPreviewReplacement")
        )
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
        assertTrue(codeDialog.contains("savedInstanceState?.getString(\"originalCode\")"))
        assertTrue(codeDialog.contains("saveStateData(originalCodeStateKey, currentOriginalCode())"))
        assertTrue(codeDialog.contains("key?.also { IntentData.put(it, data) }"))
        assertTrue(codeDialog.contains("originalCodeStateKey?.let { IntentData.get<Any>(it) }"))
        assertTrue(codeDialog.contains("outState.putBoolean(\"showingAlternate\""))
        assertTrue(
            codeDialog.contains(
                "if (!searchView.isIconified) updateSearch(keepIndex = true)"
            )
        )

        val codeMenu = readProjectFile("src/main/res/menu/code_edit.xml")
        assertTrue(codeMenu.contains("@+id/menu_search"))
        assertTrue(codeMenu.contains("@+id/menu_search_previous"))
        assertTrue(codeMenu.contains("@+id/menu_search_next"))
        assertTrue(codeMenu.contains("@+id/menu_replace_rule"))

        val rssDialog = readProjectFile(
            "src/main/java/io/legado/app/ui/association/ImportRssSourceDialog.kt"
        )
        assertTrue(rssDialog.contains("viewModel.setUseSourceReplacement(item.isChecked)"))
        assertTrue(rssDialog.contains("viewModel.originalSourceJson(position)"))
        assertTrue(rssDialog.contains("alternateCode = viewModel.replacedSourceJson(position)"))
        assertTrue(rssDialog.contains("showReplaceRules = true"))
        assertTrue(rssDialog.contains("override fun onOpenReplaceRules"))
        assertTrue(rssDialog.contains("pendingReplacementRefresh"))
        assertTrue(rssDialog.contains("viewModel.refreshSourceReplacements(index, source)"))
        assertFalse(rssDialog.contains("menu_replace_source)?.isVisible = false"))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
