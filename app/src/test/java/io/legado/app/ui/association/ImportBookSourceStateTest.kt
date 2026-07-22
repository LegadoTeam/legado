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

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
