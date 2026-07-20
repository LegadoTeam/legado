package io.legado.app.ui.association

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileAssociationLoadingStateTest {

    private val source = readProjectFile(
        "src/main/java/io/legado/app/ui/association/FileAssociationActivity.kt"
    )

    @Test
    fun `loading is hidden before import dialogs and permission failure`() {
        val onlineImportObserver = source.substringAfter("viewModel.onLineImportLive.observe(this)")
            .substringBefore("viewModel.successLive.observe(this)")
        val successObserver = source.substringAfter("viewModel.successLive.observe(this)")
            .substringBefore("viewModel.errorLive.observe(this)")
        val permissionDenied = source.substringAfter("}.onDenied {")
            .substringBefore("}.request()")

        assertTrue(onlineImportObserver.contains("binding.rotateLoading.gone()"))
        assertTrue(successObserver.contains("binding.rotateLoading.gone()"))
        assertTrue(permissionDenied.contains("binding.rotateLoading.gone()"))
    }

    @Test
    fun `folder selection does not leave loading visible`() {
        val importBook = source.substringAfter("private fun importBook(uri: Uri)")
            .substringBefore("private fun importBook(treeUri: Uri?, uri: Uri)")
        val folderSelection = importBook.substringAfter("if (treeUriStr.isNullOrEmpty())")
            .substringBefore("} else {")

        assertFalse(importBook.contains("binding.rotateLoading.visible()"))
        assertTrue(folderSelection.contains("binding.rotateLoading.gone()"))
        assertTrue(
            folderSelection.indexOf("binding.rotateLoading.gone()") <
                    folderSelection.indexOf("localBookTreeSelect.launch")
        )
    }

    @Test
    fun `copy attempt owns loading lifecycle`() {
        val importBook = source.substringAfter("private fun importBook(treeUri: Uri?, uri: Uri)")
        val failureBlock = importBook.substringAfter("}.onFailure {")

        assertTrue(importBook.contains("binding.rotateLoading.visible()"))
        assertTrue(failureBlock.contains("binding.rotateLoading.gone()"))
        assertTrue(
            failureBlock.indexOf("binding.rotateLoading.gone()") <
                    failureBlock.indexOf("when (it)")
        )
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
