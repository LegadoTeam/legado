package io.legado.app.ui.association

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.core.os.postDelayed
import androidx.fragment.app.DialogFragment
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityTranslucenceBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.autoTask.ImportAutoTaskDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.init.appCtx
import java.io.File

class FileAssociationActivity :
    VMBaseActivity<ActivityTranslucenceBinding, FileAssociationViewModel>() {

    private val localBookTreeSelect = registerForActivityResult(HandleFileContract()) {
        val destination = it.uri ?: privateBookDirectory()
        if (it.uri != null) AppConfig.defaultBookTreeUri = destination.toString()
        viewModel.importLocalBooks(destination)
    }
    override val binding by viewBinding(ActivityTranslucenceBinding::inflate)

    override val viewModel by viewModels<FileAssociationViewModel>()

    private val handler by lazy {
        buildMainHandler()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.rotateLoading.visible()
        viewModel.localBookBatch.observe(this) {
            binding.rotateLoading.gone()
            if (supportFragmentManager.findFragmentByTag("sharedLocalBooks") == null) {
                ImportLocalBookDialog().show(supportFragmentManager, "sharedLocalBooks")
            }
        }
        viewModel.localBookDestination.observe(this) { requested ->
            if (requested) chooseBookDirectory()
        }
        viewModel.importingLocalBooks.observe(this) { importing ->
            if (viewModel.localBookBatch.value.isNullOrEmpty()) {
                if (importing) binding.rotateLoading.visible() else binding.rotateLoading.gone()
            }
        }
        viewModel.importedLocalBooks.observe(this) { if (it) finish() }
        viewModel.onLineImportLive.observe(this) {
            binding.rotateLoading.gone()
            startActivity<OnLineImportActivity> {
                data = it
            }
            finish()
        }
        viewModel.successLive.observe(this) {
            binding.rotateLoading.gone()
            if (supportFragmentManager.fragments.any { fragment -> fragment is DialogFragment }) {
                return@observe
            }
            when (it.first) {
                "bookSource" -> showDialogFragment(ImportBookSourceDialog(it.second, true))
                "rssSource" -> showDialogFragment(ImportRssSourceDialog(it.second, true))
                "replaceRule" -> showDialogFragment(ImportReplaceRuleDialog(it.second, true))
                "highlightRule" -> showImportHighlightRuleDialog(it.second, true)
                "httpTts" -> showDialogFragment(ImportHttpTtsDialog(it.second, true))
                "theme" -> showDialogFragment(ImportThemeDialog(it.second, true))
                "txtRule" -> showDialogFragment(ImportTxtTocRuleDialog(it.second, true))
                "dictRule" -> showDialogFragment(ImportDictRuleDialog(it.second, true))
                "autoTask" -> showDialogFragment(ImportAutoTaskDialog(it.second, true))
                "bookshelf", "backup" -> showDialogFragment(ImportDataDialog(it.first, it.second))
            }
        }
        viewModel.errorLive.observe(this) {
            binding.rotateLoading.gone()
            toastOnUi(it)
            handler.postDelayed(2000) {
                finish()
            }
        }
        viewModel.openBookLiveData.observe(this) {
            binding.rotateLoading.gone()
            startActivityForBook(it)
            finish()
        }
        viewModel.notSupportedLiveData.observe(this) { data ->
            binding.rotateLoading.gone()
            alert(
                title = appCtx.getString(R.string.draw),
                message = appCtx.getString(R.string.file_not_supported, data.second)
            ) {
                yesButton {
                    viewModel.importBook(data.first)
                }
                noButton {
                    finish()
                }
                onCancelled {
                    finish()
                }
            }
        }
        if (viewModel.shouldDispatchInitialIntent()) {
            dispatchIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        toastOnUi(R.string.importing)
    }

    private fun dispatchIntent(intent: Intent) {
        binding.rotateLoading.visible()
        when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE -> viewModel.dispatchSharedUris(
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            )
            Intent.ACTION_SEND -> if (isSupportedSharedImportMimeType(intent.type)) {
                dispatchSharedIntent(intent)
            } else {
                viewModel.reportInvalidSharedContent()
            }

            Intent.ACTION_VIEW -> intent.data?.let { data ->
                dispatchUri(data) { viewModel.dispatchIntent(data) }
            } ?: finish()

            else -> viewModel.reportInvalidSharedContent()
        }
    }

    private fun dispatchSharedIntent(intent: Intent) {
        val uri = IntentCompat.getParcelableExtra(
            intent,
            Intent.EXTRA_STREAM,
            Uri::class.java
        )
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        when {
            uri != null -> viewModel.dispatchSharedUri(uri)

            !text.isNullOrBlank() -> viewModel.dispatchSharedText(text)

            else -> viewModel.reportInvalidSharedContent()
        }
    }

    private fun dispatchUri(uri: Uri, block: () -> Unit) {
        if (uri.isContentScheme()) {
            block()
        } else {
            PermissionsCompat.Builder()
                .addPermissions(*Permissions.Group.STORAGE)
                .rationale(R.string.tip_perm_request_storage)
                .onGranted { block() }
                .onDenied {
                    binding.rotateLoading.gone()
                    toastOnUi("请求存储权限失败。")
                    handler.postDelayed(2000) {
                        finish()
                    }
                }.request()
        }
    }

    private fun privateBookDirectory(): Uri = Uri.fromFile(File(filesDir, "books"))

    private fun chooseBookDirectory() {
        val configured = AppConfig.defaultBookTreeUri
        if (!configured.isNullOrBlank()) {
            viewModel.importLocalBooks(Uri.parse(configured))
            return
        }
        binding.rotateLoading.gone()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.select_book_folder)
            .setMessage(R.string.shared_local_books_storage)
            .setPositiveButton(R.string.select_folder) { _, _ ->
                localBookTreeSelect.launch {
                    title = getString(R.string.select_book_folder)
                    mode = HandleFileContract.DIR_SYS
                }
            }
            .setNegativeButton(R.string.shared_local_books_private) { _, _ ->
                viewModel.importLocalBooks(privateBookDirectory())
            }
            .setOnCancelListener { viewModel.importLocalBooks(privateBookDirectory()) }
            .show()
    }
}

internal fun isSupportedSharedImportMimeType(mimeType: String?): Boolean =
    mimeType.equals("text/plain", ignoreCase = true) ||
        mimeType.equals("text/*", ignoreCase = true) ||
        mimeType.equals("application/json", ignoreCase = true) ||
        mimeType.equals("application/epub+zip", ignoreCase = true) ||
        mimeType.equals("application/pdf", ignoreCase = true) ||
        mimeType.equals("application/zip", ignoreCase = true) ||
        mimeType.equals("application/x-zip-compressed", ignoreCase = true) ||
        mimeType.equals("application/x-rar-compressed", ignoreCase = true) ||
        mimeType.equals("application/vnd.rar", ignoreCase = true) ||
        mimeType.equals("application/x-7z-compressed", ignoreCase = true) ||
        mimeType.equals("application/mobi", ignoreCase = true) ||
        mimeType.equals("application/x-mobipocket-ebook", ignoreCase = true) ||
        mimeType.equals("application/azw", ignoreCase = true) ||
        mimeType.equals("application/azw3", ignoreCase = true) ||
        mimeType.equals("application/x-mobi8-ebook", ignoreCase = true) ||
        mimeType.equals("application/octet-stream", ignoreCase = true)
