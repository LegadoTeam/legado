package io.legado.app.ui.replace.edit

import com.script.ScriptBindings
import com.script.rhino.RhinoInterruptError
import com.script.rhino.RhinoScriptEngine
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.RegexJsExtensions
import io.legado.app.help.config.ReplacePreviewConfig
import io.legado.app.utils.quoteReplacementJs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

object ReplacePreview {

    const val MAX_SAMPLE_LENGTH = ReplacePreviewConfig.MAX_SAMPLE_LENGTH

    fun normalizeSample(sample: String): String {
        return ReplacePreviewConfig.normalizeSample(sample)
    }

    suspend fun apply(rule: ReplaceRule, sample: String): String {
        return apply(rule, sample, System::nanoTime)
    }

    internal suspend fun apply(
        rule: ReplaceRule,
        sample: String,
        nanoTime: () -> Long
    ): String {
        if (sample.isEmpty() || rule.pattern.isEmpty()) return sample
        if (!rule.isRegex) return sample.replace(rule.pattern, rule.replacement)
        return try {
            withTimeout(rule.getValidTimeoutMillisecond()) {
                withContext(Dispatchers.Default) {
                    applyRegex(rule, sample, nanoTime)
                }
            }
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw ReplacePreviewException(ReplacePreviewException.Reason.TIMEOUT)
        } catch (_: PreviewDeadlineException) {
            throw ReplacePreviewException(ReplacePreviewException.Reason.TIMEOUT)
        }
    }

    private suspend fun applyRegex(
        rule: ReplaceRule,
        sample: String,
        nanoTime: () -> Long
    ): String {
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(rule.getValidTimeoutMillisecond())
        val startedAt = nanoTime()
        val coroutineContext = currentCoroutineContext()
        val input = DeadlineCharSequence(
            sample,
            startedAt,
            timeoutNanos,
            nanoTime,
            shouldContinue = { coroutineContext[Job]?.isActive != false }
        )
        val matcher = rule.pattern.toRegex().toPattern().matcher(input)
        val output = StringBuffer()
        val isJs = rule.replacement.startsWith("@js:")
        val replacement = if (isJs) rule.replacement.substring(4) else rule.replacement
        val jsExtensions by lazy { RegexJsExtensions(rule.name) }
        while (matcher.find()) {
            coroutineContext.ensureActive()
            if (isJs) {
                val jsResult = evaluateJsReplacement(
                    replacement,
                    matcher.group(),
                    jsExtensions,
                    coroutineContext
                )
                matcher.appendReplacement(output, jsResult.quoteReplacementJs())
            } else {
                matcher.appendReplacement(output, replacement)
            }
        }
        matcher.appendTail(output)
        return output.toString()
    }

    private fun evaluateJsReplacement(
        script: String,
        result: String,
        jsExtensions: RegexJsExtensions,
        coroutineContext: kotlin.coroutines.CoroutineContext
    ): String {
        // The editor has no real book/chapter object; reject direct property access rather than
        // silently evaluating it against null. String literals such as "book" remain valid.
        if (containsContextReference(script)) {
            throw ReplacePreviewException(ReplacePreviewException.Reason.CONTEXT_UNAVAILABLE)
        }
        return try {
            val bindings = ScriptBindings().apply {
                this["result"] = result
                this["chapter"] = null
                this["book"] = null
                this["java"] = jsExtensions
            }
            RhinoScriptEngine.eval(script, bindings, coroutineContext).toString()
        } catch (error: CancellationException) {
            throw error
        } catch (error: RhinoInterruptError) {
            val cancellation = error.cause as? CancellationException
            if (cancellation != null) throw cancellation
            throw error
        } catch (_: Exception) {
            throw ReplacePreviewException(ReplacePreviewException.Reason.JS_EVALUATION)
        }
    }

    private class DeadlineCharSequence(
        private val source: CharSequence,
        private val startedAt: Long,
        private val timeoutNanos: Long,
        private val nanoTime: () -> Long,
        private val shouldContinue: () -> Boolean,
        private val start: Int = 0,
        private val end: Int = source.length
    ) : CharSequence {

        override val length: Int
            get() = end - start

        override fun get(index: Int): Char {
            checkDeadline()
            return source[start + index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            checkDeadline()
            return DeadlineCharSequence(
                source,
                startedAt,
                timeoutNanos,
                nanoTime,
                shouldContinue,
                start + startIndex,
                start + endIndex
            )
        }

        override fun toString(): String {
            checkDeadline()
            return source.subSequence(start, end).toString()
        }

        private fun checkDeadline() {
            if (!shouldContinue()) throw CancellationException("Preview cancelled")
            if (nanoTime() - startedAt > timeoutNanos) {
                throw PreviewDeadlineException()
            }
        }
    }

    private class PreviewDeadlineException : RuntimeException()

    private fun containsContextReference(script: String): Boolean {
        var index = 0
        var quote: Char? = null
        var escaped = false
        var lineComment = false
        var blockComment = false
        while (index < script.length) {
            val current = script[index]
            val next = script.getOrNull(index + 1)
            if (lineComment) {
                if (current == '\n' || current == '\r') lineComment = false
                index++
                continue
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false
                    index += 2
                } else {
                    index++
                }
                continue
            }
            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else if (current == '\\') {
                    escaped = true
                } else if (current == quote) {
                    quote = null
                }
                index++
                continue
            }
            if ((current == '\'' || current == '"' || current == '`')) {
                quote = current
                index++
                continue
            }
            if (current == '/' && next == '/') {
                lineComment = true
                index += 2
                continue
            }
            if (current == '/' && next == '*') {
                blockComment = true
                index += 2
                continue
            }
            if (current.isLetter() || current == '_' || current == '$') {
                val start = index
                index++
                while (index < script.length &&
                    (script[index].isLetterOrDigit() || script[index] == '_' || script[index] == '$')
                ) {
                    index++
                }
                if (script.substring(start, index) == "book" ||
                    script.substring(start, index) == "chapter"
                ) {
                    return true
                }
                continue
            }
            index++
        }
        return false
    }
}

internal class ReplacePreviewException(val reason: Reason) : NoStackTraceException(reason.name) {
    enum class Reason {
        TIMEOUT,
        CONTEXT_UNAVAILABLE,
        JS_EVALUATION
    }
}
