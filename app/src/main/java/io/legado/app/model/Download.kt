package io.legado.app.model

import android.content.Context
import io.legado.app.constant.IntentAction
import io.legado.app.service.DownloadService
import io.legado.app.utils.startService

object Download {


    fun start(context: Context, url: String, fileName: String, isAppUpdate: Boolean = false) {
        context.startService<DownloadService> {
            action = IntentAction.start
            putExtra("url", url)
            putExtra("fileName", fileName)
            putExtra("isAppUpdate", isAppUpdate)
        }
    }

}
