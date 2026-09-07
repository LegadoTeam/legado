package io.legado.app.service

import io.legado.app.constant.AppPattern
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.paramPattern
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

private val legacyReviewClickPattern = Regex(
    """^(?:getDP\(\s*\d+\s*,\s*\d+\s*\)|getZP\(\s*\d+\s*\))$"""
)

private val legacyReviewShowPattern = Regex(
    """^(?:dp|zp)show\(\s*\d+\s*\)$""",
    RegexOption.IGNORE_CASE
)

private val legacyReviewUrlPattern = Regex(
    """^(?:dp|zp)url\([\s\S]*\)$""",
    RegexOption.IGNORE_CASE
)

private val exportInvisibleCharsRegex = Regex("[\\u200B\\uFEFF]")

internal fun sanitizeExportContent(content: String): String {
    val matcher = AppPattern.imgPattern.matcher(content)
    if (!matcher.find()) {
        return content.replace(exportInvisibleCharsRegex, "")
    }

    val sanitized = StringBuilder(content.length)
    var start = 0
    do {
        if (matcher.start() > start) {
            sanitized.append(content, start, matcher.start())
        }
        if (!isExportReviewImage(matcher.group(1))) {
            sanitized.append(content, matcher.start(), matcher.end())
        }
        start = matcher.end()
    } while (matcher.find())
    if (start < content.length) {
        sanitized.append(content, start, content.length)
    }
    return sanitized.toString().replace(exportInvisibleCharsRegex, "")
}

internal fun removeExportImages(content: String): String {
    val matcher = AppPattern.imgPattern.matcher(content)
    if (!matcher.find()) return content
    val text = StringBuilder(content.length)
    var start = 0
    do {
        text.append(content, start, matcher.start())
        start = matcher.end()
    } while (matcher.find())
    if (start < content.length) {
        text.append(content, start, content.length)
    }
    return text.toString()
}

internal fun isExportReviewImage(src: String?): Boolean {
    if (src.isNullOrEmpty()) return false
    val urlMatcher = paramPattern.matcher(src)
    if (!urlMatcher.find()) return false
    val options = GSON.fromJsonObject<Map<String, String>>(src.substring(urlMatcher.end()))
        .getOrNull() ?: return false
    val click = options["click"]
    if (click != null && legacyReviewClickPattern.matches(click.trim())) {
        return true
    }
    if (click != null && legacyReviewShowPattern.matches(click.trim())) {
        val js = options["js"]?.trim()
        if (js != null && legacyReviewUrlPattern.matches(js)) return true
    }
    val style = options["style"]
    val reviewCount = options["reviewCount"]?.toIntOrNull() ?: return false
    return (style == "text" || style == "TEXT") &&
        reviewCount > 0 &&
        !click.isNullOrBlank()
}
