package io.legado.app

import com.script.ScriptBindings
import com.script.rhino.RhinoContext
import com.script.rhino.RhinoScriptEngine
import com.script.rhino.rhinoContext
import com.script.rhino.rhinoContextOrNull
import com.script.rhino.runScriptWithContext
import com.script.rhino.suspendContinuation
import io.legado.app.data.entities.BookChapter
import io.legado.app.ui.book.read.page.entities.TextChapter
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class RuntimeConcurrencyTest {

    @Test
    fun scriptContextOverloadsAlwaysUseRhinoContext() = runBlocking {
        val suspendContext = withContext(CoroutineName("suspend-script")) {
            runScriptWithContext {
                rhinoContext.javaClass to rhinoContext.coroutineContext?.get(CoroutineName)?.name
            }
        }
        assertEquals(RhinoContext::class.java, suspendContext.first)
        assertEquals("suspend-script", suspendContext.second)
        assertNull(rhinoContextOrNull)

        val explicitContext = runScriptWithContext(CoroutineName("explicit-script")) {
            rhinoContext.javaClass to rhinoContext.coroutineContext?.get(CoroutineName)?.name
        }
        assertEquals(RhinoContext::class.java, explicitContext.first)
        assertEquals("explicit-script", explicitContext.second)
        assertNull(rhinoContextOrNull)
    }

    @Test
    fun suspendedScriptsCanResumeAcrossDispatcherHandoffs() = runBlocking {
        repeat(50) {
            val bindings = ScriptBindings().apply {
                this["bridge"] = SuspendBridge()
            }
            val result = withContext(Dispatchers.Default) {
                RhinoScriptEngine.evalSuspend("bridge.hop()", bindings)
            }

            assertEquals("resumed", result)
            assertNull(rhinoContextOrNull)
        }
    }

    @Test
    fun layoutPageStorageSupportsConcurrentAppendAndIteration() {
        val chapter = TextChapter(
            chapter = BookChapter(),
            position = 0,
            title = "test",
            chaptersSize = 1,
            sameTitleRemoved = false,
            isVip = false,
            isPay = false,
            effectiveReplaceRules = null,
        )
        val field = TextChapter::class.java.getDeclaredField("textPages").apply {
            isAccessible = true
        }
        val storage = field.get(chapter)
        assertTrue(storage is CopyOnWriteArrayList<*>)

        @Suppress("UNCHECKED_CAST")
        val pages = storage as CopyOnWriteArrayList<Any>
        val writer = thread(start = true) {
            repeat(2_000) { pages.add(it) }
        }
        while (writer.isAlive) {
            pages.forEach { assertTrue(it is Int) }
            pages.getOrNull(pages.lastIndex)
        }
        writer.join()
        assertEquals(2_000, pages.size)
    }

    class SuspendBridge {
        fun hop(): String = suspendContinuation {
            repeat(4) {
                withContext(Dispatchers.IO) {
                    yield()
                }
                withContext(Dispatchers.Default) {
                    yield()
                }
            }
            "resumed"
        }
    }
}
