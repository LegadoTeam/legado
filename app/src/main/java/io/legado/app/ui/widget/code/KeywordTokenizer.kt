package io.legado.app.ui.widget.code

import android.widget.MultiAutoCompleteTextView

class KeywordTokenizer : MultiAutoCompleteTextView.Tokenizer {
    override fun findTokenStart(charSequence: CharSequence, cursor: Int): Int {
        var index = cursor.coerceIn(0, charSequence.length) - 1
        while (index > 0) {
            val character = charSequence[index]
            if (character == ' ' || character == '\n' || character == '(') break
            index--
        }
        if (index <= 0) return 0
        return if (index + 1 < charSequence.length) index + 1 else index
    }

    override fun findTokenEnd(charSequence: CharSequence, cursor: Int): Int {
        return charSequence.length
    }

    override fun terminateToken(charSequence: CharSequence): CharSequence {
        return charSequence
    }
}
