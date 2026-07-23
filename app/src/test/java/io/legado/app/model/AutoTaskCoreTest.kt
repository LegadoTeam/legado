package io.legado.app.model

import com.script.rhino.RhinoInterruptError
import com.google.gson.annotations.SerializedName
import io.legado.app.data.entities.AutoTaskRule
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTaskCoreTest {

    @Test
    fun normalizesSupportedScriptWrappers() {
        assertEquals("return 1", AutoTask.normalizeScript(" @js: return 1 "))
        assertEquals("return 2", AutoTask.normalizeScript("<js> return 2 </js>"))
        assertEquals("return 3", AutoTask.normalizeScript(" return 3 "))
    }

    @Test
    fun parsesProtocolArrayObjectAndWrapper() {
        assertEquals(1, AutoTaskProtocol.parseActions("{\"type\":\"notify\"}")?.size)
        assertEquals(2, AutoTaskProtocol.parseActions("[{\"type\":\"notify\"},{\"type\":\"refreshToc\"}]")?.size)
        assertEquals(1, AutoTaskProtocol.parseActions("{\"actions\":[{\"type\":\"notify\"}]}")?.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownProtocolAction() {
        AutoTaskProtocol.actionType(mapOf("type" to "unknown"))
    }

    @Test
    fun boundsStoredLogLength() {
        assertEquals(AutoTaskLogFormatter.MAX_LENGTH, AutoTaskLogFormatter.trim("x".repeat(8_000)).length)
        assertEquals(
            AutoTaskLogFormatter.MAX_ERROR_LENGTH,
            AutoTaskLogFormatter.trimError("x".repeat(8_000)).length
        )
    }

    @Test
    fun cancellationIsNeverConvertedToFailure() {
        val cancellation = CancellationException("stop")
        assertEquals(cancellation, cancellation.autoTaskCancellation())
    }

    @Test
    fun ordinaryFailureIsNotCancellation() {
        assertNull(IllegalStateException("failed").autoTaskCancellation())
    }

    @Test
    fun rhinoWrappedCancellationIsNeverConvertedToFailure() {
        val cancellation = CancellationException("stop Rhino")
        assertEquals(
            cancellation,
            RhinoInterruptError(cancellation).autoTaskCancellation()
        )
    }

    @Test
    fun legacyRulesMigrateOnlyOnce() {
        var legacyJson: String? = "[{\"id\":\"one\",\"name\":\"Task\",\"script\":\"1\"}]"
        var persistCount = 0
        var clearCount = 0
        val migrate = {
            migrateLegacyAutoTaskRules(
                read = { legacyJson },
                persist = { persistCount++ },
                clear = {
                    clearCount++
                    legacyJson = null
                }
            )
        }

        assertEquals(1, migrate().size)
        assertEquals(0, migrate().size)
        assertEquals(1, persistCount)
        assertEquals(1, clearCount)
    }

    @Test
    fun autoTaskJsonFieldsHaveStableSerializedNames() {
        val expected = setOf(
            "id",
            "name",
            "enable",
            "cron",
            "loginUrl",
            "loginUi",
            "loginCheckJs",
            "comment",
            "script",
            "header",
            "jsLib",
            "concurrentRate",
            "enabledCookieJar",
            "customOrder",
            "lastRunAt",
            "lastResult",
            "lastError",
            "lastLog"
        )
        val serializedNames = AutoTaskRule::class.java.declaredFields.mapNotNull { field ->
            field.getAnnotation(SerializedName::class.java)?.value
        }.toSet()

        assertEquals(expected, serializedNames)
    }

    @Test
    fun existingRoomRulesClearLegacyCacheOnceAndNeverReviveIt() {
        val loader = LegacyAutoTaskRulesLoader()
        val existing = listOf(AutoTaskRule(id = "current", name = "Current", script = "1"))
        var legacyJson: String? = "[{\"id\":\"old\",\"name\":\"Old\",\"script\":\"1\"}]"
        var persistCount = 0
        var clearCount = 0
        val load: (List<AutoTaskRule>) -> List<AutoTaskRule> = { roomRules ->
            loader.load(
                existingRules = roomRules,
                read = { legacyJson },
                persist = { persistCount++ },
                clear = {
                    clearCount++
                    legacyJson = null
                }
            )
        }

        assertEquals(existing, load(existing))
        assertEquals(emptyList<AutoTaskRule>(), load(emptyList()))
        assertEquals(0, persistCount)
        assertEquals(1, clearCount)
    }

    @Test
    fun failedLegacyCleanupCanRetry() {
        val loader = LegacyAutoTaskRulesLoader()
        val existing = listOf(AutoTaskRule(id = "current", name = "Current", script = "1"))
        var clearAttempts = 0

        runCatching {
            loader.load(existing, read = { null }, persist = {}, clear = {
                clearAttempts++
                error("cleanup failed")
            })
        }
        val result = loader.load(existing, read = { null }, persist = {}, clear = {
            clearAttempts++
        })

        assertEquals(existing, result)
        assertEquals(2, clearAttempts)
    }

    @Test
    fun notificationIdRangesNeverOverlap() {
        val taskIds = listOf(Int.MIN_VALUE, -1, 0, 9_999, Int.MAX_VALUE).map {
            AutoTaskProtocol.taskNotificationId(it, "ignored")
        } + AutoTaskProtocol.taskNotificationId(null, "task")
        val bookIds = listOf("", "book", "another").map {
            AutoTaskProtocol.bookUpdateNotificationId(it)
        }

        assertTrue(taskIds.all { it in 30_000..39_999 })
        assertTrue(bookIds.all { it in 50_000..59_999 })
        assertTrue(taskIds.toSet().intersect(bookIds.toSet()).isEmpty())
    }

    @Test
    fun notificationTextIsBoundedBeforePosting() {
        assertEquals(
            AutoTaskProtocol.MAX_NOTIFICATION_TITLE_LENGTH,
            AutoTaskProtocol.trimNotificationTitle("x".repeat(1_000)).length
        )
        assertEquals(
            AutoTaskProtocol.MAX_NOTIFICATION_CONTENT_LENGTH,
            AutoTaskProtocol.trimNotificationContent("x".repeat(8_000)).length
        )
    }
}
