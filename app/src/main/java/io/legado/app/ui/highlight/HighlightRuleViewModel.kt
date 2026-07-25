package io.legado.app.ui.highlight

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightRule

class HighlightRuleViewModel(application: Application) : BaseViewModel(application) {

    fun update(vararg rule: HighlightRule) = execute {
        appDb.highlightRuleDao.update(*rule)
    }

    fun delete(rule: HighlightRule) = execute {
        appDb.highlightRuleDao.delete(rule)
    }

    fun toTop(rule: HighlightRule) = execute {
        rule.order = appDb.highlightRuleDao.minOrder - 1
        appDb.highlightRuleDao.update(rule)
    }

    fun toBottom(rule: HighlightRule) = execute {
        rule.order = appDb.highlightRuleDao.maxOrder + 1
        appDb.highlightRuleDao.update(rule)
    }
}
