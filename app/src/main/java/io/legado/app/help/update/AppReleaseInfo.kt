package io.legado.app.help.update

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import io.legado.app.exception.NoStackTraceException
import java.time.Instant

data class AppReleaseInfo(
    val appVariant: AppVariant,
    val createdAt: Long,
    val note: String,
    val name: String,
    val downloadUrl: String,
    val assetUrl: String,
    val versionName: String
)

enum class AppVariant {
    OFFICIAL,
    BETA_RELEASEA,
    BETA_RELEASE,
    UNKNOWN;

    fun isBeta(): Boolean {
        return this == BETA_RELEASE || this == BETA_RELEASEA
    }

}

@Keep
data class GithubRelease(
    val assets: List<Asset>?,
    val body: String,
    @SerializedName("prerelease")
    val isPreRelease: Boolean,
    @SerializedName("tag_name")
    val tagName: String = "",
) {
    fun gitReleaseToAppReleaseInfo(): List<AppReleaseInfo> {
        assets ?: throw NoStackTraceException("获取新版本出错")
        return assets
            .filter { it.isValid }
            .map { it.assetToAppReleaseInfo(isPreRelease, body, tagName) }
    }
}
@Keep
data class Asset(
    @SerializedName("browser_download_url")
    val apkUrl: String,
    @SerializedName("content_type")
    val contentType: String,
    @SerializedName("created_at")
    val createdAt: String,
    @SerializedName("download_count")
    val downloadCount: Int,
    val id: Int,
    val name: String,
    val state: String,
    val url: String
) {
    val isValid: Boolean
        get() = (contentType == "application/vnd.android.package-archive") && (state == "uploaded")

    fun assetToAppReleaseInfo(
        preRelease: Boolean,
        note: String,
        releaseTag: String = ""
    ): AppReleaseInfo {
        val instant = Instant.parse(createdAt)
        val timestamp: Long = instant.toEpochMilli()
        val appVariant = inferAppVariant(name, preRelease)
        return AppReleaseInfo(
            appVariant,
            timestamp,
            note,
            name,
            apkUrl,
            url,
            parseReleaseVersionName(releaseTag, name, appVariant)
        )
    }
}

private val versionPattern = Regex("""\d+(?:\.\d+)+""")
private val releaseAPattern = Regex("""(?:^|[_\-.])releasea(?:[_\-.]|$)""", RegexOption.IGNORE_CASE)
private val releasePattern = Regex("""(?:^|[_\-.])release(?:[_\-.]|$)""", RegexOption.IGNORE_CASE)

internal fun inferAppVariant(assetName: String, preRelease: Boolean): AppVariant {
    return when {
        releaseAPattern.containsMatchIn(assetName) -> AppVariant.BETA_RELEASEA
        releasePattern.containsMatchIn(assetName) -> AppVariant.BETA_RELEASE
        preRelease -> AppVariant.BETA_RELEASE
        else -> AppVariant.OFFICIAL
    }
}

internal fun parseReleaseVersionName(
    releaseTag: String,
    assetName: String,
    appVariant: AppVariant
): String {
    versionPattern.find(releaseTag)?.value?.let { return it }
    val assetVersion = versionPattern.find(assetName)?.value.orEmpty()
    val lastPart = assetVersion.substringAfterLast('.')
    return if (appVariant.isBeta() && lastPart.length >= 8) {
        assetVersion.dropLast(2)
    } else {
        assetVersion
    }
}


