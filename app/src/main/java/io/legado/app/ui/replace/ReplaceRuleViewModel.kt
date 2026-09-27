package io.legado.app.ui.replace

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.config.ReplacePreviewConfig
import io.legado.app.utils.renameGroupExact
import io.legado.app.utils.moveRelativeTo

/**
 * 替换规则数据修改
 * 修改数据要copy,直接修改会导致界面不刷新
 */
class ReplaceRuleViewModel(application: Application) : BaseViewModel(application) {

    fun update(vararg rule: ReplaceRule) {
        execute {
            appDb.replaceRuleDao.update(*rule)
        }
    }

    fun enable(id: Long, enable: Boolean) {
        execute { appDb.replaceRuleDao.enable(id, enable) }
    }

    fun delete(rule: ReplaceRule) {
        execute {
            appDb.replaceRuleDao.delete(rule)
            ReplacePreviewConfig.removeSample(rule.id)
        }
    }

    fun toTop(rule: ReplaceRule) {
        execute {
            rule.order = appDb.replaceRuleDao.minOrder - 1
            appDb.replaceRuleDao.update(rule)
        }
    }

    fun topSelect(rules: List<ReplaceRule>) {
        execute {
            var minOrder = appDb.replaceRuleDao.minOrder - rules.size
            rules.forEach {
                it.order = minOrder++
            }
            appDb.replaceRuleDao.update(*rules.toTypedArray())
        }
    }

    fun toBottom(rule: ReplaceRule) {
        execute {
            rule.order = appDb.replaceRuleDao.maxOrder + 1
            appDb.replaceRuleDao.update(rule)
        }
    }

    fun bottomSelect(rules: List<ReplaceRule>) {
        execute {
            var maxOrder = appDb.replaceRuleDao.maxOrder + 1
            rules.forEach {
                it.order = maxOrder++
            }
            appDb.replaceRuleDao.update(*rules.toTypedArray())
        }
    }

    fun move(ruleId: Long, targetId: Long, after: Boolean, onFinally: () -> Unit = {}) {
        executeLazy {
            appDb.runInTransaction {
                val current = appDb.replaceRuleDao.all
                val reordered = moveRelativeTo(current, ruleId, targetId, after) { it.id }
                if (reordered == current) return@runInTransaction
                appDb.replaceRuleDao.update(*reordered.mapIndexed { index, item ->
                    item.copy(order = index)
                }.toTypedArray())
            }
        }.onFinally { onFinally() }.start()
    }

    fun enableSelection(rules: List<ReplaceRule>) {
        execute {
            appDb.replaceRuleDao.enable(true, rules)
        }
    }

    fun disableSelection(rules: List<ReplaceRule>) {
        execute {
            appDb.replaceRuleDao.enable(false, rules)
        }
    }

    fun selectionAddToGroups(rules: List<ReplaceRule>, groups: String) {
        execute {
            val array = Array(rules.size) {
                rules[it].copy().addGroup(groups)
            }
            appDb.replaceRuleDao.update(*array)
        }
    }

    fun selectionRemoveFromGroups(rules: List<ReplaceRule>, groups: String) {
        execute {
            val array = Array(rules.size) {
                rules[it].copy().removeGroup(groups)
            }
            appDb.replaceRuleDao.update(*array)
        }
    }

    fun delSelection(rules: List<ReplaceRule>) {
        execute {
            appDb.replaceRuleDao.delete(*rules.toTypedArray())
            rules.forEach { ReplacePreviewConfig.removeSample(it.id) }
        }
    }

    fun addGroup(group: String) {
        execute {
            val sources = appDb.replaceRuleDao.noGroup
            sources.forEach { source ->
                source.group = group
            }
            appDb.replaceRuleDao.update(*sources.toTypedArray())
        }
    }

    fun upGroup(oldGroup: String, newGroup: String?) {
        execute {
            val sources = appDb.replaceRuleDao.getByGroup(oldGroup).mapNotNull { source ->
                source.group.renameGroupExact(oldGroup, newGroup)?.let { groups ->
                    source.apply { group = groups }
                }
            }
            if (sources.isNotEmpty()) {
                appDb.replaceRuleDao.update(*sources.toTypedArray())
            }
        }
    }

    fun delGroup(group: String) = upGroup(group, null)
}
