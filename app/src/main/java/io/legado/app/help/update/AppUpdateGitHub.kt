package io.legado.app.help.update

import android.os.Build
import androidx.annotation.Keep
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.CoroutineScope
import splitties.init.appCtx

@Keep
@Suppress("unused")
object AppUpdateGitHub : AppUpdate.AppUpdateInterface {

    private val checkVariant: AppVariant
        get() = AppConst.appInfo.appVariant.takeUnless { it == AppVariant.UNKNOWN }
            ?: AppVariant.BETA_RELEASE

    private suspend fun getReleases(channel: UpdateChannel): List<AppReleaseInfo> {
        val res = okHttpClient.newCallResponse {
            url("https://api.github.com/repos/yuanxiaoaihezhou/Reader/releases?per_page=30")
        }
        if (!res.isSuccessful) {
            throw NoStackTraceException("获取新版本出错(${res.code})")
        }
        val body = res.body.text()
        if (body.isBlank()) throw NoStackTraceException("获取新版本出错")
        val releases = GSON.fromJsonArray<GithubRelease>(body).getOrElse {
            throw NoStackTraceException("获取新版本出错 " + it.localizedMessage)
        }
        return releases
            .asSequence()
            .filter { channel == UpdateChannel.LATEST || !it.isPreRelease }
            .flatMap { it.gitReleaseToAppReleaseInfo().asSequence() }
            .sortedByDescending { it.createdAt }
            .toList()
    }

    override fun check(scope: CoroutineScope): Coroutine<AppUpdate.UpdateInfo> =
        check(scope, UpdateChannel.STABLE)

    fun check(scope: CoroutineScope, channel: UpdateChannel): Coroutine<AppUpdate.UpdateInfo> {
        return Coroutine.async(scope) {
            val releases = getReleases(channel)
            selectUpdateRelease(
                releases = releases,
                appVariant = checkVariant,
                currentVersionName = AppConst.appInfo.versionName,
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
                currentVersionCode = AppConst.appInfo.versionCode
            )?.let { return@async it.toUpdateInfo() }

            if (releases.any {
                    it.appVariant == checkVariant &&
                        it.isNewerThan(AppConst.appInfo.versionName, AppConst.appInfo.versionCode)
                }) {
                throw NoStackTraceException(appCtx.getString(R.string.update_no_compatible_apk))
            }
            throw NoStackTraceException(appCtx.getString(R.string.latest_version))
        }.timeout(10000)
    }
}

internal fun AppReleaseInfo.toUpdateInfo(): AppUpdate.UpdateInfo =
    AppUpdate.UpdateInfo(
        tagName = versionName,
        updateLog = note,
        downloadUrl = downloadUrl,
        fileName = name,
        size = size,
        createdAt = createdAt,
        isBeta = isPreRelease
    )
