package io.legado.app.ui.autoTask

import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.model.prepareImportedAutoTasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AutoTaskImportMergeTest {

    @Test
    fun `import updates matching ids and appends new ids without changing order`() {
        val local = listOf(
            AutoTaskRule(id = "existing", name = "old", customOrder = 4),
            AutoTaskRule(id = "other", name = "other", customOrder = 9),
        )
        val imported = listOf(
            AutoTaskRule(id = "existing", name = "new", customOrder = 99),
            AutoTaskRule(id = "new", name = "added", customOrder = 0),
        )

        val merged = prepareImportedAutoTasks(local, imported)

        assertEquals(listOf("existing", "new"), merged.map { it.id })
        assertEquals("new", merged[0].name)
        assertEquals(4, merged[0].customOrder)
        assertEquals(10, merged[1].customOrder)
    }

    @Test
    fun `import comparison ignores local ordering and run state`() {
        val task = AutoTaskRule(
            id = "same",
            name = "task",
            customOrder = 1,
            lastRunAt = 2L,
            lastResult = "result",
            lastError = "error",
            lastLog = "log"
        )
        val imported = task.copy(
            customOrder = 99,
            lastRunAt = 0L,
            lastResult = null,
            lastError = null,
            lastLog = null
        )

        assertEquals(true, sameAutoTaskForImport(imported, task))
    }

    @Test
    fun `import preserves local ordering and run state`() {
        val local = AutoTaskRule(
            id = "existing",
            name = "old",
            customOrder = 4,
            lastRunAt = 5L,
            lastResult = null,
            lastError = "error",
            lastLog = null
        )

        val imported = prepareImportedAutoTasks(
            listOf(local),
            listOf(
                AutoTaskRule(
                    id = "existing",
                    name = "new",
                    lastResult = "imported result",
                    lastLog = "imported log"
                )
            )
        ).single()

        assertEquals("new", imported.name)
        assertEquals(4, imported.customOrder)
        assertEquals(5L, imported.lastRunAt)
        assertEquals(null, imported.lastResult)
        assertEquals("error", imported.lastError)
        assertEquals(null, imported.lastLog)
    }

    @Test
    fun `run state writes share the import mutation lock`() {
        val source = projectFile("src/main/java/io/legado/app/model/AutoTask.kt").readText()
        val update = source.substringAfter("fun updateRunState(").substringBefore("\n\n")

        assertTrue(update.contains(") = synchronized(this) {"))
    }

    @Test
    fun `duplicate imported ids keep their first position and last content`() {
        val imported = listOf(
            AutoTaskRule(id = "duplicate", name = "first"),
            AutoTaskRule(id = "duplicate", name = "last", customOrder = 99),
        )

        val prepared = prepareImportedAutoTasks(emptyList(), imported)

        assertEquals(1, prepared.size)
        assertEquals("last", prepared.single().name)
        assertEquals(0, prepared.single().customOrder)
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
