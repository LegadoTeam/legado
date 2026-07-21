package com.script.rhino

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import org.htmlunit.corejs.javascript.CompilerEnvirons
import org.htmlunit.corejs.javascript.Context
import org.htmlunit.corejs.javascript.ContextFactory
import org.htmlunit.corejs.javascript.ErrorReporter
import org.htmlunit.corejs.javascript.Evaluator
import org.htmlunit.corejs.javascript.Function
import org.htmlunit.corejs.javascript.FunctionCompileSpec
import org.htmlunit.corejs.javascript.Parser
import org.htmlunit.corejs.javascript.Script
import org.htmlunit.corejs.javascript.TopLevel
import org.htmlunit.corejs.javascript.VarScope
import org.htmlunit.corejs.javascript.ast.AstNode
import org.htmlunit.corejs.javascript.ast.CatchClause
import org.htmlunit.corejs.javascript.ast.NodeVisitor
import org.htmlunit.corejs.javascript.ast.VariableDeclaration
import org.htmlunit.corejs.javascript.ast.WithStatement
import org.htmlunit.corejs.javascript.xml.XMLLib
import org.htmlunit.corejs.javascript.xmlimpl.XMLLoaderImpl
import java.util.function.Consumer
import kotlin.coroutines.CoroutineContext

class RhinoContext(factory: ContextFactory) : Context(factory) {

    var coroutineContext: CoroutineContext? = null
    var allowScriptRun = false
    var recursiveCount = 0

    override fun initStandardObjects(scope: TopLevel?, sealedScope: Boolean): TopLevel {
        return super.initStandardObjects(scope, sealedScope).also {
            XMLLoaderImpl().load(it, sealedScope)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getE4xImplementationFactory(): XMLLib.Factory {
        return XMLLoaderImpl().factory
    }

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
        val normalizedSource = normalizeLegacySource(
            source,
            resolvedSourceName,
            lineno,
            compilationErrorReporter ?: errorReporter,
        )
        return super.compileString(
            normalizedSource,
            compiler,
            compilationErrorReporter,
            resolvedSourceName,
            lineno,
            securityDomain,
            compatibilityProcessor(compilerEnvironsProcessor),
        )
    }

    override fun compileFunction(
        scope: VarScope,
        source: String,
        compiler: Evaluator?,
        compilationErrorReporter: ErrorReporter?,
        sourceName: String?,
        lineno: Int,
        securityDomain: Any?,
    ): Function {
        val resolvedSourceName = sourceName ?: "<Unknown source>"
        return compileFunction(
            FunctionCompileSpec.fromSource(
                normalizeLegacySource(
                    source,
                    resolvedSourceName,
                    lineno,
                    compilationErrorReporter ?: errorReporter,
                ),
                scope,
            )
                .sourceName(resolvedSourceName)
                .lineno(lineno)
                .securityDomain(securityDomain)
                .compiler(compiler)
                .compilationErrorReporter(compilationErrorReporter)
                .compilerEnvironsProcessor(compatibilityProcessor(null))
                .build()
        )
    }

    fun compileWithCompatibility(
        source: String,
        sourceName: String,
        lineNumber: Int,
    ): Script = compileString(source, sourceName, lineNumber, null)

    private fun compatibilityProcessor(
        delegate: Consumer<CompilerEnvirons>?,
    ): Consumer<CompilerEnvirons> = Consumer { environs ->
        delegate?.accept(environs)
        environs.setXmlAvailable(true)
    }

    private fun normalizeLegacySource(
        source: String,
        sourceName: String,
        lineNumber: Int,
        reporter: ErrorReporter,
    ): String {
        if (!source.contains("with") || !source.contains("const")) return source

        val environs = CompilerEnvirons().apply {
            initFromContext(this@RhinoContext)
            setXmlAvailable(true)
        }
        val positions = ArrayList<Int>()
        val visitor = object : NodeVisitor {
            override fun visit(node: AstNode): Boolean {
                if (node is CatchClause) {
                    node.varName?.visit(this)
                    node.catchCondition?.visit(this)
                    node.body.visit(this)
                    return false
                }
                if (node is WithStatement) {
                    when (val body = node.statement) {
                        is VariableDeclaration -> positions.add(body.absolutePosition)
                        else -> body.filterIsInstance<VariableDeclaration>()
                            .forEach { positions.add(it.absolutePosition) }
                    }
                }
                return true
            }
        }
        Parser(environs, reporter).parse(source, sourceName, lineNumber).visit(visitor)
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
