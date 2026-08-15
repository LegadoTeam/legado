package io.legado.app.ui.widget.dialog

import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.ViewGroup
import android.view.textclassifier.TextClassifier
import android.widget.ArrayAdapter
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogTextViewBinding
import io.legado.app.help.CacheManager
import io.legado.app.help.HelpSection
import io.legado.app.help.IntentData
import io.legado.app.help.parseHelpSections
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.utils.applyTint
import io.legado.app.utils.setHtml
import io.legado.app.utils.setLayout
import io.legado.app.utils.setMarkdown
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class TextDialog() : BaseDialogFragment(R.layout.dialog_text_view) {

    enum class Mode {
        MD, HTML, TEXT
    }

    constructor(
        title: String,
        content: String?,
        mode: Mode = Mode.TEXT,
        time: Long = 0,
        autoClose: Boolean = false,
        showToc: Boolean = false
    ) : this() {
        val tocEnabled = showToc && mode == Mode.MD
        arguments = Bundle().apply {
            putString("title", title)
            putString("content", if (tocEnabled) content else IntentData.put(content))
            putString("mode", mode.name)
            putLong("time", time)
            putBoolean("showToc", tocEnabled)
        }
        isCancelable = false
        this.autoClose = autoClose
    }

    private val binding by viewBinding(DialogTextViewBinding::bind)
    private var time = 0L
    private var autoClose: Boolean = false
    private var markwon: Markwon? = null
    private var renderJob: Job? = null
    private var fullContent = ""
    private var sections: List<HelpSection> = emptyList()
    private var selectedSection = 0
    private var restoredScrollY = 0

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.inflateMenu(R.menu.dialog_text)
        binding.toolBar.menu.applyTint(requireContext())
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        binding.drawerLayout.setDrawerTitle(GravityCompat.END, getString(R.string.chapter_list))
        selectedSection = savedInstanceState?.getInt(STATE_SELECTED_SECTION) ?: 0
        restoredScrollY = savedInstanceState?.getInt(STATE_SCROLL_Y) ?: 0
        arguments?.let {
            val title = it.getString("title")
            binding.toolBar.title = title
            val showToc = it.getBoolean("showToc")
            val content = if (showToc) {
                it.getString("content").orEmpty()
            } else {
                IntentData.get<String>(it.getString("content")).orEmpty()
            }
            fullContent = content
            val mode = it.getString("mode")
            when (mode) {
                Mode.MD.name -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        binding.textView.setTextClassifier(TextClassifier.NO_OP)
                    }
                    if (showToc) {
                        binding.textView.setLineSpacing(0f, 1.3f)
                        setupToc()
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        markwon = withContext(IO) {
                            val context = requireContext()
                            val builder = Markwon.builder(context)
                                .usePlugin(GlideImagesPlugin.create(Glide.with(context)))
                                .usePlugin(HtmlPlugin.create())
                            if (showToc) {
                                builder
                                    .usePlugin(TablePlugin.create(HelpMarkwonTheme.tableTheme(context)))
                                    .usePlugin(HelpMarkwonTheme.plugin(context))
                            } else {
                                builder.usePlugin(TablePlugin.create(context))
                            }
                            builder.build()
                        }
                        renderMarkdown(currentMarkdown(), restoredScrollY)
                    }
                }

                Mode.HTML.name -> binding.textView.setHtml(content)
                else -> {
                    if (content.length >= 32 * 1024) {
                        val truncatedContent =
                            content.take(32 * 1024) + "\n\n数据太大，无法全部显示…"
                        binding.textView.text = truncatedContent
                    } else {
                        binding.textView.text = content
                    }
                }
            }
            binding.toolBar.setOnMenuItemClickListener { menu ->
                when (menu.itemId) {
                    R.id.menu_help_toc -> binding.drawerLayout.openDrawer(GravityCompat.END)
                    R.id.menu_close -> dismissAllowingStateLoss()
                    R.id.menu_fullscreen_edit -> {
                        val cacheKey = "code_text_${System.currentTimeMillis()}"
                        CacheManager.putMemory(cacheKey, fullContent)
                        startActivity<CodeEditActivity> {
                            putExtra("cacheKey", cacheKey)
                            putExtra("title", title)
                            putExtra("languageName", if (mode == Mode.MD.name) "text.html.markdown" else "text.html.basic")
                        }
                    }
                }
                true
            }
            time = it.getLong("time", 0L)
        }
        if (time > 0) {
            val owner = viewLifecycleOwner
            val badgeView = binding.badgeView
            badgeView.setBadgeCount((time / 1000).toInt())
            owner.lifecycleScope.launch {
                while (time > 0) {
                    delay(1000)
                    time -= 1000
                    badgeView.setBadgeCount((time / 1000).toInt())
                    if (time <= 0) {
                        dialog?.setCancelable(true)
                        if (autoClose) dialog?.cancel()
                    }
                }
            }
        } else {
            val owner = viewLifecycleOwner
            view.post {
                if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                    dialog?.setCancelable(true)
                }
            }
        }
    }

    private fun setupToc() {
        sections = parseHelpSections(fullContent)
        if (sections.isEmpty()) {
            selectedSection = 0
            return
        }
        selectedSection = selectedSection.coerceIn(0, sections.size)
        val labels = listOf(getString(R.string.all)) + sections.map {
            if (it.depth == 0) it.title else "    ${it.title}"
        }
        binding.tocList.adapter = ArrayAdapter<String>(
            requireContext(),
            android.R.layout.simple_list_item_activated_1,
            labels,
        )
        binding.tocList.setItemChecked(selectedSection, true)
        binding.tocList.setSelection(selectedSection)
        binding.tocList.setOnItemClickListener { _, _, position, _ ->
            if (selectedSection != position) {
                selectedSection = position
                restoredScrollY = 0
                binding.textView.scrollTo(0, 0)
                renderMarkdown(currentMarkdown())
            }
            binding.drawerLayout.closeDrawers()
        }
        binding.toolBar.menu.findItem(R.id.menu_help_toc)?.isVisible = true
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
    }

    private fun currentMarkdown(): String {
        return if (selectedSection == 0) {
            fullContent
        } else {
            sections[selectedSection - 1].markdown
        }
    }

    private fun renderMarkdown(markdown: String, scrollY: Int = 0) {
        val currentMarkwon = markwon ?: return
        renderJob?.cancel()
        renderJob = viewLifecycleOwner.lifecycleScope.launch {
            val parsed = withContext(IO) { currentMarkwon.toMarkdown(markdown) }
            val textView = binding.textView
            textView.movementMethod = LinkMovementMethod.getInstance()
            textView.setMarkdown(
                currentMarkwon,
                parsed,
                imgOnLongClickListener = { source ->
                    showDialogFragment(PhotoDialog(source))
                },
            )
            textView.post {
                if (textView.isAttachedToWindow) textView.scrollTo(0, scrollY)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (arguments?.getBoolean("showToc") == true) {
            outState.putInt(STATE_SELECTED_SECTION, selectedSection)
            outState.putInt(STATE_SCROLL_Y, binding.textView.scrollY)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        renderJob?.cancel()
        renderJob = null
        markwon = null
        super.onDestroyView()
    }

    companion object {
        private const val STATE_SELECTED_SECTION = "selectedSection"
        private const val STATE_SCROLL_Y = "textScrollY"
    }

}
