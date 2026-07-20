package com.script.rhino

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import org.htmlunit.corejs.javascript.CompilerEnvirons
import org.htmlunit.corejs.javascript.Context
import org.htmlunit.corejs.javascript.ContextFactory
import org.htmlunit.corejs.javascript.ErrorReporter
import org.htmlunit.corejs.javascript.Evaluator
import org.htmlunit.corejs.javascript.Parser
import org.htmlunit.corejs.javascript.Script
import org.htmlunit.corejs.javascript.ast.VariableDeclaration
import org.htmlunit.corejs.javascript.ast.WithStatement
import java.util.function.Consumer
import kotlin.coroutines.CoroutineContext

class RhinoContext(factory: ContextFactory) : Context(factory) {

    var coroutineContext: CoroutineContext? = null
    var allowScriptRun = false
    var recursiveCount = 0

    override fun compileString(
        source: String,
        compiler: Evaluator?,
        compilationErrorReporter: ErrorReporter?,
        sourceName: String?,
        lineno: Int,
        securityDomain: Any?,
        compilerEnvironsProcessor: Consumer<CompilerEnvirons>?,
    ): Script {
        val resolvedSourceName = sourceName ?: "<Unknown source>"
        val normalizedSource = normalizeLegacySource(source, resolvedSourceName, lineno)
        val compatibilityProcessor = Consumer<CompilerEnvirons> { environs ->
            compilerEnvironsProcessor?.accept(environs)
            environs.setXmlAvailable(true)
        }
        return super.compileString(
            normalizedSource,
            compiler,
            compilationErrorReporter,
            resolvedSourceName,
            lineno,
            securityDomain,
            compatibilityProcessor,
        )
    }

    private fun normalizeLegacySource(
        source: String,
        sourceName: String,
        lineNumber: Int,
    ): String {
        if (!source.contains("with") || !source.contains("const")) return source

        val environs = CompilerEnvirons().apply {
            initFromContext(this@RhinoContext)
            setXmlAvailable(true)
        }
        val positions = ArrayList<Int>()
        Parser(environs, errorReporter).parse(source, sourceName, lineNumber).visit { node ->
            if (node is WithStatement) {
                when (val body = node.statement) {
                    is VariableDeclaration -> positions.add(body.absolutePosition)
                    else -> body.filterIsInstance<VariableDeclaration>()
                        .forEach { positions.add(it.absolutePosition) }
                }
            }
            true
        }
        if (positions.isEmpty()) return source

        val result = source.toCharArray()
        positions.forEach { position ->
            if (position >= 0 && source.regionMatches(position, "const", 0, 5)) {
                "var  ".toCharArray(result, position)
            }
        }
        return result.concatToString()
    }

    @Throws(RhinoInterruptError::class)
    fun ensureActive() {
        try {
            coroutineContext?.ensureActive()
        } catch (e: CancellationException) {
            throw RhinoInterruptError(e)
        }
    }

    @Throws(RhinoRecursionError::class)
    fun checkRecursive() {
        if (recursiveCount >= 10) {
            throw RhinoRecursionError()
        }
    }

}
