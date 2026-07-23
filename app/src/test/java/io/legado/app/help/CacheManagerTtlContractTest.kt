package io.legado.app.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CacheManagerTtlContractTest {

    private val source by lazy {
        projectFile("src/main/java/io/legado/app/help/CacheManager.kt")
            .readText()
            .replace("\r\n", "\n")
    }

    @Test
    fun `timed strings invalidate memory after database write`() {
        val put = section("fun put(key: String, value: Any", "fun putMemory")

        assertTrue(put.contains("saveTime * 1000L"))
        assertTrue(
            Regex(
                """appDb\.cacheDao\.insert\(cache\)\s*""" +
                    """if \(deadline == 0L\) \{\s*""" +
                    """putMemory\(key, valueStr\)\s*""" +
                    """} else \{\s*deleteMemory\(key\)\s*}"""
            ).containsMatchIn(put)
        )
        assertEquals(1, Regex("""putMemory\(key, valueStr\)""").findAll(put).count())
        assertEquals(1, Regex("""deleteMemory\(key\)""").findAll(put).count())
    }

    @Test
    fun `only permanent disk strings are promoted to memory`() {
        val get = section("fun get(key: String): String?", "fun get(key: String, onlyDisk")

        assertTrue(
            Regex(
                """return cache\.value\?\.also \{\s*""" +
                    """if \(cache\.deadline == 0L\) \{\s*""" +
                    """putMemory\(key, it\)\s*}\s*}"""
            ).containsMatchIn(get)
        )
        assertEquals(1, Regex("""putMemory\(key, it\)""").findAll(get).count())
    }

    @Test
    fun `disk only reads do not populate memory`() {
        val onlyDisk = section("fun get(key: String, onlyDisk", "fun getInt")

        assertFalse(onlyDisk.contains("putMemory("))
    }

    @Test
    fun `persistent operations share the cache manager monitor`() {
        val cacheManager = section("object CacheManager {", "object WebCacheManager")

        assertTrue(
            cacheManager.contains(
                "@JvmOverloads\n    @Synchronized\n" +
                    "    fun put(key: String, value: Any, saveTime: Int = 0)"
            )
        )
        assertTrue(cacheManager.contains("@Synchronized\n    fun get(key: String): String?"))
        assertTrue(
            cacheManager.contains(
                "@Synchronized\n    fun get(key: String, onlyDisk: Boolean): String?"
            )
        )
        assertTrue(cacheManager.contains("@Synchronized\n    fun delete(key: String)"))
    }

    private fun section(startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, start)
        require(start >= 0 && end > start)
        return source.substring(start, end)
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
