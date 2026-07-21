package io.legado.app.ui.widget.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.PopupWindow
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.databinding.ItemFilletTextBinding
import io.legado.app.databinding.PopupKeyboardToolBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.selector
import io.legado.app.utils.activity
import io.legado.app.utils.applyMd3PopupStyle
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.windowSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import splitties.systemservices.layoutInflater
import splitties.systemservices.windowManager

/**
 * 键盘帮助浮窗
 */
class KeyboardToolPop(
    private val context: Context,
    private val scope: CoroutineScope,
    private val rootView: View,
    private val callBack: CallBack
) : PopupWindow(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    ViewTreeObserver.OnGlobalLayoutListener,
    KeyboardAssistsConfig.CallBack {

    private val helpChar = "❓"

    private val binding = PopupKeyboardToolBinding.inflate(LayoutInflater.from(context))
    private val adapter = Adapter(context)
    private var mIsSoftKeyBoardShowing = false
    var initialPadding = 0

    init {
        contentView = binding.root
        applyMd3PopupStyle()

        isTouchable = true
        isOutsideTouchable = false
        isFocusable = false
        inputMethodMode = INPUT_METHOD_NEEDED //解决遮盖输入法
        initRecyclerView()
        upAdapterData()
    }

    fun attachToWindow(window: Window) {
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener(this)
        contentView.measure(
            View.MeasureSpec.UNSPECIFIED,
            View.MeasureSpec.UNSPECIFIED,
        )
    }

    override fun onGlobalLayout() {
        val keyboardHeight = resolveKeyboardHeight()
        val preShowing = mIsSoftKeyBoardShowing
        if (keyboardHeight > 0) {
            mIsSoftKeyBoardShowing = true
            rootView.setPadding(0, 0, 0, initialPadding + contentView.measuredHeight)
            if (!isShowing) {
                showAtLocation(rootView, Gravity.BOTTOM, 0, 0)
            }
        } else {
            mIsSoftKeyBoardShowing = false
            rootView.setPadding(0, 0, 0, 0)
            if (preShowing) {
                dismiss()
            }
        }
    }

    private fun resolveKeyboardHeight(): Int {
        ViewCompat.getRootWindowInsets(rootView)?.let { insets ->
            return resolveKeyboardToolHeight(
                imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime()),
                imeInsetBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
                screenHeight = 0,
                visibleFrameBottom = 0,
            )
        }

        val rect = Rect()
        rootView.getWindowVisibleDisplayFrame(rect)
        val screenHeight = windowManager.windowSize.heightPixels
        return resolveKeyboardToolHeight(
            imeVisible = null,
            imeInsetBottom = 0,
            screenHeight = screenHeight,
            visibleFrameBottom = rect.bottom,
        )
    }

    @SuppressLint("SetTextI18n")
    private fun initRecyclerView() {
        (binding.recyclerView.layoutManager as GridLayoutManager).spanCount = AppConfig.showBoardLine
        binding.recyclerView.adapter = adapter
        adapter.addHeaderView {
            ItemFilletTextBinding.inflate(context.layoutInflater, it, false).apply {
                textView.text = helpChar
                root.setOnClickListener {
                    helpAlert()
                }
            }
        }
        // 安卓6以上支持撤销重做
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            adapter.addHeaderView {
                ItemFilletTextBinding.inflate(context.layoutInflater, it, false).apply {
                    textView.text = "↩\uFE0F"
                    root.setOnClickListener {
                        callBack.onUndoClicked()
                    }
                }
            }
            adapter.addHeaderView {
                ItemFilletTextBinding.inflate(context.layoutInflater, it, false).apply {
                    textView.text = "↪\uFE0F"
                    root.setOnClickListener {
                        callBack.onRedoClicked()
                    }
                }
            }
        }
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun upAdapterData() {
        scope.launch {
            appDb.keyboardAssistsDao.flowByType(0).catch {
                AppLog.put("键盘帮助浮窗获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect {
                adapter.setItems(it)
            }
        }
    }

    private fun helpAlert() {
        val items = arrayListOf(
            SelectItem(context.getString(R.string.assists_key_config), "keyConfig")
        )
        items.addAll(callBack.helpActions())
        context.selector(context.getString(R.string.help), items) { _, selectItem, _ ->
            when (selectItem.value) {
                "keyConfig" -> config()
                else -> callBack.onHelpActionSelect(selectItem.value)
            }
        }
    }

    private fun config() {
        contentView.activity?.showDialogFragment(KeyboardAssistsConfig(this))
    }

    override fun requestLayout() {
        (binding.recyclerView.layoutManager as GridLayoutManager).spanCount = AppConfig.showBoardLine
        binding.recyclerView.layoutManager?.requestLayout()
    }

    inner class Adapter(context: Context) :
        RecyclerAdapter<KeyboardAssist, ItemFilletTextBinding>(context) {

        private val itemClickListener = View.OnClickListener { view ->
            val holder = view.tag as? ItemViewHolder
            holder?.let {
                val position = holder.layoutPosition
                if (position != RecyclerView.NO_POSITION) {
                    getItemByLayoutPosition(position)?.let { item ->
                        callBack.sendText(item.value)
                    }
                }
            }
        }

        override fun getViewBinding(parent: ViewGroup): ItemFilletTextBinding {
            return ItemFilletTextBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemFilletTextBinding,
            item: KeyboardAssist,
            payloads: MutableList<Any>
        ) {
            binding.run {
                textView.text = item.key
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemFilletTextBinding) {
            holder.itemView.tag = holder
            holder.itemView.setOnClickListener(itemClickListener)
        }
    }

    interface CallBack {

        fun helpActions(): List<SelectItem<String>> = arrayListOf()

        fun onHelpActionSelect(action: String)

        fun sendText(text: String)

        fun onUndoClicked()
        fun onRedoClicked()
    }

}

internal fun resolveKeyboardToolHeight(
    imeVisible: Boolean?,
    imeInsetBottom: Int,
    screenHeight: Int,
    visibleFrameBottom: Int,
): Int {
    if (imeVisible != null) {
        return if (imeVisible) imeInsetBottom.coerceAtLeast(0) else 0
    }

    if (screenHeight <= 0 || visibleFrameBottom !in 1..screenHeight) {
        return 0
    }
    val hiddenHeight = screenHeight - visibleFrameBottom
    return hiddenHeight.takeIf { it > screenHeight / 5 } ?: 0
}
