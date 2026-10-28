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
                    functionSource = true,
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
        functionSource: Boolean = false,
    ): String {
        val hasConst = source.contains("const")
        val hasLet = source.contains("let")
        if (!hasConst && !hasLet) return source

        val environs = CompilerEnvirons().apply {
            initFromContext(this@RhinoContext)
            setXmlAvailable(true)
        }
        val root = Parser(environs, reporter).parse(source, sourceName, lineNumber)
        val outerFunction = if (functionSource) root.firstChild as? FunctionNode else null
        val replacements = linkedMapOf<Int, String>()
        val declarations = arrayListOf<LegacyBlockDeclaration>()
        val references = arrayListOf<Name>()
        val hasLegacyWithConst = hasConst && source.contains("with")
        val visitor = object : NodeVisitor {
            override fun visit(node: AstNode): Boolean {
                if (node is CatchClause) {
                    node.varName?.visit(this)
                    node.catchCondition?.visit(this)
                    node.body.visit(this)
                    return false
                }
                if (hasLegacyWithConst && node is WithStatement) {
                    when (val body = node.statement) {
                        is VariableDeclaration -> body.addConstReplacement(replacements, source)
                        else -> body.filterIsInstance<VariableDeclaration>()
                            .forEach { it.addConstReplacement(replacements, source) }
                    }
                }
                if (node is VariableDeclaration) {
                    val scope = node.parent as? Scope
                    val function = node.enclosingFunction
                    val names = node.variables.mapNotNull {
                        (it.target as? Name)?.identifier
                    }.toSet()
                    val replacement = node.declarationReplacement(source)
                    val inOuterScope = if (functionSource) {
                        function === outerFunction
                    } else {
                        function == null
                    }
                    val inLegacyFunction = runtimeScope != null &&
                        hasLegacyWithConst &&
                        function != null &&
                        replacement?.second == "var  "
                    if (
                        scope?.type == Token.BLOCK &&
                        (inOuterScope || inLegacyFunction) &&
                        names.isNotEmpty() &&
                        replacement != null
                    ) {
                        declarations += LegacyBlockDeclaration(
                            replacement.first,
                            replacement.second,
                            scope,
                            function,
                            names,
                        )
                    }
                }
                if (
                    node is Name &&
                    node.definingScope == null &&
                    node.isRequiredReference()
                ) {
                    val function = node.enclosingFunction
                    val inOuterScope = if (functionSource) {
                        function === outerFunction
                    } else {
                        function == null
                    }
                    if (
                        inOuterScope ||
                        runtimeScope != null && hasLegacyWithConst && function != null
                    ) {
                        references += node
                    }
                }
                return true
            }
        }
        root.visit(visitor)
        // 旧书源会在块外读取 let/const；仅在同一执行层的未解析真实读取时恢复可见性。
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
                val declaration = matches.single()
                replacements[declaration.position] = declaration.replacement
            }
        }
        if (replacements.isEmpty()) return source

        val result = source.toCharArray()
        replacements.forEach { (position, replacement) ->
            replacement.toCharArray(result, position)
        }
        return result.concatToString()
    }

    private fun currentRuntimeScope(): VarScope? {
        return if (ScriptRuntime.hasTopCall(this)) ScriptRuntime.getTopCallScope(this) else null
    }

    private data class LegacyBlockDeclaration(
        val position: Int,
        val replacement: String,
        val scope: Scope,
        val function: FunctionNode?,
        val names: Set<String>,
    )

    private fun VariableDeclaration.addConstReplacement(
        replacements: MutableMap<Int, String>,
        source: String,
    ) {
        val position = absolutePosition
        if (
            position >= 0 &&
            source.regionMatches(position, "const", 0, 5)
        ) {
            replacements[position] = "var  "
        }
    }

    private fun VariableDeclaration.declarationReplacement(
        source: String,
    ): Pair<Int, String>? {
        val position = absolutePosition
        return when {
            position >= 0 && source.regionMatches(position, "const", 0, 5) -> {
                position to "var  "
            }

            type == Token.LET -> {
                if (position >= 0 && source.regionMatches(position, "let", 0, 3)) {
                    return position to "var"
                }
                var keywordEnd = position - 1
                while (keywordEnd >= 0 && source[keywordEnd].isWhitespace()) {
                    keywordEnd--
                }
                val keywordPosition = keywordEnd - 2
                if (
                    keywordPosition >= 0 &&
                    source.regionMatches(keywordPosition, "let", 0, 3)
                ) {
                    keywordPosition to "var"
                } else {
                    null
                }
            }

            else -> null
        }
    }

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
