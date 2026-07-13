package io.legado.app.model

import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReadAloudEngineNameTest {

    private val systemTtsName = "System TTS"

    @Test
    fun emptyEngineUsesSystemTtsName() {
        assertEquals(systemTtsName, resolveReadAloudEngineName(null, systemTtsName) { null })
        assertEquals(systemTtsName, resolveReadAloudEngineName("", systemTtsName) { null })
    }

    @Test
    fun httpTtsIdUsesStoredEngineName() {
        assertEquals(
            "Online engine",
            resolveReadAloudEngineName("42", systemTtsName) { id ->
                if (id == 42L) "Online engine" else null
            }
        )
    }

    @Test
    fun missingHttpTtsFallsBackToSystemTtsName() {
        assertEquals(
            systemTtsName,
            resolveReadAloudEngineName("42", systemTtsName) { null }
        )
    }

    @Test
    fun systemEngineJsonUsesItsTitle() {
        val engine = GSON.toJson(SelectItem("Device engine", "package.name"))

        assertEquals(
            "Device engine",
            resolveReadAloudEngineName(engine, systemTtsName) { null }
        )
    }

    @Test
    fun malformedOrBlankEngineTitleFallsBackToSystemTtsName() {
        val blankTitle = GSON.toJson(SelectItem("", "package.name"))

        assertEquals(
            systemTtsName,
            resolveReadAloudEngineName("not-json", systemTtsName) { null }
        )
        assertEquals(
            systemTtsName,
            resolveReadAloudEngineName(blankTitle, systemTtsName) { null }
        )
    }

    @Test
    fun overflowingNumericIdFallsBackWithoutDatabaseLookup() {
        var lookedUp = false

        assertEquals(
            systemTtsName,
            resolveReadAloudEngineName("999999999999999999999999", systemTtsName) {
                lookedUp = true
                "Unexpected"
            }
        )
        assertFalse(lookedUp)
    }
}
