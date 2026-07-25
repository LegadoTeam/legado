package io.legado.app.ui.association

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.help.DefaultData
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.utils.FileUtils
import io.legado.app.utils.externalCache
import okhttp3.MediaType.Companion.toMediaType
import splitties.init.appCtx

class OnLineImportViewModel(app: Application) : BaseAssociationViewModel(app) {

    val readConfigLive = MutableLiveData<ByteArray?>()
    var intentHandled = false

    fun getText(url: String, success: (text: String) -> Unit) {
        execute {
            okHttpClient.newCallResponseBody {
                if (url.endsWith("#requestWithoutUA")) {
                    url(url.substringBeforeLast("#requestWithoutUA"))
                    header(AppConst.UA_NAME, "null")
                } else {
                    url(url)
                }
            }.decompressed().text("utf-8")
        }.onSuccess {
            success.invoke(it)
        }.onError {
            errorLive.postValue(
                it.localizedMessage ?: context.getString(R.string.unknown_error)
            )
        }
    }

    fun getReadConfig(url: String) {
        execute {
            okHttpClient.newCallResponseBody {
                if (url.endsWith("#requestWithoutUA")) {
                    url(url.substringBeforeLast("#requestWithoutUA"))
                    header(AppConst.UA_NAME, "null")
                } else {
                    url(url)
                }
            }.bytes()
        }.onSuccess {
            readConfigLive.value = it
        }.onError {
            errorLive.postValue(
                it.localizedMessage ?: context.getString(R.string.unknown_error)
            )
        }
    }

    fun importReadConfig() {
        val bytes = readConfigLive.value ?: return
        readConfigLive.value = null
        execute {
            val config = ReadBookConfig.import(bytes)
            applyImportedReadConfig(
                configs = ReadBookConfig.configList,
                imported = config,
                defaultConfigs = { DefaultData.readConfigs },
                save = ReadBookConfig::saveNow,
                transactionLock = ReadBookConfig,
            )
        }.onSuccess {
            successLive.value = "readConfig" to context.getString(R.string.read_config_import_success)
        }.onError {
            errorLive.value =
                it.localizedMessage ?: context.getString(R.string.unknown_error)
        }
    }

    fun cancelReadConfigImport() {
        readConfigLive.value = null
    }

    fun determineType(url: String) {
        execute {
            val rs = okHttpClient.newCallResponseBody {
                if (url.endsWith("#requestWithoutUA")) {
                    url(url.substringBeforeLast("#requestWithoutUA"))
                    header(AppConst.UA_NAME, "null")
                } else {
                    url(url)
                }
            }
            when (rs.contentType()) {
                "application/zip".toMediaType(),
                "application/octet-stream".toMediaType() -> {
                    rs.bytes()
                }
                else -> {
                    val inputStream = rs.byteStream()
                    val file = FileUtils.createFileIfNotExist(
                        appCtx.externalCache,
                        "download",
                        "scheme_import_cache.json"
                    )
                    file.outputStream().use { out ->
                        inputStream.use {
                            it.copyTo(out)
                        }
                    }
                    importJson(file.toUri())
                    null
                }
            }
        }.onSuccess {
            if (it != null) {
                readConfigLive.value = it
            }
        }.onError {
            errorLive.postValue(
                it.localizedMessage ?: context.getString(R.string.unknown_error)
            )
        }
    }

}
