package io.legado.app.ui.about

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemAppLogBinding
import io.legado.app.help.http.HttpLogRecord
import io.legado.app.help.http.HttpLogStore
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.LogUtils
import io.legado.app.utils.applyOpenTint
import io.legado.app.utils.applyTint
import io.legado.app.utils.installMd3OverflowMenu
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.views.onClick
import java.io.File
import java.util.*

class AppLogDialog : BaseDialogFragment(R.layout.dialog_recycler_view),
    Toolbar.OnMenuItemClickListener {

    companion object {
        private const val MAX_SHARE_TEXT = 64_000
    }

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val adapter by lazy {
        LogAdapter(requireContext())
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.run {
            toolBar.setBackgroundColor(primaryColor)
            toolBar.setTitle(R.string.log)
            toolBar.inflateMenu(R.menu.app_log)
            toolBar.menu.applyTint(requireContext())
            toolBar.installMd3OverflowMenu(
                showIcons = true,
                onOpenCustomMenu = { it.applyOpenTint(requireContext()) }
            )
            toolBar.setOnMenuItemClickListener(this@AppLogDialog)
            recyclerView.layoutManager = LinearLayoutManager(requireContext())
            recyclerView.adapter = adapter
        }
        adapter.setItems(AppLog.logs)
        observeEvent<Boolean>(EventBus.APP_LOG_UPDATED) {
            adapter.setItems(AppLog.logs)
        }
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_clear -> alert(R.string.clear, R.string.clear_log_confirm) {
                yesButton {
                    AppLog.clear()
                    HttpLogStore.clear()
                    adapter.clearItems()
                }
                noButton()
            }

            R.id.menu_export -> exportLogs()
        }
        return true
    }

    private fun exportLogs() {
        val text = AppLog.exportText(AppLog.logs)
        if (text.isBlank()) {
            toastOnUi(R.string.no_log)
            return
        }
        if (text.length <= MAX_SHARE_TEXT) {
            requireContext().share(text, getString(R.string.log))
            return
        }
        runCatching {
            val file = File(requireContext().cacheDir, "applog.txt")
            file.writeText(text)
            requireContext().share(file, "text/plain")
        }.onFailure {
            toastOnUi(R.string.can_not_share)
        }
    }

    inner class LogAdapter(context: Context) :
        RecyclerAdapter<Triple<Long, String, Throwable?>, ItemAppLogBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemAppLogBinding {
            return ItemAppLogBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemAppLogBinding,
            item: Triple<Long, String, Throwable?>,
            payloads: MutableList<Any>
        ) {
            binding.textTime.text = LogUtils.logTimeFormat.format(Date(item.first))
            binding.textMessage.text = item.second
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemAppLogBinding) {
            binding.root.onClick {
                getItem(holder.layoutPosition)?.let { item ->
                    val httpId = HttpLogRecord.parseId(item.second)
                    val httpRecord = httpId?.let(HttpLogStore::get)
                    when {
                        httpId != null -> {
                            showDialogFragment(TextDialog("HTTP", httpRecord?.detail ?: item.second))
                        }

                        else -> item.third?.let { throwable ->
                            showDialogFragment(TextDialog("Log", throwable.stackTraceToString()))
                        }
                    }
                }
            }
        }

    }

}
