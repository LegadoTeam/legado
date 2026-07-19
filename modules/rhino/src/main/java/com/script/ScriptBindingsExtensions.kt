package com.script

import org.htmlunit.corejs.javascript.Context

inline fun buildScriptBindings(block: (bindings: ScriptBindings) -> Unit): ScriptBindings {
    val bindings = ScriptBindings()
    Context.enter()
    try {
        block(bindings)
    } finally {
        Context.exit()
    }
    return bindings
}
