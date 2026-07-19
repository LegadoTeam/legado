package io.legado.app.ui.association

import io.legado.app.help.config.ReadBookConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ReadConfigImportTest {

    @Test
    fun `empty list receives the imported config and saves`() {
        val configs = arrayListOf<ReadBookConfig.Config>()
        val imported = config("custom", 24)
        var defaultsCount = 0
        var saveCount = 0

        val name = applyImportedReadConfig(
            configs = configs,
            imported = imported,
            defaultConfigs = {
                defaultsCount++
                defaultConfigs()
            },
            save = { saveCount++ },
        )

        assertEquals("custom", name)
        assertEquals(
            listOf("default-a", "default-b", "default-c", "default-d", "default-e", "custom"),
            configs.map { it.name },
        )
        assertSame(imported, configs.last())
        assertEquals(1, defaultsCount)
        assertEquals(1, saveCount)
    }

    @Test
    fun `new name appends once without reordering existing configs`() {
        val configs = defaultConfigs()
        val imported = config("custom", 24)

        apply(configs, imported)
        apply(configs, imported)

        assertEquals(
            listOf("default-a", "default-b", "default-c", "default-d", "default-e", "custom"),
            configs.map { it.name },
        )
        assertSame(imported, configs.last())
    }

    @Test
    fun `matching first middle and last items are replaced in place`() {
        for (targetIndex in listOf(0, 2, 4)) {
            val configs = defaultConfigs()
            val imported = config(configs[targetIndex].name, 30 + targetIndex)

            apply(configs, imported)

            assertEquals(defaultNames, configs.map { it.name })
            assertEquals(5, configs.size)
            assertSame(imported, configs[targetIndex])
        }
    }

    @Test
    fun `preexisting duplicates stay in place without adding another item`() {
        val configs = arrayListOf(
            config("default-a"),
            config("custom", 18),
            config("default-b"),
            config("custom", 19),
            config("default-c"),
            config("default-d"),
        )
        val imported = config("custom", 30)

        apply(configs, imported)

        assertEquals(
            listOf("default-a", "custom", "default-b", "custom", "default-c", "default-d"),
            configs.map { it.name },
        )
        assertSame(imported, configs[1])
        assertEquals(6, configs.size)
    }

    @Test
    fun `persistence failure is reported before import succeeds`() {
        val error = IllegalStateException("save failed")
        val configs = defaultConfigs()
        val previousConfigs = configs.toList()

        val thrown = assertThrows(IllegalStateException::class.java) {
            applyImportedReadConfig(
                configs = configs,
                imported = config("custom"),
                defaultConfigs = ::defaultConfigs,
                save = { throw error },
            )
        }

        assertSame(error, thrown)
        assertEquals(defaultNames, configs.map { it.name })
        previousConfigs.forEachIndexed { index, config ->
            assertSame(config, configs[index])
        }
    }

    @Test
    fun `persistence failure rolls back restored defaults`() {
        val existing = config("legacy")
        val configs = arrayListOf(existing)

        assertThrows(IllegalStateException::class.java) {
            applyImportedReadConfig(
                configs = configs,
                imported = config("custom"),
                defaultConfigs = ::defaultConfigs,
                save = { error("save failed") },
            )
        }

        assertEquals(1, configs.size)
        assertSame(existing, configs.single())
    }

    @Test
    fun `default loading failure restores the original list`() {
        val existing = config("legacy")
        val configs = arrayListOf(existing)
        val error = IllegalStateException("defaults failed")

        val thrown = assertThrows(IllegalStateException::class.java) {
            applyImportedReadConfig(
                configs = configs,
                imported = config("custom"),
                defaultConfigs = { throw error },
                save = {},
            )
        }

        assertSame(error, thrown)
        assertEquals(1, configs.size)
        assertSame(existing, configs.single())
    }

    @Test
    fun `concurrent imports cannot let a failed rollback erase a successful import`() {
        val configs = defaultConfigs()
        val firstSaveStarted = CountDownLatch(1)
        val releaseFirstSave = CountDownLatch(1)
        val secondImportStarted = CountDownLatch(1)
        val secondSaveFinished = CountDownLatch(1)
        val firstFailure = AtomicReference<Throwable?>()
        val secondFailure = AtomicReference<Throwable?>()
        val expectedFailure = IllegalStateException("first save failed")
        val transactionLock = Any()

        val firstThread = thread(name = "read-config-import-first") {
            try {
                applyImportedReadConfig(
                    configs = configs,
                    imported = config("first"),
                    defaultConfigs = ::defaultConfigs,
                    save = {
                        firstSaveStarted.countDown()
                        check(releaseFirstSave.await(5, TimeUnit.SECONDS))
                        throw expectedFailure
                    },
                    transactionLock = transactionLock,
                )
            } catch (error: Throwable) {
                firstFailure.set(error)
            }
        }
        assertTrue(firstSaveStarted.await(5, TimeUnit.SECONDS))

        val secondThread = thread(name = "read-config-import-second") {
            secondImportStarted.countDown()
            try {
                applyImportedReadConfig(
                    configs = configs,
                    imported = config("second"),
                    defaultConfigs = ::defaultConfigs,
                    save = { secondSaveFinished.countDown() },
                    transactionLock = transactionLock,
                )
            } catch (error: Throwable) {
                secondFailure.set(error)
            }
        }
        assertTrue(secondImportStarted.await(5, TimeUnit.SECONDS))
        assertFalse(secondSaveFinished.await(200, TimeUnit.MILLISECONDS))

        releaseFirstSave.countDown()
        firstThread.join(5_000)
        secondThread.join(5_000)

        assertFalse(firstThread.isAlive)
        assertFalse(secondThread.isAlive)
        assertSame(expectedFailure, firstFailure.get())
        assertNull(secondFailure.get())
        assertEquals(defaultNames + "second", configs.map { it.name })
        assertTrue(secondSaveFinished.await(0, TimeUnit.MILLISECONDS))
    }

    private fun apply(
        configs: MutableList<ReadBookConfig.Config>,
        imported: ReadBookConfig.Config,
    ) = applyImportedReadConfig(
        configs = configs,
        imported = imported,
        defaultConfigs = ::defaultConfigs,
        save = {},
    )

    private fun defaultConfigs() = defaultNames.mapTo(arrayListOf()) { config(it) }

    private fun config(name: String, textSize: Int = 20) =
        ReadBookConfig.Config(name = name, textSize = textSize)

    private val defaultNames =
        listOf("default-a", "default-b", "default-c", "default-d", "default-e")
}
