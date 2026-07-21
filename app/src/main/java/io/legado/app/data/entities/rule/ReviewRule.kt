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
    var enabled: Boolean = false,
    var reviewSummaryUrl: String? = null,
    var summaryListRule: String? = null,
    var summaryParagraphIndexRule: String? = null,
    var summaryParagraphDataRule: String? = null,
    var summaryCountRule: String? = null,
    var reviewDetailUrl: String? = null,
    var reviewDetailNextPageUrl: String? = null,
    var detailListRule: String? = null,
    var detailIdRule: String? = null,
    var detailAvatarRule: String? = null,
    var detailNameRule: String? = null,
    var detailBadgeRule: String? = null,
    var detailContentRule: String? = null,
    var replyListRule: String? = null,
    var replyIdRule: String? = null,
    var replyAvatarRule: String? = null,
    var replyNameRule: String? = null,
    var replyBadgeRule: String? = null,
    var replyContentRule: String? = null,
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
