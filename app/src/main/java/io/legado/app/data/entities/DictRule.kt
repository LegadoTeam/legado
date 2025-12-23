package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import kotlinx.coroutines.currentCoroutineContext

/**
 * 字典规则
 */
@Entity(tableName = "dictRules")
data class DictRule(
    @PrimaryKey
    var name: String = "",
    var urlRule: String = "",
    var showRule: String = "",
    @ColumnInfo(defaultValue = "1")
    var enabled: Boolean = true,
    @ColumnInfo(defaultValue = "0")
    var sortNumber: Int = 0
) {

    override fun hashCode(): Int {
        return name.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other is DictRule) {
            return name == other.name
        }
        return false
    }

    /**
     * 搜索字典
     */
    suspend fun search(word: String): String {
        val analyzeUrl = AnalyzeUrl(urlRule, key = word, coroutineContext = currentCoroutineContext())
        val body = analyzeUrl.getStrResponseAwait().body
        if (showRule.isBlank()) {
            return body!!
        }
        val analyzeRule = AnalyzeRule().setCoroutineContext(currentCoroutineContext())
        return analyzeRule.getString(showRule, mContent = body)
    }

}