package io.legado.app.ui.about

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogUpdateBinding
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.update.AppUpdate
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.Download
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.openUrl
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class UpdateDialog() : BaseDialogFragment(R.layout.dialog_update) {

    constructor(updateInfo: AppUpdate.UpdateInfo) : this() {
        arguments = Bundle().apply {
            putString("newVersion", updateInfo.tagName)
            putString("updateBody", updateInfo.updateLog)
            putString("url", updateInfo.downloadUrl)
            putString("name", updateInfo.fileName)
            putString("backupUrl", updateInfo.backupDownloadUrl)
            putString("mirrorUrl", updateInfo.mirrorDownloadUrl)
            putString("alternateMirrorUrl", updateInfo.alternateMirrorDownloadUrl)
            putLong("size", updateInfo.size)
            putLong("createdAt", updateInfo.createdAt)
            putBoolean("isBeta", updateInfo.isBeta)
        }
    }

    val binding by viewBinding(DialogUpdateBinding::bind)

    private val isBetaUpdate: Boolean
        get() = arguments?.getBoolean("isBeta") == true

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.8f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.title = arguments?.getString("newVersion")
        binding.toolBar.subtitle = formatUpdateMetadata(
            size = arguments?.getLong("size") ?: 0L,
            createdAt = arguments?.getLong("createdAt") ?: 0L
        ).takeIf(String::isNotBlank)
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
        binding.betaActions.isVisible = true
        (binding.textView.layoutParams as LinearLayout.LayoutParams).apply {
            height = 0
            weight = 1f
        }
        binding.btnBetaCancel.setOnClickListener { dismiss() }
        binding.btnBetaUpdate.setText(
            if (isBetaUpdate) R.string.beta_update_now else R.string.action_download
        )
        binding.btnBetaUpdate.setOnClickListener {
            val url = arguments?.getString("url")
            if (isBetaUpdate) {
                url?.takeIf(String::isNotBlank)?.let { requireContext().openUrl(it) }
                dismiss()
            } else {
                startDownload(url)
            }
        }
        if (!isBetaUpdate) {
            binding.toolBar.inflateMenu(R.menu.app_update)
            binding.toolBar.menu.findItem(R.id.menu_download).isVisible = false
            binding.toolBar.menu.findItem(R.id.menu_download_backup).isVisible =
                !arguments?.getString("backupUrl").isNullOrBlank()
            binding.toolBar.menu.findItem(R.id.menu_download_mirror).isVisible =
                !arguments?.getString("mirrorUrl").isNullOrBlank()
            binding.toolBar.menu.findItem(R.id.menu_download_alternate_mirror).isVisible =
                !arguments?.getString("alternateMirrorUrl").isNullOrBlank()
            binding.toolBar.setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.menu_download -> startDownload(arguments?.getString("url"))
                    R.id.menu_download_backup -> startDownload(arguments?.getString("backupUrl"))
                    R.id.menu_download_mirror -> startDownload(arguments?.getString("mirrorUrl"))
                    R.id.menu_download_alternate_mirror ->
                        startDownload(arguments?.getString("alternateMirrorUrl"))
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
    }

    private fun formatUpdateMetadata(size: Long, createdAt: Long): String {
        val metadata = mutableListOf<String>()
        if (size > 0) metadata += ConvertUtils.formatFileSize(size)
        if (createdAt > 0) {
            metadata += Instant.ofEpochMilli(createdAt)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_LOCAL_DATE)
        }
        return metadata.joinToString(" · ")
    }

    private fun startDownload(url: String?) {
        val name = arguments?.getString("name")
        if (url.isNullOrBlank() || name.isNullOrBlank()) return
        Download.start(requireContext(), url, name, isAppUpdate = true)
        toastOnUi(R.string.download_start)
        dismiss()
    }

}
