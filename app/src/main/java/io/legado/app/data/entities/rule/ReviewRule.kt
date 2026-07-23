package io.legado.app.data.entities.rule

import android.os.Parcelable
import com.google.gson.JsonDeserializer
import io.legado.app.utils.INITIAL_GSON
import kotlinx.parcelize.Parcelize

@Parcelize
data class ReviewRule(
    var reviewUrl: String? = null,          // 段评URL
    var avatarRule: String? = null,         // 段评发布者头像
    var contentRule: String? = null,        // 段评内容
    var postTimeRule: String? = null,       // 段评发布时间
    var reviewQuoteUrl: String? = null,     // 获取段评回复URL

    // 这些功能将在以上功能完成以后实现
    var voteUpUrl: String? = null,          // 点赞URL
    var voteDownUrl: String? = null,        // 点踩URL
    var postReviewUrl: String? = null,      // 发送回复URL
    var postQuoteUrl: String? = null,       // 发送回复段评URL
    var deleteUrl: String? = null,          // 删除段评URL

    // 段评摘要/概览规则
    var summaryListRule: String? = null,              // 段评概览列表
    var summaryParagraphIndexRule: String? = null,    // 段落索引规则
    var summaryCountRule: String? = null,              // 段评计数规则
    var summaryParagraphDataRule: String? = null,      // 段落数据规则

    // 段评详情规则
    var detailListRule: String? = null,               // 详情列表
    var detailIdRule: String? = null,                 // 详情ID
    var detailAvatarRule: String? = null,             // 详情头像
    var detailNameRule: String? = null,               // 详情昵称
    var detailBadgeRule: String? = null,              // 详情徽章
    var detailContentRule: String? = null,            // 详情内容

    // 回复规则
    var replyIdRule: String? = null,                  // 回复ID
    var replyAvatarRule: String? = null,              // 回复头像
    var replyNameRule: String? = null,                // 回复昵称
    var replyBadgeRule: String? = null,               // 回复徽章
    var replyContentRule: String? = null,             // 回复内容
    var replyListRule: String? = null,                // 回复列表

    var reviewDetailUrl: String? = null,              // 段评详情URL
    var reviewDetailNextPageUrl: String? = null,      // 段评下一页URL

    var enabled: Boolean = false,                      // 是否启用
) : Parcelable {

    companion object {

        val jsonDeserializer = JsonDeserializer<ReviewRule?> { json, _, _ ->
            when {
                json.isJsonObject -> INITIAL_GSON.fromJson(json, ReviewRule::class.java)
                json.isJsonPrimitive -> INITIAL_GSON.fromJson(json.asString, ReviewRule::class.java)
                else -> null
            }
        }

    }

}
