package io.legado.app.ui.book.source.edit

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.ActivityJsSourceEditBinding
import io.legado.app.help.ConcurrentRateLimiter.Companion.concurrentRecordMap
import io.legado.app.help.config.SourceConfig
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.model.SharedJsScope
import io.legado.app.model.jsSource.JsSourceConfig
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class JsSourceEditActivity : BaseActivity<ActivityJsSourceEditBinding>(imageBg = false) {

    override val binding by viewBinding(ActivityJsSourceEditBinding::inflate)

    private var openedSourceUrl: String? = null
    private var pendingText: String? = null
    private var editorOpen = false

    private val editorResult = registerForActivityResult(
        StartActivityContract(CodeEditActivity::class.java)
    ) { result ->
        editorOpen = false
        val text = result.data?.getStringExtra("text")
        if (result.resultCode != Activity.RESULT_OK || text == null) {
            super.finish()
            return@registerForActivityResult
        }
        pendingText = text
        saveSource(text)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        openedSourceUrl = savedInstanceState?.getString("openedSourceUrl")
            ?: intent.getStringExtra("sourceUrl")
        pendingText = savedInstanceState?.getString("pendingText")
        lifecycleScope.launch {
            val text = pendingText ?: withContext(Dispatchers.IO) {
                openedSourceUrl?.let { appDb.bookSourceDao.getBookSource(it)?.mainJs }
                    ?: assets.open("js_source_template.js").bufferedReader().use { it.readText() }
            }
            pendingText = text
            openEditor(text)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("openedSourceUrl", openedSourceUrl)
        outState.putString("pendingText", pendingText)
    }

    private fun openEditor(text: String) {
        if (editorOpen || isFinishing) return
        editorOpen = true
        editorResult.launch {
            putExtra("text", text)
            putExtra("title", getString(R.string.js_source_edit))
            putExtra("languageName", "source.js")
            putExtra("returnUnchangedText", true)
        }
    }

    private fun saveSource(text: String) {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val source = JsSourceConfig.extract(text, coroutineContext)
                    val old = openedSourceUrl?.let { appDb.bookSourceDao.getBookSource(it) }
                        ?: appDb.bookSourceDao.getBookSource(source.bookSourceUrl)
                    preserveUserState(source, old)
                    if (old == null || !source.equal(old)) {
                        stampSource(source)
                    } else {
                        source.lastUpdateTime = old.lastUpdateTime
                    }
                    old?.let {
                        if (it.exploreUrl != source.exploreUrl) {
                            it.clearExploreKindsCache()
                        }
                        if (it.jsLib != source.jsLib) {
                            SharedJsScope.remove(it.jsLib)
                        }
                        if (it.bookSourceUrl != source.bookSourceUrl) {
                            SourceHelp.deleteBookSource(it.bookSourceUrl)
                        } else {
                            appDb.bookSourceDao.delete(it)
                            SourceConfig.removeSource(it.bookSourceUrl)
                        }
                    } ?: openedSourceUrl?.takeIf { it != source.bookSourceUrl }?.let {
                        SourceHelp.deleteBookSource(it)
                    }
                    appDb.bookSourceDao.insert(source)
                    concurrentRecordMap.remove(source.bookSourceUrl)
                    openedSourceUrl = source.bookSourceUrl
                    source
                }
            }.onSuccess { source ->
                toastOnUi(R.string.success)
                setResult(Activity.RESULT_OK, Intent().putExtra("origin", source.bookSourceUrl))
                super.finish()
            }.onFailure {
                toastOnUi(it.localizedMessage)
                openEditor(text)
            }
        }
    }

    private fun preserveUserState(source: BookSource, old: BookSource?) {
        old ?: return
        source.enabled = old.enabled
        source.enabledExplore = old.enabledExplore
        source.customOrder = old.customOrder
        source.weight = old.weight
        source.respondTime = old.respondTime
        if (source.bookSourceGroup.isNullOrBlank()) {
            source.bookSourceGroup = old.bookSourceGroup
        }
    }

    private fun stampSource(source: BookSource) {
        val stamp = System.currentTimeMillis()
        source.lastUpdateTime = stamp
        source.mainJs?.let { script ->
            JsSourceConfig.stampLastUpdateTime(script, stamp)?.let { source.mainJs = it }
        }
    }
}
