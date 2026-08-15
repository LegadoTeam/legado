package io.legado.app.ui.highlight

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightRule

class HighlightRuleViewModel(application: Application) : BaseViewModel(application) {

    fun update(vararg rule: HighlightRule) = execute {
        appDb.highlightRuleDao.update(*rule)
    }

    fun delete(vararg rule: HighlightRule) = execute {
        appDb.highlightRuleDao.delete(*rule)
    }

    fun toTop(rule: HighlightRule) = execute {
        appDb.highlightRuleDao.move(setOf(rule.uuid), true)
    }

    fun toBottom(rule: HighlightRule) = execute {
        appDb.highlightRuleDao.move(setOf(rule.uuid), false)
    }

    fun enableSelection(rules: List<HighlightRule>, enabled: Boolean) = execute {
        appDb.highlightRuleDao.update(
            *rules.map { it.copy(isEnabled = enabled) }.toTypedArray()
        )
    }

    fun moveSelection(rules: List<HighlightRule>, toTop: Boolean) = execute {
        appDb.highlightRuleDao.move(rules.mapTo(linkedSetOf()) { it.uuid }, toTop)
    }
}
