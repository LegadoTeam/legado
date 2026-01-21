package io.legado.app.ui.dict

import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.ViewGroup
import android.view.textclassifier.TextClassifier
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.DictRule
import io.legado.app.databinding.DialogDictBinding
import io.legado.app.help.GlideImageGetter
import io.legado.app.help.TextViewTagHandler
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.utils.setHtml
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 词典
 */
class DictDialog() : BaseDialogFragment(R.layout.dialog_dict) {

    constructor(word: String) : this() {
        arguments = Bundle().apply {
            putString("word", word)
        }
    }

    private val viewModel by viewModels<DictViewModel>()
    private val binding by viewBinding(DialogDictBinding::bind)
    private var word: String? = null
    private var initGetter = false
    private val glideImageGetter by lazy {
        initGetter = true
        GlideImageGetter(requireContext(), binding.tvDict, this@DictDialog.lifecycle)
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.tvDict.movementMethod = LinkMovementMethod()
        word = arguments?.getString("word")
        if (word.isNullOrEmpty()) {
            toastOnUi(R.string.cannot_empty)
            dismiss()
            return
        }
        binding.tabLayout.setBackgroundColor(backgroundColor)
        binding.tabLayout.setSelectedTabIndicatorColor(accentColor)
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabReselected(tab: TabLayout.Tab) {
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
            }

            override fun onTabSelected(tab: TabLayout.Tab) {
                val dictRule = tab.tag as DictRule
                binding.rotateLoading.visible()
                viewModel.dict(dictRule, word!!) {
                    binding.rotateLoading.inVisible()
                    val contentTrimS = it.trimStart()
                    if (contentTrimS.startsWith("<md>")) {
                        val lastIndex = contentTrimS.lastIndexOf("<")
                        if (lastIndex < 4) {
                            binding.tvDict.text = contentTrimS
                            return@dict
                        }
                        val mark = contentTrimS.substring(4, lastIndex)
                        viewLifecycleOwner.lifecycleScope.launch {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                binding.tvDict.setTextClassifier(TextClassifier.NO_OP)
                            }
                            val markwon: Markwon
                            val markdown = withContext(IO) {
                                markwon = Markwon.builder(requireContext())
                                    .usePlugin(GlideImagesPlugin.create(Glide.with(requireContext())))
                                    .usePlugin(HtmlPlugin.create())
                                    .usePlugin(TablePlugin.create(requireContext()))
                                    .build()
                                markwon.toMarkdown(mark)
                            }
                            markwon.setParsedMarkdown(binding.tvDict, markdown)
                        }
                        return@dict
                    }
                    val textViewTagHandler = TextViewTagHandler(object : TextViewTagHandler.OnButtonClickListener {
                        override fun onButtonClick(name: String, click: String?) {
                            viewModel.onButtonClick(dictRule, name, click)
                        }
                    })
                    binding.tvDict.setHtml(it, glideImageGetter, textViewTagHandler)
                }
            }
        })
        viewModel.initData {
            it.forEach { d  ->
                binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
                    text = d.name
                    tag = d
                })
            }
            setupTabLayoutMode(it.size)
        }
    }

    //根据已启用词典数动态选取布局
    private fun setupTabLayoutMode(dictCount: Int) {
        if (dictCount <= 4) {
            binding.tabLayout.tabMode = TabLayout.MODE_FIXED
            binding.tabLayout.tabGravity = TabLayout.GRAVITY_FILL
        } else {
            binding.tabLayout.tabMode = TabLayout.MODE_SCROLLABLE
            binding.tabLayout.tabGravity = TabLayout.GRAVITY_CENTER
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (initGetter) {
            glideImageGetter.clear()
        }
    }
}
