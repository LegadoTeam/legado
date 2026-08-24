package io.legado.app.help.update

import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.CoroutineScope

object AppUpdate {

    val gitHubUpdate: AppUpdateInterface by lazy {
        AppUpdateGitHub
    }

    fun checkBeta(scope: CoroutineScope): Coroutine<UpdateInfo> =
        AppUpdateGitHub.checkBeta(scope)

    data class UpdateInfo(
        val tagName: String,
        val updateLog: String,
        val downloadUrl: String,
        val fileName: String,
        val backupDownloadUrl: String? = null,
        val mirrorDownloadUrl: String? = null,
        val alternateMirrorDownloadUrl: String? = null,
        val size: Long = 0L,
        val createdAt: Long = 0L,
        val isBeta: Boolean = false
    )

    interface AppUpdateInterface {

        fun check(scope: CoroutineScope): Coroutine<UpdateInfo>

    }

}
