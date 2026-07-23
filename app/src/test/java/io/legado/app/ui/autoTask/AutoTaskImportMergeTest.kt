package io.legado.app.ui.autoTask

import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.model.prepareImportedAutoTasks
import org.junit.Assert.assertEquals
import org.junit.Test

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
    fun `import comparison ignores local ordering`() {
        val task = AutoTaskRule(id = "same", name = "task", customOrder = 1)
        val imported = task.copy(customOrder = 99)

        assertEquals(true, sameAutoTaskForImport(imported, task))
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
}
