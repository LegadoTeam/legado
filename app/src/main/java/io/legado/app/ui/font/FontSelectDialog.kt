package io.legado.app.ui.font

import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogFontSelectBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.RealPathUtil
import io.legado.app.utils.applyTint
import io.legado.app.utils.cnCompare
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.list
import io.legado.app.utils.listFileDocs
import io.legado.app.utils.openInputStream
import io.legado.app.utils.putPrefString
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch
import java.io.File

/**
 * 字体选择对话框
 */
class FontSelectDialog : BaseDialogFragment(R.layout.dialog_font_select),
    Toolbar.OnMenuItemClickListener,
    FontAdapter.CallBack {
    private val fontRegex = Regex("(?i).*\\.[ot]tf")
    private val binding by viewBinding(DialogFontSelectBinding::bind)
    private val adapter by lazy {
        val curFontPath = callBack?.curFontPath ?: ""
        FontAdapter(requireContext(), curFontPath, this)
    }
    private val selectFontDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.isContentScheme()) {
                putPrefString(PreferKey.fontFolder, uri.toString())
                val doc = DocumentFile.fromTreeUri(requireContext(), uri)
                if (doc != null) {
                    loadFontFiles(FileDoc.fromDocumentFile(doc))
                } else {
                    RealPathUtil.getPath(requireContext(), uri)?.let { path ->
                        loadFontFilesByPermission(path)
                    }
                }
            } else {
                uri.path?.let { path ->
                    putPrefString(PreferKey.fontFolder, path)
                    loadFontFilesByPermission(path)
                }
            }
        }
    }
    private val importFont = registerForActivityResult(HandleFileContract()) {
        it.uri?.let(::importFont)
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.setTitle(R.string.select_font)
        binding.toolBar.inflateMenu(R.menu.font_select)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener(this)
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter

        loadFonts()
    }

    private fun loadFonts() {
        val fontPath = getPrefString(PreferKey.fontFolder)
        if (fontPath.isNullOrEmpty()) {
            loadLocalFonts(openFolderWhenEmpty = true)
        } else {
            if (fontPath.isContentScheme()) {
                val doc = DocumentFile.fromTreeUri(requireContext(), Uri.parse(fontPath))
                if (doc?.canRead() == true) {
                    loadFontFiles(FileDoc.fromDocumentFile(doc))
                } else {
                    loadLocalFonts(openFolderWhenEmpty = true)
                }
            } else {
                loadFontFilesByPermission(fontPath)
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_default -> {
                if (!shouldSelectSystemTypeface(callBack)) {
                    onDefaultFontChange()
                    dismissAllowingStateLoss()
                    return true
                }
                val requireContext = requireContext()
                alert(titleResource = R.string.system_typeface) {
                    items(
                        requireContext.resources.getStringArray(R.array.system_typefaces).toList()
                    ) { _, i ->
                        AppConfig.systemTypefaces = i
                        onDefaultFontChange()
                        dismissAllowingStateLoss()
                    }
                }
            }
            R.id.menu_other -> {
                openFolder()
            }
            R.id.menu_import -> {
                importFont.launch {
                    mode = HandleFileContract.FILE
                    title = getString(R.string.import_str)
                }
            }
        }
        return true
    }

    private fun importFont(uri: Uri) {
        val directory = getLocalFontDirectory()
        execute {
            val source = FileDoc.fromUri(uri, false)
            source.openInputStream().getOrThrow().use { input ->
                installFontFile(input, source.name, directory, ::isValidFont)
            }
        }.onSuccess {
            toastOnUi(R.string.success)
            loadFonts()
        }.onError {
            AppLog.put("导入字体失败\n${it.localizedMessage}", it)
            toastOnUi(
                if (it is IllegalArgumentException) R.string.wrong_format
                else R.string.error_read_file
            )
        }
    }

    private fun isValidFont(file: File): Boolean = kotlin.runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Typeface.Builder(file).build() != null
        } else {
            Typeface.createFromFile(file) != Typeface.DEFAULT
        }
    }.getOrDefault(false)

    private fun openFolder() {
        lifecycleScope.launch {
            val defaultPath = "SD${File.separator}Fonts"
            selectFontDir.launch {
                otherActions = arrayListOf(SelectItem(defaultPath, -1))
            }
        }
    }

    private fun loadLocalFonts(openFolderWhenEmpty: Boolean = false) {
        execute {
            mergeFontItems(arrayListOf(), getLocalFonts())
        }.onSuccess {
            if (it.isNotEmpty()) {
                adapter.setItems(it)
            } else if (openFolderWhenEmpty) {
                openFolder()
            }
        }
    }

    private fun getLocalFonts(): ArrayList<FileDoc> {
        return getLocalFontDirectory().listFileDocs {
            it.name.matches(fontRegex)
        }
    }

    private fun getLocalFontDirectory(): File {
        return File(FileUtils.getPath(requireContext().externalFiles, "font"))
    }

    private fun loadFontFilesByPermission(path: String) {
        PermissionsCompat.Builder()
            .addPermissions(*Permissions.Group.STORAGE)
            .rationale(R.string.tip_perm_request_storage)
            .onGranted {
                loadFontFiles(
                    FileDoc.fromFile(File(path))
                )
            }.onDenied {
                loadLocalFonts()
            }
            .request()
    }

    private fun loadFontFiles(fileDoc: FileDoc) {
        execute {
            val fontItems = fileDoc.list {
                it.name.matches(fontRegex)
            } ?: ArrayList()
            mergeFontItems(fontItems, getLocalFonts())
        }.onSuccess {
            adapter.setItems(it)
        }.onError {
            AppLog.put("加载字体文件失败\n${it.localizedMessage}", it)
            toastOnUi("getFontFiles:${it.localizedMessage}")
            loadLocalFonts()
        }
    }

    private fun mergeFontItems(
        items1: ArrayList<FileDoc>,
        items2: ArrayList<FileDoc>
    ): List<FontItem> {
        val paths = hashSetOf<String>()
        val items = ArrayList<FontItem>()
        items1.forEach { item ->
            if (paths.add(item.toString())) items.add(FontItem(item, privateFolder = false))
        }
        items2.forEach { item ->
            if (paths.add(item.toString())) items.add(FontItem(item, privateFolder = true))
        }
        return items.sortedWith { o1, o2 ->
            o1.doc.name.cnCompare(o2.doc.name).takeIf { it != 0 }
                ?: o1.privateFolder.compareTo(o2.privateFolder)
        }
    }

    override fun onFontSelect(docItem: FileDoc) {
        callBack?.selectFont(docItem.toString())
        dismissAllowingStateLoss()
    }

    private fun onDefaultFontChange() {
        callBack?.selectFont("")
    }

    private val callBack: CallBack?
        get() = (parentFragment as? CallBack) ?: (activity as? CallBack)

    interface CallBack {
        fun selectFont(path: String)
        val curFontPath: String
        val selectSystemTypefaceOnDefault: Boolean get() = true
    }

    companion object {
        internal fun shouldSelectSystemTypeface(callBack: CallBack?): Boolean {
            return callBack?.selectSystemTypefaceOnDefault != false
        }
    }
}
