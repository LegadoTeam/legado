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
        val folderSelection = source.substringAfter("private fun chooseBookDirectory()")

        assertFalse(folderSelection.contains("binding.rotateLoading.visible()"))
        assertTrue(folderSelection.contains("binding.rotateLoading.gone()"))
        assertTrue(
            folderSelection.indexOf("binding.rotateLoading.gone()") <
                    folderSelection.indexOf("localBookTreeSelect.launch")
        )
    }

    @Test
    fun `copy attempt owns loading lifecycle`() {
        val observer = source.substringAfter("viewModel.importingLocalBooks.observe(this)")
            .substringBefore("viewModel.importedLocalBooks.observe(this)")
        val copy = readProjectFile("src/main/java/io/legado/app/ui/association/FileAssociationViewModel.kt")
            .substringAfter("fun importLocalBooks(directory: Uri)")
            .substringBefore("private fun reportSharedImportError")
        assertTrue(observer.contains("if (importing) binding.rotateLoading.visible() else binding.rotateLoading.gone()"))
        assertTrue(copy.contains("importingLocalBooks.value = true"))
        assertTrue(copy.contains(".onFinally { importingLocalBooks.value = false }"))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
