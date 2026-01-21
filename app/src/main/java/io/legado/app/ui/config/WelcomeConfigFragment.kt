package io.legado.app.ui.config

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.prefs.SwitchPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.BookCover
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefString
import io.legado.app.utils.inputStream
import io.legado.app.utils.putPrefString
import io.legado.app.utils.readUri
import io.legado.app.utils.removePref
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.io.FileOutputStream
class WelcomeConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val requestWelcomeImage = 221
    private val requestWelcomeImageDark = 222
    private val selectImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            when (it.requestCode) {
                requestWelcomeImage -> setCoverFromUri(PreferKey.welcomeImage, uri)
                requestWelcomeImageDark -> setCoverFromUri(PreferKey.welcomeImageDark, uri)
            }
        }
    }

    // 虽然启动页文字和图标都不显示不太好看，但仍然应该吧权力交给用户，故注释相关代码
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_welcome)
        val welcomeImage = AppConfig.welcomeImage
        val welcomeImageDark = AppConfig.welcomeImageDark
        upPreferenceSummary(PreferKey.welcomeImage, welcomeImage)
        upPreferenceSummary(PreferKey.welcomeImageDark, welcomeImageDark)
//        findPreference<SwitchPreference>(PreferKey.welcomeShowText)?.let {
//            it.isEnabled = !welcomeImage.isNullOrEmpty()
//        }
//        findPreference<SwitchPreference>(PreferKey.welcomeShowIcon)?.let {
//            it.isEnabled = !welcomeImage.isNullOrEmpty()
//        }
//        findPreference<SwitchPreference>(PreferKey.welcomeShowTextDark)?.let {
//            it.isEnabled = !welcomeImageDark.isNullOrEmpty()
//        }
//        findPreference<SwitchPreference>(PreferKey.welcomeShowIconDark)?.let {
//            it.isEnabled = !welcomeImageDark.isNullOrEmpty()
//        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.welcome_style)
        listView.setEdgeEffectColor(primaryColor)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        sharedPreferences ?: return
        when (key) {
            PreferKey.welcomeImage -> {
                val welcomeImage = getPrefString(key)
                upPreferenceSummary(key, welcomeImage)
//                findPreference<SwitchPreference>(PreferKey.welcomeShowText)?.let {
//                    it.isEnabled = !welcomeImage.isNullOrEmpty()
//                }
//                findPreference<SwitchPreference>(PreferKey.welcomeShowIcon)?.let {
//                    it.isEnabled = !welcomeImage.isNullOrEmpty()
//                }
            }

            PreferKey.welcomeImageDark -> {
                val welcomeImageDark = getPrefString(key)
                upPreferenceSummary(key, welcomeImageDark)
//                findPreference<SwitchPreference>(PreferKey.welcomeShowTextDark)?.let {
//                    it.isEnabled = !welcomeImageDark.isNullOrEmpty()
//                }
//                findPreference<SwitchPreference>(PreferKey.welcomeShowIconDark)?.let {
//                    it.isEnabled = !welcomeImageDark.isNullOrEmpty()
//                }
            }
        }
    }

    @SuppressLint("PrivateResource")
    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            PreferKey.welcomeImage ->
                if (getPrefString(preference.key).isNullOrEmpty()) {
                    selectImage.launch {
                        requestCode = requestWelcomeImage
                        mode = HandleFileContract.IMAGE
                    }
                } else {
                    context?.selector(
                        items = arrayListOf(
                            getString(R.string.delete),
                            getString(R.string.select_image)
                        )
                    ) { _, i ->
                        if (i == 0) {
                            removePref(preference.key)
//                            AppConfig.welcomeShowText = true
//                            AppConfig.welcomeShowIcon = true
//                            findPreference<SwitchPreference>(PreferKey.welcomeShowText)?.let {
//                                it.isChecked = true
//                            }
//                            findPreference<SwitchPreference>(PreferKey.welcomeShowIcon)?.let {
//                                it.isChecked = true
//                            }
                            BookCover.upDefaultCover()
                        } else {
                            selectImage.launch {
                                requestCode = requestWelcomeImage
                                mode = HandleFileContract.IMAGE
                            }
                        }
                    }
                }

            PreferKey.welcomeImageDark ->
                if (getPrefString(preference.key).isNullOrEmpty()) {
                    selectImage.launch {
                        requestCode = requestWelcomeImageDark
                        mode = HandleFileContract.IMAGE
                    }
                } else {
                    context?.selector(
                        items = arrayListOf(
                            getString(R.string.delete),
                            getString(R.string.select_image)
                        )
                    ) { _, i ->
                        if (i == 0) {
                            removePref(preference.key)
//                            AppConfig.welcomeShowTextDark = true
//                            AppConfig.welcomeShowIconDark = true
//                            findPreference<SwitchPreference>(PreferKey.welcomeShowTextDark)?.let {
//                                it.isChecked = true
//                            }
//                            findPreference<SwitchPreference>(PreferKey.welcomeShowIconDark)?.let {
//                                it.isChecked = true
//                            }
                            BookCover.upDefaultCover()
                        } else {
                            selectImage.launch {
                                requestCode = requestWelcomeImageDark
                                mode = HandleFileContract.IMAGE
                            }
                        }
                    }
                }
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun upPreferenceSummary(preferenceKey: String, value: String?) {
        val preference = findPreference<Preference>(preferenceKey) ?: return
        when (preferenceKey) {
            PreferKey.welcomeImage,
            PreferKey.welcomeImageDark -> preference.summary = if (value.isNullOrBlank()) {
                getString(R.string.select_image)
            } else {
                value
            }

            else -> preference.summary = value
        }
    }

    private fun setCoverFromUri(preferenceKey: String, uri: Uri) {
        if (uri.scheme?.lowercase() in listOf("http", "https")) {
            lifecycleScope.launch {
                kotlin.runCatching {
                    appCtx.toastOnUi("下载图片中...")
                    val analyzeUrl = AnalyzeUrl(uri.toString())
                    val url = analyzeUrl.urlNoQuery
                    var file = requireContext().externalFiles
                    val res = okHttpClient.newCallResponse(0) {
                        addHeaders(analyzeUrl.headerMap)
                        url(url)
                    }
                    val contentType = res.header("Content-Type") ?: "image/jpeg"
                    val imageType = when {
                        contentType.contains("png", ignoreCase = true) -> "png"
                        contentType.contains("gif", ignoreCase = true) -> "gif"
                        contentType.contains("webp", ignoreCase = true) -> "webp"
                        else -> "jpg"
                    }
                    val suffix = if (url.contains(".9.png", true)) {
                        ".9.png"
                    } else {
                        ".$imageType"
                    }
                    val fileName = MD5Utils.md5Encode(url) + suffix
                    file = FileUtils.createFileIfNotExist(file, "covers", fileName)
                    res.body.byteStream().use { inputStream ->
                        FileOutputStream(file).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    putPrefString(preferenceKey, file.absolutePath)
                }.onSuccess {
                    appCtx.toastOnUi("设定成功")
                }.onFailure {
                    appCtx.toastOnUi(it.localizedMessage)
                }
            }
            return
        }
        readUri(uri) { fileDoc, inputStream ->
            kotlin.runCatching {
                var file = requireContext().externalFiles
                val suffix = if (fileDoc.name.contains(".9.png", true)) {
                    ".9.png"
                } else {
                    "." + fileDoc.name.substringAfterLast(".")
                }
                val fileName = uri.inputStream(requireContext()).getOrThrow().use {
                    MD5Utils.md5Encode(it) + suffix
                }
                file = FileUtils.createFileIfNotExist(file, "covers", fileName)
                FileOutputStream(file).use {
                    inputStream.copyTo(it)
                }
                putPrefString(preferenceKey, file.absolutePath)
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }

}