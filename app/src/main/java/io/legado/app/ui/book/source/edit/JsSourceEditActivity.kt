package io.legado.app.ui.book.source.edit

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStateAtLeast
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
import io.legado.app.ui.book.source.debug.BookSourceDebugActivity
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class JsSourceEditActivity : BaseActivity<ActivityJsSourceEditBinding>(imageBg = false) {

    companion object {
        private const val STATE_OPENED_SOURCE_URL = "openedSourceUrl"
        private const val STATE_PENDING_TEXT = "pendingText"
        private const val STATE_STAGE = "stage"
    }

    override val binding by viewBinding(ActivityJsSourceEditBinding::inflate)

    private var openedSourceUrl: String? = null
    private var pendingText: String? = null
    private var stage = JsSourceEditStage.READY

    private val debugResult = registerForActivityResult(
        StartActivityContract(BookSourceDebugActivity::class.java)
    ) {
        stage = stage.afterDebugResult()
        pendingText?.let(::openEditor) ?: super.finish()
    }

    private val editorResult = registerForActivityResult(
        StartActivityContract(CodeEditActivity::class.java)
    ) { result ->
        val text = result.data?.getStringExtra("text")
        if (result.resultCode != Activity.RESULT_OK || text == null) {
            stage = JsSourceEditStage.READY
            super.finish()
            return@registerForActivityResult
        }
        pendingText = text
        val debugRequested = result.data?.getStringExtra(CodeEditActivity.EXTRA_RESULT_ACTION) ==
            CodeEditActivity.RESULT_ACTION_DEBUG_SOURCE
        stage = stageForEditorResult(debugRequested)
        if (debugRequested) {
            saveForDebug(text)
        } else {
            saveSource(text)
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        openedSourceUrl = savedInstanceState?.getString(STATE_OPENED_SOURCE_URL)
            ?: intent.getStringExtra("sourceUrl")
        pendingText = savedInstanceState?.getString(STATE_PENDING_TEXT)
        stage = savedInstanceState?.getString(STATE_STAGE)
            ?.let { savedStage ->
                runCatching { JsSourceEditStage.valueOf(savedStage) }.getOrNull()
            }
            ?: JsSourceEditStage.READY
        lifecycleScope.launch {
            val text = pendingText ?: withContext(Dispatchers.IO) {
                openedSourceUrl?.let { appDb.bookSourceDao.getBookSource(it)?.mainJs }
                ?: assets.open("js_source_template.js").bufferedReader().use { it.readText() }
            }
            pendingText = text
            when (stage.restoreAction()) {
                JsSourceEditRestoreAction.OPEN_EDITOR -> openEditor(text)
                JsSourceEditRestoreAction.SAVE_AND_FINISH -> saveSource(text)
                JsSourceEditRestoreAction.SAVE_FOR_DEBUG -> saveForDebug(text)
                JsSourceEditRestoreAction.LAUNCH_DEBUG -> launchDebugWhenResumed()
                JsSourceEditRestoreAction.AWAIT_RESULT -> Unit
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_OPENED_SOURCE_URL, openedSourceUrl)
        outState.putString(STATE_PENDING_TEXT, pendingText)
        outState.putString(STATE_STAGE, stage.name)
    }

    private fun openEditor(text: String) {
        if (stage == JsSourceEditStage.EDITOR_OPEN || isFinishing) return
        stage = JsSourceEditStage.EDITOR_OPEN
        editorResult.launch {
            putExtra("text", text)
            putExtra("title", getString(R.string.js_source_edit))
            putExtra("languageName", "source.js")
            putExtra("returnUnchangedText", true)
            putExtra(CodeEditActivity.EXTRA_SHOW_DEBUG_SOURCE, true)
        }
    }

    private fun saveForDebug(text: String) {
        saveSource(text, showSuccessToast = false, finishAfterSave = false) {
            launchDebugWhenResumed()
        }
    }

    private fun launchDebugWhenResumed() {
        val sourceUrl = openedSourceUrl ?: run {
            val text = pendingText ?: return super.finish()
            stage = JsSourceEditStage.SAVING_FOR_DEBUG
            saveForDebug(text)
            return
        }
        lifecycleScope.launch {
            lifecycle.withStateAtLeast(Lifecycle.State.RESUMED) {
                if (stage != JsSourceEditStage.DEBUG_READY) return@withStateAtLeast
                stage = JsSourceEditStage.DEBUG_OPEN
                debugResult.launch {
                    putExtra("key", sourceUrl)
                }
            }
        }
    }

    private fun saveSource(
        text: String,
        showSuccessToast: Boolean = true,
        finishAfterSave: Boolean = true,
        onSuccess: ((BookSource) -> Unit)? = null,
    ) {
        lifecycleScope.launch {
            try {
                val source = withContext(Dispatchers.IO) {
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
                stage = stage.afterSuccessfulSave()
                pendingText = source.mainJs ?: text
                if (showSuccessToast) {
                    toastOnUi(R.string.success)
                }
                setResult(Activity.RESULT_OK, Intent().putExtra("origin", source.bookSourceUrl))
                onSuccess?.invoke(source)
                if (finishAfterSave) {
                    super.finish()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                stage = JsSourceEditStage.READY
                toastOnUi(error.localizedMessage)
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
