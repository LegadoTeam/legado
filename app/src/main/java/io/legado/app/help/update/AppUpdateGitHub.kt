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
            ?: AppVariant.OFFICIAL

    private suspend fun getLatestRelease(): List<AppReleaseInfo> {
        val lastReleaseUrl =
            "https://api.github.com/repos/LegadoTeam/legado/releases?per_page=30"
        val res = okHttpClient.newCallResponse {
            url(lastReleaseUrl)
        }
        if (!res.isSuccessful) {
            throw NoStackTraceException("获取新版本出错(${res.code})")
        }
        val body = res.body.text()
        if (body.isBlank()) {
            throw NoStackTraceException("获取新版本出错")
        }
        val releases = GSON.fromJsonArray<GithubRelease>(body).getOrElse {
                throw NoStackTraceException("获取新版本出错 " + it.localizedMessage)
        }
        return releases
            .filterNot { it.isPreRelease }
            .flatMap { it.gitReleaseToAppReleaseInfo() }
            .sortedByDescending { it.createdAt }
    }

    private suspend fun getBetaRelease(): List<AppReleaseInfo> {
        val res = okHttpClient.newCallResponse {
            url("https://api.github.com/repos/LegadoTeam/legado/releases/tags/beta")
        }
        if (!res.isSuccessful) {
            throw NoStackTraceException(
                "${appCtx.getString(R.string.check_beta_update_failed)}(${res.code})"
            )
        }
        val body = res.body.text()
        if (body.isBlank()) {
            throw NoStackTraceException(appCtx.getString(R.string.check_beta_update_failed))
        }
        return runCatching {
            GSON.fromJson(body, GithubRelease::class.java).gitReleaseToAppReleaseInfo()
        }.getOrElse {
            throw NoStackTraceException(
                appCtx.getString(R.string.check_beta_update_failed) + " " + it.localizedMessage
            )
        }
    }

    override fun check(
        scope: CoroutineScope,
    ): Coroutine<AppUpdate.UpdateInfo> {
        return Coroutine.async(scope) {
            selectUpdateRelease(
                releases = getLatestRelease(),
                appVariant = checkVariant,
                currentVersionName = AppConst.appInfo.versionName,
                supportedAbis = Build.SUPPORTED_ABIS.toList()
            )
                ?.let { return@async it.toUpdateInfo() }
            throw NoStackTraceException("已是最新版本")
        }.timeout(10000)
    }

    fun checkBeta(scope: CoroutineScope): Coroutine<AppUpdate.UpdateInfo> {
        return Coroutine.async(scope) {
            val variant = AppConst.betaUpdateVariant
                ?: throw NoStackTraceException(appCtx.getString(R.string.beta_update_unsupported))
            val releases = getBetaRelease()
            selectUpdateRelease(
                releases = releases,
                appVariant = variant,
                currentVersionName = AppConst.appInfo.versionName,
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
                currentVersionCode = AppConst.appInfo.versionCode
            )
                ?.let { return@async it.toUpdateInfo(isBeta = true) }
            if (releases.any {
                    it.appVariant == variant && it.isNewerThan(
                        AppConst.appInfo.versionName,
                        AppConst.appInfo.versionCode
                    )
                }) {
                throw NoStackTraceException(appCtx.getString(R.string.beta_update_no_compatible_apk))
            }
            throw NoStackTraceException(appCtx.getString(R.string.latest_beta_version))
        }.timeout(10000)
    }
}

internal fun AppReleaseInfo.toUpdateInfo(isBeta: Boolean = false): AppUpdate.UpdateInfo {
    val primaryUrl = if (isBeta) downloadUrl else resolveAppUpdateDownloadUrl(name, downloadUrl)
    val mirrorUrl = if (isBeta) null else resolveAppUpdateMirrorUrl(name, primaryUrl)
    val alternateMirrorUrl = if (isBeta) {
        null
    } else {
        resolveAppUpdateAlternateMirrorUrl(name, primaryUrl, mirrorUrl)
    }
    return AppUpdate.UpdateInfo(
        tagName = versionName,
        updateLog = note,
        downloadUrl = primaryUrl,
        fileName = name,
        backupDownloadUrl = if (isBeta) null else resolveAppUpdateBackupUrl(primaryUrl, downloadUrl),
        mirrorDownloadUrl = mirrorUrl,
        alternateMirrorDownloadUrl = alternateMirrorUrl,
        size = size,
        createdAt = createdAt,
        isBeta = isBeta
    )
}
