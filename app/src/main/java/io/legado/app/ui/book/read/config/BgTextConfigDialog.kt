package io.legado.app.ui.book.read.config

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.TooltipCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.isGone
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.DialogReadBgTextBinding
import io.legado.app.databinding.ItemBgImageBinding
import io.legado.app.help.DefaultData
import io.legado.app.help.book.isImage
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.getSecondaryTextColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.createFileReplace
import io.legado.app.utils.createFolderReplace
import io.legado.app.utils.delete
import io.legado.app.utils.dpToPx
import io.legado.app.utils.externalCache
import io.legado.app.utils.externalFiles
import io.legado.app.utils.find
import io.legado.app.utils.getFile
import io.legado.app.utils.inputStream
import io.legado.app.utils.longToast
import io.legado.app.utils.openInputStream
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.outputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.readBytes
import io.legado.app.utils.readUri
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import androidx.lifecycle.lifecycleScope
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.setSelectionSafely
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class BgTextConfigDialog : BaseDialogFragment(R.layout.dialog_read_bg_text) {

    companion object {
        const val TEXT_COLOR = 121
        const val BG_COLOR = 122
        const val TEXT_ACCENT_COLOR = 123
        const val REVIEW_ICON_COLOR = 124
        const val UNDERLINE_COLOR = 125
    }

    private val binding by viewBinding(DialogReadBgTextBinding::bind)
    private val configFileName = "readConfig.zip"
    private val adapter by lazy { BgAdapter(requireContext(), secondaryTextColor) }
    private var primaryTextColor = 0
    private var secondaryTextColor = 0
    private val importFormNet = "网络导入"
    private val selectBgImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            setBgFromUri(uri)
        }
    }
    private val selectExportDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            exportConfig(uri)
        }
    }
    private val selectImportDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.path == "/$importFormNet") {
                importNetConfigAlert()
            } else {
                importConfig(uri)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(R.color.background)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        (activity as ReadBookActivity).bottomDialog++
        initView()
        initData()
        initEvent()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        ReadBookConfig.save()
        (activity as ReadBookActivity).bottomDialog--
    }

    private fun initView() = binding.run {
        val bg = requireContext().bottomBackground
        val isLight = ColorUtils.isColorLight(bg)
        primaryTextColor = requireContext().getPrimaryTextColor(isLight)
        secondaryTextColor = requireContext().getSecondaryTextColor(isLight)
        rootView.setBackgroundColor(bg)
        tvNameTitle.setTextColor(primaryTextColor)
        tvName.setTextColor(secondaryTextColor)
        ivEdit.setColorFilter(secondaryTextColor, PorterDuff.Mode.SRC_IN)
        tvRestore.setTextColor(primaryTextColor)
        swDarkStatusIcon.setTextColor(primaryTextColor)
        ivImport.setColorFilter(primaryTextColor, PorterDuff.Mode.SRC_IN)
        ivExport.setColorFilter(primaryTextColor, PorterDuff.Mode.SRC_IN)
        ivDelete.setColorFilter(primaryTextColor, PorterDuff.Mode.SRC_IN)
        tvBgAlpha.setTextColor(primaryTextColor)
        tvBgImage.setTextColor(primaryTextColor)
        tvUnderlineColor.setTextColor(primaryTextColor)
        swUnderlineBody.setTextColor(primaryTextColor)
        swUnderlineTitle.setTextColor(primaryTextColor)
        dsbUnderlineWidth.valueFormat = { "${it / 2f}dp" }
        dsbUnderlineDistance.valueFormat = { "${(it / 2f)}dp" }
        if (ReadBook.book?.isImage == true) {
            underlineStyleRow.isGone = true
            underlineActionsRow.isGone = true
            dsbUnderlineWidth.isGone = true
            dsbUnderlineDistance.isGone = true
            spUnderline.isGone = true
        } else {
            val textStyles = arrayOf(
                getString(R.string.highlight_action_trigger_off),
                getString(R.string.highlight_underline_solid),
                getString(R.string.highlight_underline_dashed),
                getString(R.string.highlight_underline_dotted),
                getString(R.string.highlight_underline_double),
                getString(R.string.highlight_underline_wavy),
                getString(R.string.underline_double_dashed)
            )
            val adapter = object : ArrayAdapter<String>(requireContext(), R.layout.item_text_common, textStyles) {
                override fun getDropDownView(
                    position: Int,
                    convertView: View?,
                    parent: ViewGroup
                ): View {
                    val view = super.getDropDownView(position, convertView, parent)
                    if (view is android.widget.TextView) {
                        view.setBackgroundColor(bg) // 设置下拉列表项的背景色
                        view.setTextColor(primaryTextColor) // 设置下拉列表项的文本颜色
                    }
                    return view
                }
            }
            adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
            spUnderline.adapter = adapter
            spUnderline.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                var isInitializing = true
                override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                    if (isInitializing) { //忽略初始化选择
                        isInitializing = false
                        return
                    }
                    ReadBookConfig.underlineMode = position
                    postEvent(EventBus.UP_CONFIG, arrayListOf(6, 9, 11))
                }
                override fun onNothingSelected(parent: AdapterView<*>) { }
            }
        }
        recyclerView.adapter = adapter
        adapter.addHeaderView {
            ItemBgImageBinding.inflate(layoutInflater, it, false).apply {
                tvName.setTextColor(secondaryTextColor)
                tvName.text = getString(R.string.select_image)
                ivBg.setImageResource(R.drawable.ic_image)
                ivBg.setColorFilter(primaryTextColor, PorterDuff.Mode.SRC_IN)
                root.setOnClickListener {
                    selectBgImage.launch {
                        mode = HandleFileContract.IMAGE
                    }
                }
            }
        }
        requireContext().assets.list("bg")?.let {
            adapter.setItems(it.toList())
        }
    }

    @SuppressLint("InflateParams")
    private fun initData() = with(ReadBookConfig.durConfig) {
        binding.tvName.text = name.ifBlank { "文字" }
        binding.swDarkStatusIcon.isChecked = curStatusIconDark()
        binding.spUnderline.setSelectionSafely(ReadBookConfig.underlineMode)
        binding.dsbUnderlineWidth.progress =
            (ReadBookConfig.underlineWidth * 2f).roundToInt().coerceIn(0, 20)
        binding.dsbUnderlineDistance.progress =
            (ReadBookConfig.underlineDistance * 2f).roundToInt().coerceIn(0, 60)
        binding.swUnderlineBody.isChecked = ReadBookConfig.underlineBodyEnabled
        binding.swUnderlineTitle.isChecked = ReadBookConfig.underlineTitleEnabled
        binding.tvUnderlineColor.setTextColor(
            if (ReadBookConfig.underlineColorSet) {
                ReadBookConfig.underlineColor
            } else {
                ReadBookConfig.textColor
            }
        )
        binding.sbBgAlpha.progress = bgAlpha
    }

    @SuppressLint("InflateParams")
    private fun initEvent() = with(ReadBookConfig.durConfig) {
        binding.ivEdit.setOnClickListener {
            alert(R.string.style_name) {
                val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                    editView.hint = "name"
                    editView.setText(ReadBookConfig.durConfig.name)
                }
                customView { alertBinding.root }
                okButton {
                    alertBinding.editView.text?.toString()?.let {
                        binding.tvName.text = it
                        ReadBookConfig.durConfig.name = it
                    }
                }
                cancelButton()
            }
        }
        binding.tvRestore.setOnClickListener {
            val defaultConfigs = DefaultData.readConfigs
            val layoutNames = defaultConfigs.map { it.name }
            context?.selector("选择预设布局", layoutNames) { _, i ->
                if (i >= 0) {
                    ReadBookConfig.durConfig = defaultConfigs[i].copy()
                    initData()
                    postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5, 13))
                }
            }
        }
        binding.swDarkStatusIcon.setOnCheckedChangeListener { _, isChecked ->
            setCurStatusIconDark(isChecked)
            (activity as? ReadBookActivity)?.upSystemUiVisibility()
        }
        binding.tvTextColor.setOnClickListener {
            ColorPickerDialog.newBuilder()
                .setColor(curTextColor())
                .setShowAlphaSlider(false)
                .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                .setDialogId(TEXT_COLOR)
                .show(requireActivity())
        }
        binding.tvUnderlineColor.setOnClickListener {
            ColorPickerDialog.newBuilder()
                .setColor(
                    ReadBookConfig.underlineColor.takeIf { ReadBookConfig.underlineColorSet }
                        ?: ReadBookConfig.textColor
                )
                .setShowAlphaSlider(true)
                .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                .setDialogId(UNDERLINE_COLOR)
                .show(requireActivity())
        }
        binding.tvTextAccentColor.setOnClickListener {
            ColorPickerDialog.newBuilder()
                .setColor(curTextAccentColor())
                .setShowAlphaSlider(false)
                .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                .setDialogId(TEXT_ACCENT_COLOR)
                .show(requireActivity())
        }
        binding.tvReviewIconSvg.setOnClickListener {
            showReviewIconTemplates()
        }
        binding.tvReviewIconSize.setOnClickListener {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = getString(R.string.review_icon_size_hint)
                editView.inputType = InputType.TYPE_CLASS_NUMBER
                editView.setSingleLine(true)
                editView.setText(ReadBookConfig.reviewIconScale.toString())
                editView.setSelection(editView.text?.length ?: 0)
            }
            val dialog = alert(R.string.review_icon_size_title) {
                customView { alertBinding.root }
                okButton()
                cancelButton()
            }
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val scale = alertBinding.editView.text?.toString()?.trim()?.toIntOrNull()
                if (scale == null || scale !in 50..200) {
                    toastOnUi(R.string.review_icon_size_invalid)
                    return@setOnClickListener
                }
                if (scale != ReadBookConfig.reviewIconScale) {
                    ReadBookConfig.reviewIconScale = scale
                    notifyReviewIconStyleChanged()
                }
                dialog.dismiss()
            }
        }
        binding.tvReviewIconColor.setOnClickListener {
            ColorPickerDialog.newBuilder()
                .setColor(
                    ReadBookConfig.reviewIconColor.takeIf { it != 0 }
                        ?: ChapterProvider.reviewPaint.color
                )
                .setShowAlphaSlider(false)
                .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                .setDialogId(REVIEW_ICON_COLOR)
                .show(requireActivity())
        }
        binding.tvReviewIconColor.setOnLongClickListener {
            if (ReadBookConfig.reviewIconColor != 0) {
                ReadBookConfig.reviewIconColor = 0
                postEvent(EventBus.UP_CONFIG, arrayListOf(8, 9, 11))
                toastOnUi(R.string.review_icon_color_reset)
            }
            true
        }
        binding.tvBgColor.setOnClickListener {
            val bgColor =
                if (curBgType() == 0) curBgStr().toColorInt()
                else "#015A86".toColorInt()
            ColorPickerDialog.newBuilder()
                .setColor(bgColor)
                .setShowAlphaSlider(false)
                .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                .setDialogId(BG_COLOR)
                .show(requireActivity())
        }
        binding.tvBgColor.apply {
            TooltipCompat.setTooltipText(this, text)
        }
        binding.ivImport.setOnClickListener {
            selectImportDoc.launch {
                mode = HandleFileContract.FILE
                title = getString(R.string.import_str)
                allowExtensions = arrayOf("zip")
                otherActions = arrayListOf(SelectItem(importFormNet, -1))
            }
        }
        binding.ivExport.setOnClickListener {
            selectExportDir.launch {
                title = getString(R.string.export_str)
            }
        }
        binding.ivDelete.setOnClickListener {
            if (ReadBookConfig.deleteDur()) {
                postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
                dismissAllowingStateLoss()
            } else {
                toastOnUi("数量已是最少,不能删除.")
            }
        }
        binding.sbBgAlpha.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                ReadBookConfig.bgAlpha = progress
                postEvent(EventBus.UP_CONFIG, arrayListOf(3))
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                postEvent(EventBus.UP_CONFIG, arrayListOf(3))
            }
        })
        binding.dsbUnderlineWidth.onChanged = { progress ->
            ReadBookConfig.underlineWidth = progress / 2f
            postEvent(EventBus.UP_CONFIG, arrayListOf(6, 9, 11))
        }
        binding.dsbUnderlineDistance.onChanged = { progress ->
            ReadBookConfig.underlineDistance = progress / 2f
            postEvent(EventBus.UP_CONFIG, arrayListOf(6, 9, 11))
        }
        binding.swUnderlineBody.setOnUserCheckedChangeListener { checked ->
            ReadBookConfig.underlineBodyEnabled = checked
            postEvent(EventBus.UP_CONFIG, arrayListOf(6, 9, 11))
        }
        binding.swUnderlineTitle.setOnUserCheckedChangeListener { checked ->
            ReadBookConfig.underlineTitleEnabled = checked
            postEvent(EventBus.UP_CONFIG, arrayListOf(6, 9, 11))
        }
    }

    private fun showReviewIconTemplates() {
        val templates = ReadBookConfig.durConfig.reviewIconSvgTemplates
        val templateAdapter = ReviewIconSvgTemplateAdapter(
            requireContext(),
            secondaryTextColor
        ).apply {
            setItems(templates)
        }
        val visibleRows = ((templates.size.coerceAtLeast(1) + 2) / 3).coerceAtMost(2)
        val recyclerView = RecyclerView(requireContext()).apply {
            layoutManager = GridLayoutManager(requireContext(), 3)
            adapter = templateAdapter
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (visibleRows * 88).dpToPx()
            )
            setHasFixedSize(true)
        }
        lateinit var dialog: AlertDialog
        templateAdapter.setOnItemClickListener { _, template ->
            if (applyReviewIconTemplate(template)) dialog.dismiss()
        }
        templateAdapter.setOnItemLongClickListener { _, template ->
            dialog.dismiss()
            confirmDeleteReviewIconTemplate(template)
            true
        }
        dialog = alert(R.string.review_icon_templates_title) {
            customView { recyclerView }
            positiveButton(R.string.review_icon_template_save)
            neutralButton(R.string.review_icon_svg_edit)
            cancelButton()
        }
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            dialog.dismiss()
            showReviewIconTemplateNameEditor()
        }
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            dialog.dismiss()
            showReviewIconSvgEditor()
        }
    }

    private fun showReviewIconSvgEditor() {
        val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = getString(R.string.review_icon_svg_hint)
            editView.setSingleLine(false)
            editView.maxLines = 8
            editView.setText(ReadBookConfig.reviewIconSvg)
            editView.setSelection(editView.text?.length ?: 0)
        }
        val dialog = alert(R.string.review_icon_svg_title) {
            customView { alertBinding.root }
            okButton()
            cancelButton()
        }
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val newSvg = alertBinding.editView.text?.toString().orEmpty().trim()
            if (newSvg.isNotBlank() && !isValidReviewIconSvg(newSvg)) {
                toastOnUi(R.string.review_icon_svg_invalid)
                return@setOnClickListener
            }
            if (newSvg != ReadBookConfig.reviewIconSvg) {
                ReadBookConfig.reviewIconSvg = newSvg
                notifyReviewIconStyleChanged()
            }
            dialog.dismiss()
        }
    }

    private fun showReviewIconTemplateNameEditor(
        template: ReadBookConfig.ReviewIconSvgTemplate? = null
    ) {
        val svg = template?.svg?.trim() ?: ReadBookConfig.reviewIconSvg.trim()
        if (svg.isBlank() || (template == null && !isValidReviewIconSvg(svg))) {
            toastOnUi(R.string.review_icon_template_no_svg)
            return
        }
        val existing = template ?: ReadBookConfig.durConfig.reviewIconSvgTemplates
            .firstOrNull { it.svg.trim() == svg }
        val defaultName = existing?.name ?: getString(
            R.string.review_icon_template_default_name,
            ReadBookConfig.durConfig.reviewIconSvgTemplates.size + 1
        )
        val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.setHint(R.string.review_icon_template_name)
            editView.setSingleLine(true)
            editView.setText(defaultName)
            editView.setSelection(editView.text?.length ?: 0)
        }
        val dialog = alert(R.string.review_icon_template_name) {
            customView { alertBinding.root }
            okButton()
            cancelButton()
        }
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val name = alertBinding.editView.text?.toString().orEmpty().trim()
            if (name.isEmpty()) {
                toastOnUi(R.string.review_icon_template_name_empty)
                return@setOnClickListener
            }
            ReadBookConfig.durConfig.putReviewIconSvgTemplate(name, svg)
            toastOnUi(
                if (template == null) R.string.review_icon_template_saved
                else R.string.review_icon_template_renamed
            )
            dialog.dismiss()
            showReviewIconTemplates()
        }
    }

    private fun applyReviewIconTemplate(
        template: ReadBookConfig.ReviewIconSvgTemplate
    ): Boolean {
        val svg = template.svg.trim()
        if (!isValidReviewIconSvg(svg)) {
            toastOnUi(R.string.review_icon_svg_invalid)
            return false
        }
        if (svg != ReadBookConfig.reviewIconSvg) {
            ReadBookConfig.reviewIconSvg = svg
            notifyReviewIconStyleChanged()
        }
        return true
    }

    private fun confirmDeleteReviewIconTemplate(
        template: ReadBookConfig.ReviewIconSvgTemplate
    ) {
        val name = template.name.ifBlank { getString(R.string.review_icon_template_unnamed) }
        alert(name, getString(R.string.sure_del)) {
            yesButton { dialog ->
                dialog.dismiss()
                ReadBookConfig.durConfig.removeReviewIconSvgTemplate(template.svg)
                toastOnUi(R.string.review_icon_template_deleted)
                showReviewIconTemplates()
            }
            neutralButton(R.string.edit) { dialog ->
                dialog.dismiss()
                showReviewIconTemplateNameEditor(template)
            }
            noButton()
        }
    }

    private fun notifyReviewIconStyleChanged() {
        ChapterProvider.clearReviewIconCache()
        ChapterProvider.refreshReviewColumnsForStyleChange()
        postEvent(EventBus.UP_CONFIG, arrayListOf(9, 11))
    }

    private fun isValidReviewIconSvg(svg: String): Boolean {
        val resolvedSvg = svg.replace("{{count}}", "88")
        val aspectRatio = SvgUtils.getAspectRatioFromSvgText(resolvedSvg)
            ?: return false
        if (!ChapterProvider.isReviewIconAspectRatioSupported(aspectRatio)) return false
        val bitmap = SvgUtils.createBitmapFromSvgText(resolvedSvg, 48, 48)
        return bitmap?.let {
            it.recycle()
            true
        } ?: false
    }

    private fun exportConfig(uri: Uri) {
        val exportFileName = if (ReadBookConfig.config.name.isBlank()) {
            configFileName
        } else {
            "${ReadBookConfig.config.name}.zip"
        }
        execute {
            val exportFiles = arrayListOf<File>()
            val configDir = requireContext().externalCache.getFile("readConfig")
            configDir.createFolderReplace()
            val configFile = configDir.getFile("readConfig.json")
            configFile.createFileReplace()
            val config = ReadBookConfig.getExportConfig()
            fun exportFont(fontPath: String, archiveName: String? = null): String? {
                if (fontPath.isEmpty()) return null
                val fontDoc = FileDoc.fromFile(fontPath)
                val fontName = archiveName ?: fontDoc.name
                val fontInputStream = fontDoc.openInputStream().getOrNull()
                return fontInputStream?.use {
                    val fontExportFile = FileUtils.createFileIfNotExist(configDir, fontName)
                    fontExportFile.outputStream().use { out ->
                        it.copyTo(out)
                    }
                    exportFiles.add(fontExportFile)
                    fontName
                }
            }

            val textFontPath = ReadBookConfig.textFont
            val exportedTextFont = exportFont(textFontPath)
            config.textFont = exportedTextFont.orEmpty()

            val titleFontPath = ReadBookConfig.titleFont
            config.titleFont = if (titleFontPath == textFontPath && exportedTextFont != null) {
                exportedTextFont
            } else if (titleFontPath.isNotEmpty()) {
                val titleFontName = FileDoc.fromFile(titleFontPath).name.let {
                    if (it == exportedTextFont) "title_$it" else it
                }
                exportFont(titleFontPath, titleFontName).orEmpty()
            } else ""
            configFile.writeText(GSON.toJson(config))
            exportFiles.add(configFile)
            repeat(3) {
                val path = ReadBookConfig.durConfig.getBgPath(it) ?: return@repeat
                val bgExportFile = copyBgImage(path, configDir) ?: return@repeat
                exportFiles.add(bgExportFile)
            }
            val configZipPath = FileUtils.getPath(requireContext().externalCache, configFileName)
            if (ZipUtils.zipFiles(exportFiles, File(configZipPath))) {
                val exportDir = FileDoc.fromDir(uri)
                exportDir.find(exportFileName)?.delete()
                val exportFileDoc = exportDir.createFileIfNotExist(exportFileName)
                exportFileDoc.openOutputStream().getOrThrow().use { out ->
                    File(configZipPath).inputStream().use {
                        it.copyTo(out)
                    }
                }
            }
        }.onSuccess {
            toastOnUi("导出成功, 文件名为 $exportFileName")
        }.onError {
            it.printOnDebug()
            AppLog.put("导出失败:${it.localizedMessage}", it)
            longToast("导出失败:${it.localizedMessage}")
        }
    }

    private fun copyBgImage(path: String, configDir: File): File? {
        val bgName = FileUtils.getName(path)
        val bgFile = File(path)
        if (bgFile.exists()) {
            val bgExportFile = File(FileUtils.getPath(configDir, bgName))
            if (!bgExportFile.exists()) {
                bgFile.copyTo(bgExportFile)
                return bgExportFile
            }
        }
        return null
    }

    @SuppressLint("InflateParams")
    private fun importNetConfigAlert() {
        alert("输入地址") {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater)
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let { url ->
                    importNetConfig(url)
                }
            }
            cancelButton()
        }
    }

    private fun importNetConfig(url: String) {
        execute {
            okHttpClient.newCallResponseBody {
                url(url)
            }.bytes().let {
                importConfig(it)
            }
        }.onError {
            longToast(it.stackTraceStr)
        }
    }

    private fun importConfig(uri: Uri) {
        execute {
            importConfig(uri.readBytes(requireContext()))
        }.onError {
            it.printOnDebug()
            longToast("导入失败:${it.localizedMessage}")
        }
    }

    private fun importConfig(byteArray: ByteArray) {
        execute {
            ReadBookConfig.import(byteArray)
        }.onSuccess {
            ReadBookConfig.durConfig = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
            toastOnUi("导入成功")
        }.onError {
            it.printOnDebug()
            longToast("导入失败:${it.localizedMessage}")
        }
    }

    private fun setBgFromUri(uri: Uri) {
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
                    file = FileUtils.createFileIfNotExist(file, "bg", fileName)
                    res.body.byteStream().use { inputStream ->
                        FileOutputStream(file).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    ReadBookConfig.durConfig.setCurBg(2, fileName)
                    postEvent(EventBus.UP_CONFIG, arrayListOf(1))
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
                file = FileUtils.createFileIfNotExist(file, "bg", fileName)
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
                ReadBookConfig.durConfig.setCurBg(2, fileName)
                postEvent(EventBus.UP_CONFIG, arrayListOf(1))
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }
}
