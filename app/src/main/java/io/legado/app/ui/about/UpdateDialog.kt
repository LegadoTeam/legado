package io.legado.app.ui.about

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogUpdateBinding
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.update.AppUpdate
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.Download
import io.legado.app.utils.openUrl
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin

class UpdateDialog() : BaseDialogFragment(R.layout.dialog_update) {

    constructor(updateInfo: AppUpdate.UpdateInfo) : this() {
        arguments = Bundle().apply {
            putString("newVersion", updateInfo.tagName)
            putString("updateBody", updateInfo.updateLog)
            putString("url", updateInfo.downloadUrl)
            putString("name", updateInfo.fileName)
            putString("backupUrl", updateInfo.backupDownloadUrl)
        }
    }

    val binding by viewBinding(DialogUpdateBinding::bind)

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.title = arguments?.getString("newVersion")
        val updateBody = arguments?.getString("updateBody")
        if (updateBody == null) {
            toastOnUi("没有数据")
            dismiss()
            return
        }
        binding.textView.post {
            Markwon.builder(requireContext())
                .usePlugin(GlideImagesPlugin.create(requireContext()))
                .usePlugin(HtmlPlugin.create())
                .usePlugin(TablePlugin.create(requireContext()))
                .build()
                .setMarkdown(binding.textView, updateBody)
        }
        binding.toolBar.inflateMenu(R.menu.app_update)
        binding.toolBar.menu.findItem(R.id.menu_download_backup).isVisible =
            !arguments?.getString("backupUrl").isNullOrBlank()
        binding.toolBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_download -> startDownload(arguments?.getString("url"))
                R.id.menu_download_backup -> startDownload(arguments?.getString("backupUrl"))
                R.id.menu_open_in_browser -> arguments?.getString("backupUrl").orEmpty()
                    .ifBlank { arguments?.getString("url").orEmpty() }
                    .takeIf(String::isNotBlank)
                    ?.let { url -> requireContext().openUrl(url) }
                R.id.menu_ignore_version -> {
                    LocalConfig.ignoreUpdateVersion = arguments?.getString("newVersion")
                    toastOnUi(R.string.ignore_this_version)
                    dismiss()
                }
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun startDownload(url: String?) {
        val name = arguments?.getString("name")
        if (url.isNullOrBlank() || name.isNullOrBlank()) return
        Download.start(requireContext(), url, name)
        toastOnUi(R.string.download_start)
        dismiss()
    }

}
