package io.legado.app.ui.autoTask

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AutoTaskImportContractTest {

    @Test
    fun `automatic task import is routed from files and links`() {
        val base = projectFile(
            "src/main/java/io/legado/app/ui/association/BaseAssociationViewModel.kt"
        )
        val online = projectFile(
            "src/main/java/io/legado/app/ui/association/OnLineImportActivity.kt"
        )
        val file = projectFile(
            "src/main/java/io/legado/app/ui/association/FileAssociationActivity.kt"
        )
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/autoTask/AutoTaskActivity.kt"
        )

        assertTrue(base.contains("map.containsKey(\"cron\") && map.containsKey(\"script\")"))
        assertTrue(base.contains("successLive.postValue(\"autoTask\" to uri.toString())"))
        assertTrue(online.contains("\"/autoTask\" -> showDialogFragment("))
        assertTrue(online.contains("\"/auto\" -> viewModel.determineType("))
        assertTrue(online.contains("\"autoTask\" -> showDialogFragment("))
        assertTrue(file.contains("\"autoTask\" -> showDialogFragment("))
        assertTrue(activity.contains("R.id.menu_import_local"))
        assertTrue(activity.contains("R.id.menu_import_on_line"))
    }

    @Test
    fun `automatic task share passphrase routes import and export`() {
        val main = projectFile("src/main/java/io/legado/app/ui/main/MainActivity.kt")
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/autoTask/AutoTaskActivity.kt"
        )
        val exportBlock = activity.substringAfter("private val exportDoc")
            .substringBefore("override fun onActivityCreated")

        assertTrue(main.contains("SourceSharePassphrase.Type.AUTO_TASK ->"))
        assertTrue(main.contains("showDialogFragment(ImportAutoTaskDialog(value.url))"))
        assertTrue(exportBlock.contains("sourceSharePassphraseButton("))
        assertTrue(exportBlock.contains("SourceSharePassphrase.Type.AUTO_TASK"))
        assertTrue(activity.contains("inflateMenu(R.menu.auto_task_sel)"))
        assertTrue(activity.contains("val rules = adapter.selection"))
        assertTrue(activity.contains("AutoTask.exportJson(rules)"))
    }

    @Test
    fun `automatic task import batches storage and scheduler refresh`() {
        val model = projectFile("src/main/java/io/legado/app/model/AutoTask.kt")
        val block = model.substringAfter("fun importRules(")
            .substringBefore("fun delete(")

        assertTrue(block.contains("if (rules.isEmpty()) return emptyList()"))
        assertTrue(block.contains("prepareImportedAutoTasks(all(), rules)"))
        assertTrue(block.contains("appDb.autoTaskRuleDao.upsert(*imported.toTypedArray())"))
        assertTrue(block.contains("AutoTaskScheduler.refresh(context)"))
    }

    @Test
    fun `automatic task import is single shot and validates editable fields`() {
        val viewModel = projectFile(
            "src/main/java/io/legado/app/ui/autoTask/ImportAutoTaskViewModel.kt"
        )

        assertTrue(viewModel.contains("if (importStarted) return"))
        assertTrue(viewModel.contains("importStarted = false"))
        assertTrue(viewModel.contains("validateImportedTask"))
        assertTrue(viewModel.contains("AutoTask.DEFAULT_CRON"))
        assertTrue(viewModel.contains("CronSchedule.parse(normalized.cron.orEmpty())"))
        assertTrue(viewModel.contains("AutoTask.normalizeScript(normalized.script).isBlank()"))

        val importBlock = viewModel.substringAfter("fun importSelect(")
            .substringBefore("fun importSource(")
        val editBlock = viewModel.substringAfter("fun updateTaskAt(")
            .substringBefore("private suspend fun importSourceAwait(")
        assertTrue(importBlock.contains(".map(::validateImportedTask)"))
        assertTrue(editBlock.contains("validateImportedTask(task)"))
    }

    @Test
    fun `automatic task import keeps the dialog title readable`() {
        val dialog = projectFile(
            "src/main/java/io/legado/app/ui/autoTask/ImportAutoTaskDialog.kt"
        )
        val setup = dialog.substringAfter("override fun onFragmentCreated")
            .substringBefore("binding.rotateLoading.visible()")

        assertTrue(setup.contains("binding.toolBar.setBackgroundColor(primaryColor)"))
        assertTrue(setup.indexOf("setBackgroundColor") < setup.indexOf("setTitle"))
    }

    private fun projectFile(pathInApp: String): String {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("Missing project file: $pathInApp")
    }
}
