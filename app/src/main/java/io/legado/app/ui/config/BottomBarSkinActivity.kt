package io.legado.app.ui.config

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ActivityBottomBarSkinBinding
import io.legado.app.databinding.ItemBottomBarSkinBinding
import io.legado.app.help.BottomBarSkinManager
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.FileDoc
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.inputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.share
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

class BottomBarSkinActivity : BaseActivity<ActivityBottomBarSkinBinding>() {

    override val binding by viewBinding(ActivityBottomBarSkinBinding::inflate)

    private val adapter by lazy { SkinAdapter(this) }
    private val previewSizePx by lazy { 24.dpToPx() }
    private val skinPreviews = hashMapOf<String, List<Bitmap>>()
    private var loadJob: Job? = null

    private val importDoc = registerForActivityResult(HandleFileContract()) { result ->
        val uri = result.uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val session = AtomicReference<String?>()
            try {
                val extracted = withContext(IO) {
                    uri.inputStream(this@BottomBarSkinActivity).mapCatching { input ->
                        input.use { BottomBarSkinManager.extractImages(it).getOrThrow() }
                    }.also { result -> session.set(result.getOrNull()) }
                }
                if (extracted.isFailure) {
                    val message = extracted.exceptionOrNull()?.message.orEmpty()
                    toastOnUi(
                        if (message.contains("no ", ignoreCase = true)) {
                            R.string.bottom_bar_skin_no_images
                        } else {
                            R.string.bottom_bar_skin_invalid
                        }
                    )
                    return@launch
                }
                val displayName = withContext(IO) {
                    runCatching { FileDoc.fromUri(uri, false).name }.getOrNull().orEmpty()
                        .substringBeforeLast('.')
                }
                val sessionId = extracted.getOrThrow()
                startActivity(
                    Intent(this@BottomBarSkinActivity, BottomBarSkinAssignActivity::class.java)
                        .putExtra("name", displayName)
                        .putExtra("sessionId", sessionId)
                )
                session.compareAndSet(sessionId, null)
            } finally {
                session.getAndSet(null)?.let { sessionId ->
                    withContext(NonCancellable + IO) {
                        BottomBarSkinManager.discardSession(sessionId)
                    }
                }
            }
        }
    }

    private val exportDoc = registerForActivityResult(HandleFileContract()) { result ->
        if (result.uri != null) toastOnUi(R.string.export_success)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val spanCount = (resources.configuration.screenWidthDp / 160).coerceIn(1, 4)
        binding.recyclerView.layoutManager = GridLayoutManager(this, spanCount)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding(withInitialPadding = true)
    }

    override fun onResume() {
        super.onResume()
        initData()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.bottom_bar_skin, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_import) {
            importDoc.launch {
                mode = HandleFileContract.FILE
                title = getString(R.string.bottom_bar_skin_import)
                allowExtensions = arrayOf(
                    "zip", "ziP", "zIp", "zIP",
                    "Zip", "ZiP", "ZIp", "ZIP",
                )
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initData() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val names = withContext(IO) { BottomBarSkinManager.list() }
            val previews = withContext(IO) {
                names.associateWith { BottomBarSkinManager.getPreviewBitmaps(it, previewSizePx) }
            }
            skinPreviews.clear()
            skinPreviews.putAll(previews)
            val items = buildList {
                add(SkinItem("", true))
                names.forEach { add(SkinItem(it, false)) }
            }
            adapter.setItems(items)
        }
    }

    private fun confirmDelete(name: String) {
        alert(R.string.delete) {
            setMessage(getString(R.string.bottom_bar_skin_delete_confirm))
            yesButton {
                lifecycleScope.launch {
                    if (withContext(IO) { BottomBarSkinManager.delete(name) }) {
                        postEvent(EventBus.BOTTOM_BAR_SKIN, "")
                        initData()
                    } else {
                        toastOnUi(R.string.bottom_bar_skin_invalid)
                    }
                }
            }
            noButton()
        }
    }

    /** 长按图集: 编辑 / 导出 / 分享 / 删除 */
    private fun showItemMenu(name: String) {
        val actions = listOf(
            getString(R.string.edit),
            getString(R.string.export),
            getString(R.string.share),
            getString(R.string.delete),
        )
        selector(name, actions) { _, which ->
            when (which) {
                0 -> startEdit(name)
                1 -> exportSkin(name)
                2 -> shareSkin(name)
                3 -> confirmDelete(name)
            }
        }
    }

    /** 导出图集为 zip 到用户选定位置 */
    private fun exportSkin(name: String) {
        lifecycleScope.launch {
            val bytes = withContext(IO) { BottomBarSkinManager.buildZipBytes(name) }.getOrNull()
            if (bytes == null) {
                toastOnUi(R.string.bottom_bar_skin_invalid)
                return@launch
            }
            exportDoc.launch {
                mode = HandleFileContract.EXPORT
                fileData = HandleFileContract.FileData("$name.zip", bytes, "application/zip")
            }
        }
    }

    /** 分享图集 zip 给其它应用 */
    private fun shareSkin(name: String) {
        lifecycleScope.launch {
            val file = withContext(IO) { BottomBarSkinManager.cacheShareZip(name) }.getOrNull()
            if (file == null) {
                toastOnUi(R.string.bottom_bar_skin_invalid)
                return@launch
            }
            share(file, "application/zip")
        }
    }

    /** 编辑既有图集: 把它的图片复制进暂存目录, 复用分配页(锁名、保存即覆盖) */
    private fun startEdit(name: String) {
        lifecycleScope.launch {
            val session = AtomicReference<String?>()
            try {
                val staged = withContext(IO) {
                    BottomBarSkinManager.stageExisting(name)
                        .also { result -> session.set(result.getOrNull()) }
                }
                if (staged.isFailure) {
                    toastOnUi(R.string.bottom_bar_skin_invalid)
                    return@launch
                }
                val sessionId = staged.getOrThrow()
                startActivity(
                    Intent(this@BottomBarSkinActivity, BottomBarSkinAssignActivity::class.java)
                        .putExtra("name", name)
                        .putExtra("editName", name)
                        .putExtra("sessionId", sessionId)
                )
                session.compareAndSet(sessionId, null)
            } finally {
                session.getAndSet(null)?.let { sessionId ->
                    withContext(NonCancellable + IO) {
                        BottomBarSkinManager.discardSession(sessionId)
                    }
                }
            }
        }
    }

    data class SkinItem(val name: String, val isDefault: Boolean)

    inner class SkinAdapter(context: Context) :
        RecyclerAdapter<SkinItem, ItemBottomBarSkinBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemBottomBarSkinBinding {
            return ItemBottomBarSkinBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemBottomBarSkinBinding,
            item: SkinItem,
            payloads: MutableList<Any>,
        ) {
            binding.apply {
                tvName.text =
                    if (item.isDefault) getString(R.string.bottom_bar_skin_default) else item.name
                llPreview.removeAllViews()
                if (item.isDefault) {
                    intArrayOf(
                        R.drawable.ic_bottom_books,
                        R.drawable.ic_bottom_explore,
                        R.drawable.ic_bottom_rss_feed,
                        R.drawable.ic_bottom_person,
                    ).forEach { res ->
                        val iv = makeIconView()
                        iv.setImageResource(res)
                        iv.setColorFilter(context.accentColor)
                        llPreview.addView(iv)
                    }
                } else {
                    skinPreviews[item.name].orEmpty().forEach { bmp ->
                        val iv = makeIconView()
                        iv.setImageBitmap(bmp)
                        llPreview.addView(iv)
                    }
                }
                val isActive = if (item.isDefault) {
                    BottomBarSkinManager.active.isEmpty()
                } else {
                    BottomBarSkinManager.active == item.name
                }
                val accent = context.accentColor
                llCard.background = if (isActive) {
                    GradientDrawable().apply {
                        cornerRadius = 8.dpToPx().toFloat()
                        setStroke(2.dpToPx(), accent)
                        setColor(ColorUtils.setAlphaComponent(accent, 0x14))
                    }
                } else {
                    null
                }
                ivChecked.setColorFilter(accent)
                ivChecked.visibility = if (isActive) View.VISIBLE else View.GONE
            }
        }

        private fun makeIconView(): ImageView {
            val iv = ImageView(context)
            iv.layoutParams = LinearLayout.LayoutParams(previewSizePx, previewSizePx).apply {
                marginEnd = 4.dpToPx()
            }
            return iv
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemBottomBarSkinBinding) {
            binding.root.setOnClickListener {
                val item = getItem(holder.layoutPosition) ?: return@setOnClickListener
                BottomBarSkinManager.active = if (item.isDefault) "" else item.name
                postEvent(EventBus.BOTTOM_BAR_SKIN, "")
                notifyItemRangeChanged(0, itemCount)
            }
            binding.root.setOnLongClickListener {
                val item = getItem(holder.layoutPosition) ?: return@setOnLongClickListener false
                if (!item.isDefault) showItemMenu(item.name)
                true
            }
        }
    }
}
