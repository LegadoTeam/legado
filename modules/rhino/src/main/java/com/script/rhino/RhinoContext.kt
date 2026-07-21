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
import org.htmlunit.corejs.javascript.ScriptRuntime
import org.htmlunit.corejs.javascript.ScriptableObject
import org.htmlunit.corejs.javascript.Token
import org.htmlunit.corejs.javascript.TopLevel
import org.htmlunit.corejs.javascript.VarScope
import org.htmlunit.corejs.javascript.ast.AstNode
import org.htmlunit.corejs.javascript.ast.BreakStatement
import org.htmlunit.corejs.javascript.ast.CatchClause
import org.htmlunit.corejs.javascript.ast.ContinueStatement
import org.htmlunit.corejs.javascript.ast.FunctionNode
import org.htmlunit.corejs.javascript.ast.Name
import org.htmlunit.corejs.javascript.ast.NodeVisitor
import org.htmlunit.corejs.javascript.ast.ObjectProperty
import org.htmlunit.corejs.javascript.ast.ParenthesizedExpression
import org.htmlunit.corejs.javascript.ast.PropertyGet
import org.htmlunit.corejs.javascript.ast.Scope
import org.htmlunit.corejs.javascript.ast.UnaryExpression
import org.htmlunit.corejs.javascript.ast.VariableDeclaration
import org.htmlunit.corejs.javascript.ast.VariableInitializer
import org.htmlunit.corejs.javascript.ast.WithStatement
import org.htmlunit.corejs.javascript.xml.XMLLib
import org.htmlunit.corejs.javascript.xmlimpl.XMLLoaderImpl
import java.util.function.Consumer
import kotlin.coroutines.CoroutineContext

class RhinoContext(factory: ContextFactory) : Context(factory) {

    var coroutineContext: CoroutineContext? = null
    var allowScriptRun = false
    var recursiveCount = 0
    private var compatibilityScope: VarScope? = null
    private var compatibilityScopeSpecified = false

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
            if (compatibilityScopeSpecified) compatibilityScope else currentRuntimeScope(),
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
                    scope,
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
        scope: VarScope? = null,
    ): Script {
        val previousScope = compatibilityScope
        val previousScopeSpecified = compatibilityScopeSpecified
        compatibilityScope = scope
        compatibilityScopeSpecified = true
        return try {
            compileString(source, sourceName, lineNumber, null)
        } finally {
            compatibilityScope = previousScope
            compatibilityScopeSpecified = previousScopeSpecified
        }
    }

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
        runtimeScope: VarScope?,
    ): String {
        if (!source.contains("with") || !source.contains("const")) return source

        val environs = CompilerEnvirons().apply {
            initFromContext(this@RhinoContext)
            setXmlAvailable(true)
        }
        val root = Parser(environs, reporter).parse(source, sourceName, lineNumber)
        val positions = linkedSetOf<Int>()
        val declarations = arrayListOf<LegacyConstDeclaration>()
        val references = arrayListOf<Name>()
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
                if (runtimeScope != null && node is VariableDeclaration) {
                    val position = node.absolutePosition
                    val scope = node.parent as? Scope
                    val function = node.enclosingFunction
                    val names = node.variables.mapNotNull {
                        (it.target as? Name)?.identifier
                    }.toSet()
                    if (
                        scope?.type == Token.BLOCK &&
                        function != null &&
                        names.isNotEmpty() &&
                        position >= 0 &&
                        source.regionMatches(position, "const", 0, 5)
                    ) {
                        declarations += LegacyConstDeclaration(position, scope, function, names)
                    }
                }
                if (
                    runtimeScope != null &&
                    node is Name &&
                    node.definingScope == null &&
                    node.enclosingFunction != null &&
                    node.isRequiredReference()
                ) {
                    references += node
                }
                return true
            }
        }
        root.visit(visitor)
        // 旧 Rhino 将 const 放在函数作用域；仅在块外真实读取无法解析时恢复该行为。
        references.forEach { reference ->
            if (runtimeScope != null &&
                ScriptableObject.hasProperty(runtimeScope, reference.identifier)
            ) {
                return@forEach
            }
            val matches = declarations.filter { declaration ->
                declaration.function === reference.enclosingFunction &&
                    reference.identifier in declaration.names &&
                    reference.absolutePosition >
                        declaration.scope.absolutePosition + declaration.scope.length
            }
            if (matches.size == 1) {
                positions.add(matches.single().position)
            }
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

    private fun currentRuntimeScope(): VarScope? {
        return if (ScriptRuntime.hasTopCall(this)) ScriptRuntime.getTopCallScope(this) else null
    }

    private data class LegacyConstDeclaration(
        val position: Int,
        val scope: Scope,
        val function: FunctionNode,
        val names: Set<String>,
    )

    private fun Name.isRequiredReference(): Boolean {
        var expression: AstNode = this
        var owner = parent
        while (owner is ParenthesizedExpression) {
            expression = owner
            owner = owner.parent
        }
        if (owner is UnaryExpression && owner.type == Token.TYPEOF && owner.operand === expression) {
            return false
        }
        return when (val directOwner = parent) {
            is VariableInitializer -> directOwner.target !== this
            is PropertyGet -> directOwner.property !== this
            is ObjectProperty -> directOwner.value === this
            is FunctionNode, is BreakStatement, is ContinueStatement -> false
            else -> true
        }
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
