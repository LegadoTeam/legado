package io.legado.app.help

object HighlightRuleMatcher {

    data class Rule(
        val id: Long,
        val pattern: String,
        val isRegex: Boolean,
        val style: HighlightStyle,
        val timeoutMs: Long = 3000L,
        val applyToTitle: Boolean = false,
        val applyToBody: Boolean = true
    )

    data class RuleMatch(
        val start: Int,
        val end: Int,
        val ruleId: Long,
        val style: HighlightStyle,
        val applyToTitle: Boolean,
        val applyToBody: Boolean = true
    )

    data class MatchResult(
        val matches: List<RuleMatch>,
        val completed: Boolean
    )

    fun match(
        text: String,
        rules: List<Rule>,
        shouldContinue: () -> Boolean = { true },
        maxMatches: Int = DEFAULT_MAX_MATCHES,
        titleLength: Int = 0
    ): List<RuleMatch> =
        matchDetailed(text, rules, shouldContinue, maxMatches, titleLength).matches

    internal fun matchDetailed(
        text: String,
        rules: List<Rule>,
        shouldContinue: () -> Boolean = { true },
        maxMatches: Int = DEFAULT_MAX_MATCHES,
        titleLength: Int = 0
    ): MatchResult {
        val limit = maxMatches.coerceAtLeast(0)
        if (text.isEmpty() || rules.isEmpty() || limit == 0) {
            return MatchResult(emptyList(), completed = true)
        }
        val titleEnd = titleLength.coerceIn(0, text.length)
        val matches = ArrayList<RuleMatch>()
        for (rule in rules) {
            if (!shouldContinue()) {
                return MatchResult(matches, completed = false)
            }
            if (rule.pattern.isEmpty() || !rule.applyToTitle && !rule.applyToBody) continue
            val completed = if (rule.isRegex) {
                matchRegex(text, titleEnd, rule, matches, shouldContinue, limit)
            } else {
                matchLiteral(text, titleEnd, rule, matches, shouldContinue, limit)
            }
            if (!completed) {
                return MatchResult(matches, completed = false)
            }
        }
        return MatchResult(matches, completed = true)
    }

    private fun matchLiteral(
        text: String,
        titleEnd: Int,
        rule: Rule,
        out: MutableList<RuleMatch>,
        shouldContinue: () -> Boolean,
        maxMatches: Int
    ): Boolean {
        fun matchSegment(segmentStart: Int, segmentEnd: Int): Boolean {
            if (segmentStart >= segmentEnd) return true
            val isSlice = segmentEnd < text.length
            val input = if (isSlice) text.substring(segmentStart, segmentEnd) else text
            val offset = if (isSlice) segmentStart else 0
            var from = if (isSlice) 0 else segmentStart
            while (shouldContinue()) {
                val start = input.indexOf(rule.pattern, from)
                if (start < 0) return true
                val end = start + rule.pattern.length
                if (out.size >= maxMatches) return false
                out.add(rule.match(offset + start, offset + end))
                from = end
            }
            return false
        }
        if (rule.applyToTitle && rule.applyToBody) return matchSegment(0, text.length)
        if (rule.applyToTitle && !matchSegment(0, titleEnd)) return false
        return !rule.applyToBody || matchSegment(titleEnd, text.length)
    }

    private fun matchRegex(
        text: String,
        titleEnd: Int,
        rule: Rule,
        out: MutableList<RuleMatch>,
        shouldContinue: () -> Boolean,
        maxMatches: Int
    ): Boolean {
        val regex = try {
            Regex(rule.pattern)
        } catch (_: Exception) {
            return true
        } catch (_: StackOverflowError) {
            return false
        }
        val timeoutNanos = rule.timeoutMs
            .coerceAtLeast(1L)
            .coerceAtMost(Long.MAX_VALUE / 1_000_000L) * 1_000_000L
        val startedAt = System.nanoTime()
        try {
            fun matchSegment(segmentStart: Int, segmentEnd: Int): Boolean {
                if (segmentStart >= segmentEnd) return true
                val input = DeadlineCharSequence(
                    text,
                    startedAt,
                    timeoutNanos,
                    shouldContinue,
                    segmentStart,
                    segmentEnd
                )
                var result = regex.find(input)
                while (result != null) {
                    if (!shouldContinue() || System.nanoTime() - startedAt > timeoutNanos) {
                        return false
                    }
                    if (out.size >= maxMatches) return false
                    val start = result.range.first
                    val end = result.range.last + 1
                    if (end > start) {
                        out.add(rule.match(segmentStart + start, segmentStart + end))
                    }
                    result = result.next()
                }
                return true
            }
            if (rule.applyToTitle && rule.applyToBody) return matchSegment(0, text.length)
            if (rule.applyToTitle && !matchSegment(0, titleEnd)) return false
            return !rule.applyToBody || matchSegment(titleEnd, text.length)
        } catch (_: MatchCancelledException) {
            return false
        } catch (_: RegexTimeoutException) {
            return false
        } catch (_: Exception) {
            return false
        } catch (_: StackOverflowError) {
            return false
        }
    }

    private fun Rule.match(start: Int, end: Int) =
        RuleMatch(start, end, id, style, applyToTitle, applyToBody)

    private class DeadlineCharSequence(
        private val source: CharSequence,
        private val startedAt: Long,
        private val timeoutNanos: Long,
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
            if (!shouldContinue()) {
                throw MatchCancelledException()
            }
            if (System.nanoTime() - startedAt > timeoutNanos) {
                throw RegexTimeoutException()
            }
        }
    }

    private class MatchCancelledException : RuntimeException()
    private class RegexTimeoutException : RuntimeException()

    internal const val DEFAULT_MAX_MATCHES = 10_000
}
