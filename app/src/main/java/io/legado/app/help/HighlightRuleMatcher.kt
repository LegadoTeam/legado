package io.legado.app.help

object HighlightRuleMatcher {

    data class Rule(
        val id: Long,
        val pattern: String,
        val isRegex: Boolean,
        val style: HighlightStyle,
        val timeoutMs: Long = 3000L,
        val applyToTitle: Boolean = false
    )

    data class RuleMatch(
        val start: Int,
        val end: Int,
        val ruleId: Long,
        val style: HighlightStyle,
        val applyToTitle: Boolean
    )

    fun match(
        text: String,
        rules: List<Rule>,
        shouldContinue: () -> Boolean = { true },
        maxMatches: Int = DEFAULT_MAX_MATCHES
    ): List<RuleMatch> {
        val limit = maxMatches.coerceAtLeast(0)
        if (text.isEmpty() || rules.isEmpty() || limit == 0) return emptyList()
        val matches = ArrayList<RuleMatch>()
        for (rule in rules) {
            if (!shouldContinue() || matches.size >= limit) break
            if (rule.pattern.isEmpty()) continue
            if (rule.isRegex) {
                matchRegex(text, rule, matches, shouldContinue, limit)
            } else {
                matchLiteral(text, rule, matches, shouldContinue, limit)
            }
        }
        return matches
    }

    private fun matchLiteral(
        text: String,
        rule: Rule,
        out: MutableList<RuleMatch>,
        shouldContinue: () -> Boolean,
        maxMatches: Int
    ) {
        var from = 0
        while (from <= text.length && out.size < maxMatches && shouldContinue()) {
            val start = text.indexOf(rule.pattern, from)
            if (start < 0) return
            val end = start + rule.pattern.length
            out.add(rule.match(start, end))
            from = end
        }
    }

    private fun matchRegex(
        text: String,
        rule: Rule,
        out: MutableList<RuleMatch>,
        shouldContinue: () -> Boolean,
        maxMatches: Int
    ) {
        try {
            val regex = Regex(rule.pattern)
            val timeoutNanos = rule.timeoutMs
                .coerceAtLeast(1L)
                .coerceAtMost(Long.MAX_VALUE / 1_000_000L) * 1_000_000L
            val startedAt = System.nanoTime()
            val input = DeadlineCharSequence(
                text,
                startedAt,
                timeoutNanos,
                shouldContinue
            )
            var from = 0
            while (from <= text.length &&
                out.size < maxMatches &&
                shouldContinue() &&
                System.nanoTime() - startedAt <= timeoutNanos
            ) {
                val result = regex.find(input, from) ?: return
                val start = result.range.first
                val end = result.range.last + 1
                if (end > start) {
                    out.add(rule.match(start, end))
                    from = end
                } else {
                    from = start + 1
                }
            }
        } catch (_: Exception) {
            return
        } catch (_: StackOverflowError) {
            return
        }
    }

    private fun Rule.match(start: Int, end: Int) =
        RuleMatch(start, end, id, style, applyToTitle)

    private class DeadlineCharSequence(
        private val source: CharSequence,
        private val startedAt: Long,
        private val timeoutNanos: Long,
        private val shouldContinue: () -> Boolean
    ) : CharSequence {

        override val length: Int
            get() = source.length

        override fun get(index: Int): Char {
            checkDeadline()
            return source[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            checkDeadline()
            return DeadlineCharSequence(
                source.subSequence(startIndex, endIndex),
                startedAt,
                timeoutNanos,
                shouldContinue
            )
        }

        override fun toString(): String {
            checkDeadline()
            return source.toString()
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
