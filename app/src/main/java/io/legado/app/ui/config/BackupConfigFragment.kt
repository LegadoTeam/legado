package io.legado.app.ui.config

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.core.view.MenuProvider
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogAutoBackupBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.DialogLanBackupSendBinding
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.ImportOldData
import io.legado.app.help.storage.LanBackupSession
import io.legado.app.help.storage.LanBackupTransfer
import io.legado.app.help.storage.Restore
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.FileDoc
import io.legado.app.utils.QRCodeUtils
import io.legado.app.utils.applyTint
import io.legado.app.utils.checkWrite
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.launch
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.toEditable
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

class BackupConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener,
    MenuProvider {

    private val viewModel by activityViewModels<ConfigViewModel>()
    private val waitDialog by lazy { WaitDialog(requireContext()) }
    private var backupJob: Job? = null
    private var manualBackupUploadWebDav = true
    private var restoreJob: Job? = null
    private var lanBackupJob: Job? = null
    private var lanBackupSession: LanBackupSession? = null
    private var lanBackupDialog: AlertDialog? = null

    private val selectBackupPath = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.isContentScheme()) {
                AppConfig.backupPath = uri.toString()
            } else {
                AppConfig.backupPath = uri.path
            }
        }
    }
    private val backupDir = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            if (uri.isContentScheme()) {
                AppConfig.backupPath = uri.toString()
                backup(uri.toString(), manualBackupUploadWebDav)
            } else {
                uri.path?.let { path ->
                    AppConfig.backupPath = path
                    backup(path, manualBackupUploadWebDav)
                }
            }
        }
    }
    private val restoreDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            waitDialog.setText("恢复中…")
            waitDialog.show()
            val task = Coroutine.async {
                Restore.restore(appCtx, uri)
            }.onFinally {
                waitDialog.dismiss()
            }
            waitDialog.setOnCancelListener {
                task.cancel()
            }
        }
    }
    private val restoreOld = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ImportOldData.importUri(appCtx, uri)
        }
    }
    private val lanBackupQr = registerForActivityResult(QrCodeResult()) {
        it?.let(::confirmLanBackupReceive)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        manualBackupUploadWebDav = savedInstanceState?.getBoolean("manualBackupUploadWebDav", true) ?: true
        addPreferencesFromResource(R.xml.pref_config_backup)
        findPreference<EditTextPreference>(PreferKey.webDavPassword)?.let {
            it.setOnBindEditTextListener { editText ->
                editText.inputType =
                    InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT
                editText.setSelection(editText.text.length)
            }
        }
        findPreference<EditTextPreference>(PreferKey.webDavDir)?.let {
            it.setOnBindEditTextListener { editText ->
                editText.text = AppConfig.webDavDir?.toEditable()
                editText.setSelection(editText.text.length)
            }
        }
        findPreference<EditTextPreference>(PreferKey.webDavDeviceName)?.let {
            it.setOnBindEditTextListener { editText ->
                editText.text = AppConfig.webDavDeviceName?.toEditable()
                editText.setSelection(editText.text.length)
            }
        }
        upPreferenceSummary(PreferKey.webDavUrl, getPrefString(PreferKey.webDavUrl))
        upPreferenceSummary(PreferKey.webDavAccount, getPrefString(PreferKey.webDavAccount))
        upPreferenceSummary(PreferKey.webDavPassword, getPrefString(PreferKey.webDavPassword))
        upPreferenceSummary(PreferKey.webDavDir, AppConfig.webDavDir)
        upPreferenceSummary(PreferKey.webDavDeviceName, AppConfig.webDavDeviceName)
        upPreferenceSummary(PreferKey.backupPath, getPrefString(PreferKey.backupPath))
        updateAutoBackupSummary()
        findPreference<io.legado.app.lib.prefs.Preference>("web_dav_restore")
            ?.onLongClick {
                restoreFromLocal()
                true
            }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("manualBackupUploadWebDav", manualBackupUploadWebDav)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.backup_restore)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        listView.setEdgeEffectColor(primaryColor)
        activity?.addMenuProvider(this, viewLifecycleOwner)
        if (!LocalConfig.backupHelpVersionIsLast) {
            showHelp("webDavHelp")
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.backup_restore, menu)
        menu.applyTint(requireContext())
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.menu_help -> {
                showHelp("webDavHelp")
                return true
            }

            R.id.menu_import_old -> {
                restoreOld.launch()
                return true
            }

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.autoBackup, PreferKey.autoBackupWebDav, PreferKey.autoBackupIntervalDays -> updateAutoBackupSummary()
            PreferKey.backupPath -> upPreferenceSummary(key, getPrefString(key))
            PreferKey.webDavUrl,
            PreferKey.webDavAccount,
            PreferKey.webDavPassword,
            PreferKey.webDavDir -> listView.post {
                upPreferenceSummary(key, appCtx.getPrefString(key))
                viewModel.upWebDavConfig()
            }

            PreferKey.webDavDeviceName -> upPreferenceSummary(key, getPrefString(key))
        }
    }

    private fun upPreferenceSummary(preferenceKey: String, value: String?) {
        val preference = findPreference<Preference>(preferenceKey) ?: return
        when (preferenceKey) {
            PreferKey.backupPath -> preference.summary =
                value?.takeIf { it.isNotBlank() } ?: defaultBackupPathSummary()

            PreferKey.webDavUrl ->
                if (value.isNullOrBlank()) {
                    preference.summary = getString(R.string.web_dav_url_s)
                } else {
                    preference.summary = value
                }

            PreferKey.webDavAccount ->
                if (value.isNullOrBlank()) {
                    preference.summary = getString(R.string.web_dav_account_s)
                } else {
                    preference.summary = value
                }

            PreferKey.webDavPassword ->
                if (value.isNullOrEmpty()) {
                    preference.summary = getString(R.string.web_dav_pw_s)
                } else {
                    preference.summary = "*".repeat(value.length)
                }

            PreferKey.webDavDir -> preference.summary = when (value) {
                null -> "legado"
                else -> value
            }

            else -> {
                if (preference is ListPreference) {
                    val index = preference.findIndexOfValue(value)
                    // Set the summary to reflect the new value.
                    preference.summary = if (index >= 0) preference.entries[index] else null
                } else {
                    preference.summary = value
                }
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            PreferKey.backupPath -> showBackupPathSelector()
            PreferKey.backupContent -> backupContent()
            PreferKey.restoreIgnore -> backupIgnore()
            "web_dav_backup" -> backup()
            "localPassword" -> alertLocalPassword()
            PreferKey.autoBackup -> configureAutoBackup()
            "web_dav_restore" -> restore()
            "lan_backup_transfer" -> lanBackupTransfer()
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun defaultBackupPathSummary() =
        "${getString(R.string.default_path)}\n${requireContext().externalFiles.absolutePath}"

    private fun updateAutoBackupSummary() {
        findPreference<Preference>(PreferKey.autoBackup)?.summary = if (AppConfig.autoBackup) {
            getString(R.string.auto_backup_destination_summary,
                getString(if (AppConfig.autoBackupWebDav) R.string.backup_local_webdav else R.string.backup_local_only),
                AppConfig.autoBackupIntervalDays)
        } else getString(R.string.auto_backup_disabled)
    }

    private fun configureAutoBackup() {
        val binding = DialogAutoBackupBinding.inflate(layoutInflater)
        binding.enabled.isChecked = AppConfig.autoBackup
        binding.destination.check(if (AppConfig.autoBackupWebDav) R.id.local_webdav else R.id.local_only)
        binding.intervalDays.setText(AppConfig.autoBackupIntervalDays.toString())
        binding.intervalDays.doAfterTextChanged { binding.intervalDays.error = null }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.auto_backup_t)
            .setView(binding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok, null)
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val days = binding.intervalDays.text.toString().toIntOrNull()
                        if (days == null || days < 1) {
                            binding.intervalDays.error = getString(R.string.auto_backup_interval_invalid)
                            return@setOnClickListener
                        }
                        requireContext().defaultSharedPreferences.edit()
                            .putBoolean(PreferKey.autoBackup, binding.enabled.isChecked)
                            .putBoolean(PreferKey.autoBackupWebDav, binding.destination.checkedRadioButtonId == R.id.local_webdav)
                            .putInt(PreferKey.autoBackupIntervalDays, days).apply()
                        dismiss()
                    }
                }
                show()
            }
    }

    private fun alertLocalPassword() {
        alert(R.string.set_local_password, R.string.set_local_password_summary) {
            val binding = DialogEditTextBinding.inflate(layoutInflater).apply { editView.hint = "password" }
            customView { binding.root }
            okButton { LocalConfig.password = binding.editView.text.toString() }
            cancelButton()
        }
    }

    private fun showBackupPathSelector() {
        requireContext().selector(
            titleSource = R.string.backup_path,
            items = listOf(
                defaultBackupPathSummary(),
                getString(R.string.select_folder),
            ),
        ) { _, index ->
            when (index) {
                0 -> AppConfig.backupPath = null
                1 -> selectBackupPath.launch()
            }
        }
    }

    private fun lanBackupTransfer() {
        requireContext().selector(
            titleSource = R.string.lan_backup_transfer,
            items = listOf(
                getString(R.string.lan_backup_send),
                getString(R.string.lan_backup_receive),
            ),
        ) { _, index ->
            when (index) {
                0 -> confirmLanBackupSend()
                1 -> lanBackupQr.launch()
            }
        }
    }

    private fun confirmLanBackupSend() {
        alert(
            titleResource = R.string.lan_backup_send,
            messageResource = R.string.lan_backup_send_confirm,
        ) {
            okButton { sendLanBackup() }
            cancelButton()
        }
    }

    private fun sendLanBackup() {
        lanBackupDialog?.dismiss()
        lanBackupSession?.close()
        lanBackupDialog = null
        lanBackupSession = null
        lanBackupJob?.cancel()
        waitDialog.setText(R.string.lan_backup_send).show()
        lanBackupJob = viewLifecycleOwner.lifecycleScope.launch {
            var pendingSession: LanBackupSession? = null
            try {
                val prepared = withContext(IO) {
                    val backupFile = Backup.backupForLanTransferLocked(appCtx)
                    val deviceName = AppConfig.webDavDeviceName
                        ?.takeIf { it.isNotBlank() }
                        ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                    val session = LanBackupTransfer.prepare(backupFile, deviceName)
                    pendingSession = session
                    val qrCode = QRCodeUtils.createQRCode(session.qrText)
                        ?: throw NoStackTraceException("生成二维码失败")
                    session to qrCode
                }
                pendingSession = prepared.first
                ensureActive()
                showLanBackupQr(prepared.first, prepared.second)
                pendingSession = null
            } catch (error: Throwable) {
                ensureActive()
                AppLog.put("局域网备份发送失败\n${error.localizedMessage}", error)
                appCtx.toastOnUi(error.localizedMessage ?: getString(R.string.backup_fail, ""))
            } finally {
                pendingSession?.close()
                waitDialog.dismiss()
            }
        }
        waitDialog.setOnCancelListener { lanBackupJob?.cancel() }
    }

    private fun showLanBackupQr(
        session: LanBackupSession,
        qrCode: android.graphics.Bitmap,
    ) {
        lanBackupDialog?.dismiss()
        lanBackupSession?.close()
        lanBackupSession = session
        val binding = DialogLanBackupSendBinding.inflate(layoutInflater).apply {
            ivQrCode.setImageBitmap(qrCode)
        }
        lateinit var dialog: AlertDialog
        val expireTask = Runnable {
            if (lanBackupSession === session) dialog.dismiss()
        }
        dialog = alert(R.string.lan_backup_send) {
            customView { binding.root }
            cancelButton()
            onDismiss {
                binding.root.removeCallbacks(expireTask)
                if (lanBackupSession === session) {
                    lanBackupSession = null
                    session.close()
                }
                if (lanBackupDialog === dialog) {
                    lanBackupDialog = null
                }
            }
        }
        lanBackupDialog = dialog
        binding.root.postDelayed(
            expireTask,
            (session.descriptor.expiresAt - System.currentTimeMillis()).coerceAtLeast(0L),
        )
    }

    private fun confirmLanBackupReceive(qrText: String) {
        val descriptor = LanBackupTransfer.decodeDescriptor(qrText).getOrElse { error ->
            appCtx.toastOnUi(error.localizedMessage ?: getString(R.string.lan_backup_receive))
            return
        }
        alert(
            title = getString(R.string.lan_backup_receive),
            message = getString(
                R.string.lan_backup_receive_confirm,
                Formatter.formatFileSize(requireContext(), descriptor.size),
                descriptor.deviceName.ifBlank { descriptor.hosts.first() },
            ),
        ) {
            okButton { receiveLanBackup(qrText) }
            cancelButton()
        }
    }

    private fun receiveLanBackup(qrText: String) {
        lanBackupJob?.cancel()
        waitDialog.setText(R.string.lan_backup_receive).show()
        lanBackupJob = viewLifecycleOwner.lifecycleScope.launch {
            var receivedFile: java.io.File? = null
            try {
                val received = LanBackupTransfer.receive(appCtx, qrText)
                receivedFile = received.file
                waitDialog.setText(R.string.backup)
                withContext(IO) {
                    Backup.backupBeforeLanRestoreLocked(appCtx)
                    LanBackupTransfer.requireRestoreSpace(appCtx, received.uncompressedBytes)
                }
                ensureActive()
                waitDialog.setText(R.string.restore)
                withContext(IO) {
                    Restore.restoreOrThrow(
                        appCtx,
                        received.file.toUri(),
                        lanTransfer = true,
                    )
                }
            } catch (error: Throwable) {
                ensureActive()
                AppLog.put("局域网备份接收失败\n${error.localizedMessage}", error)
                appCtx.toastOnUi(error.localizedMessage ?: getString(R.string.lan_backup_receive))
            } finally {
                receivedFile?.parentFile?.deleteRecursively()
                waitDialog.dismiss()
            }
        }
        waitDialog.setOnCancelListener { lanBackupJob?.cancel() }
    }

    /**
     * 备份忽略设置
     */
    private fun backupIgnore() {
        val checkedItems = BooleanArray(BackupConfig.ignoreKeys.size) {
            BackupConfig.ignoreConfig[BackupConfig.ignoreKeys[it]] ?: false
        }
        alert(R.string.restore_ignore) {
            multiChoiceItems(BackupConfig.ignoreTitle, checkedItems) { _, which, isChecked ->
                BackupConfig.ignoreConfig[BackupConfig.ignoreKeys[which]] = isChecked
            }
            onDismiss {
                BackupConfig.saveIgnoreConfig()
            }
        }
    }

    private fun backupContent() {
        val checkedItems = BooleanArray(BackupConfig.contentKeys.size) {
            BackupConfig.contentIsEnabled(BackupConfig.contentKeys[it])
        }
        alert(R.string.backup_content) {
            multiChoiceItems(BackupConfig.contentTitles, checkedItems) { _, which, isChecked ->
                BackupConfig.ignoreConfig[BackupConfig.contentKeys[which]] = !isChecked
            }
            onDismiss {
                BackupConfig.saveIgnoreConfig()
            }
        }
    }


    fun backup() {
        requireContext().selector(R.string.backup, listOf(
            getString(R.string.backup_local_only), getString(R.string.backup_local_webdav),
        )) { _, index ->
            manualBackupUploadWebDav = index == 1
            startBackup(manualBackupUploadWebDav)
        }
    }

    private fun startBackup(uploadWebDav: Boolean) {
        val backupPath = AppConfig.backupPath
        if (backupPath.isNullOrEmpty()) {
            backup(null, uploadWebDav)
        } else {
            if (backupPath.isContentScheme()) {
                lifecycleScope.launch {
                    val canWrite = withContext(IO) {
                        FileDoc.fromDir(backupPath).checkWrite()
                    }
                    if (canWrite) {
                        backup(backupPath, uploadWebDav)
                    } else {
                        backupDir.launch()
                    }
                }
            } else {
                backupUsePermission(backupPath, uploadWebDav)
            }
        }
    }

    private fun backup(backupPath: String?, uploadWebDav: Boolean) {
        waitDialog.setText("备份中…")
        waitDialog.setOnCancelListener {
            backupJob?.cancel()
        }
        waitDialog.show()
        backupJob?.cancel()
        backupJob = lifecycleScope.launch {
            try {
                Backup.backupLocked(requireContext(), backupPath, uploadWebDav)
                appCtx.toastOnUi(R.string.backup_success)
            } catch (e: Throwable) {
                ensureActive()
                AppLog.put("备份出错\n${e.localizedMessage}", e)
                appCtx.toastOnUi(
                    appCtx.getString(
                        R.string.backup_fail,
                        e.localizedMessage
                    )
                )
            } finally {
                ensureActive()
                waitDialog.dismiss()
            }
        }
    }

    private fun backupUsePermission(path: String, uploadWebDav: Boolean) {
        PermissionsCompat.Builder()
            .addPermissions(*Permissions.Group.STORAGE)
            .rationale(R.string.tip_perm_request_storage)
            .onGranted {
                backup(path, uploadWebDav)
            }
            .request()
    }

    fun restore() {
        waitDialog.setText(R.string.loading)
        waitDialog.setOnCancelListener {
            restoreJob?.cancel()
        }
        waitDialog.show()
        Coroutine.async {
            restoreJob = coroutineContext[Job]
            showRestoreDialog(requireContext())
        }.onError {
            AppLog.put("恢复备份出错WebDavError\n${it.localizedMessage}", it)
            if (context == null) {
                return@onError
            }
            alert {
                setTitle(R.string.restore)
                setMessage("WebDavError\n${it.localizedMessage}\n将从本地备份恢复。")
                okButton {
                    restoreFromLocal()
                }
                cancelButton()
            }
        }.onFinally {
            waitDialog.dismiss()
        }
    }

    private suspend fun showRestoreDialog(context: Context) {
        val names = withContext(IO) { AppWebDav.getBackupNames() }
        if (AppWebDav.isJianGuoYun && names.size > 700) {
            context.toastOnUi("由于坚果云限制列出文件数量，部分备份可能未显示，请及时清理旧备份")
        }
        if (names.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            withContext(Main) {
                context.selector(
                    title = context.getString(R.string.select_restore_file),
                    items = names
                ) { _, index ->
                    if (index in 0 until names.size) {
                        listView.post {
                            restoreWebDav(names[index])
                        }
                    }
                }
            }
        } else {
            throw NoStackTraceException("Web dav no back up file")
        }
    }

    private fun restoreWebDav(name: String) {
        waitDialog.setText("恢复中…")
        waitDialog.show()
        val task = Coroutine.async {
            AppWebDav.restoreWebDav(name)
        }.onError {
            AppLog.put("WebDav恢复出错\n${it.localizedMessage}", it)
            appCtx.toastOnUi("WebDav恢复出错\n${it.localizedMessage}")
        }.onFinally {
            waitDialog.dismiss()
        }
        waitDialog.setOnCancelListener {
            task.cancel()
        }
    }

    private fun restoreFromLocal() {
        restoreDoc.launch {
            title = getString(R.string.select_restore_file)
            mode = HandleFileContract.FILE
            allowExtensions = arrayOf("zip")
        }
    }

    override fun onDestroyView() {
        lanBackupJob?.cancel()
        lanBackupDialog?.dismiss()
        lanBackupSession?.close()
        lanBackupDialog = null
        lanBackupSession = null
        super.onDestroyView()
        waitDialog.dismiss()
    }

    override fun onStop() {
        lanBackupJob?.cancel()
        lanBackupDialog?.dismiss()
        lanBackupSession?.close()
        lanBackupDialog = null
        lanBackupSession = null
        super.onStop()
    }

}
