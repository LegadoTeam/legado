package io.legado.app.help.update

import android.os.Build
import androidx.annotation.Keep
import io.legado.app.constant.AppConst
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CoroutineScope

@Keep
@Suppress("unused")
object AppUpdateGitHub : AppUpdate.AppUpdateInterface {

    private val checkVariant: AppVariant
        get() = when (AppConfig.updateToVariant) {
            "official_version" -> AppVariant.OFFICIAL
            "beta_release_version" -> AppVariant.BETA_RELEASE
            "beta_releaseA_version" -> AppVariant.BETA_RELEASEA
            else -> AppConst.appInfo.appVariant.takeUnless { it == AppVariant.UNKNOWN }
                ?: AppVariant.OFFICIAL
        }

    private suspend fun getLatestRelease(): List<AppReleaseInfo> {
        val betaRelease = checkVariant.isBeta()
        val lastReleaseUrl = if (betaRelease) {
            "https://api.github.com/repos/LegadoTeam/legado/releases?per_page=10"
        } else {
            "https://api.github.com/repos/LegadoTeam/legado/releases/latest"
        }
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
        val releases = if (betaRelease) {
            GSON.fromJsonArray<GithubRelease>(body).getOrElse {
                throw NoStackTraceException("获取新版本出错 " + it.localizedMessage)
            }
        } else {
            listOf(GSON.fromJsonObject<GithubRelease>(body).getOrElse {
                throw NoStackTraceException("获取新版本出错 " + it.localizedMessage)
            })
        }
        return releases
            .flatMap { it.gitReleaseToAppReleaseInfo() }
            .sortedByDescending { it.createdAt }
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
                ?.let {
                    return@async AppUpdate.UpdateInfo(
                        it.versionName,
                        it.note,
                        it.downloadUrl,
                        it.name
                    )
                }
            throw NoStackTraceException("已是最新版本")
        }.timeout(10000)
    }
}
