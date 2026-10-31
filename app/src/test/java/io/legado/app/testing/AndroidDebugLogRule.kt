package io.legado.app.testing

import android.util.Log
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.mockito.Mockito

/** Replaces only Debug's Logcat output while each JVM test runs. */
class AndroidDebugLogRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                Mockito.mockStatic(Log::class.java, Mockito.CALLS_REAL_METHODS).use { log ->
                    log.`when`<Int> {
                        Log.d(Mockito.eq("sourceDebug"), Mockito.anyString())
                    }.thenReturn(0)
                    base.evaluate()
                }
            }
        }
}
