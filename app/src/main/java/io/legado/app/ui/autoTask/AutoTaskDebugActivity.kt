package io.legado.app.ui.autoTask

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.databinding.ActivityAutoTaskDebugBinding
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskRunner
import io.legado.app.model.Debug
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AutoTaskDebugActivity : BaseActivity<ActivityAutoTaskDebugBinding>(), Debug.Callback {

    override val binding by viewBinding(ActivityAutoTaskDebugBinding::inflate)
    private var task: AutoTaskRule? = null
    private var debugJob: Job? = null
    private val output = StringBuilder()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.btnRun.setOnClickListener { runDebug() }
        lifecycleScope.launch(Dispatchers.IO) {
            val loaded = intent.getStringExtra(EXTRA_ID)?.let(AutoTask::get)
            withContext(Dispatchers.Main) {
                task = loaded
                if (loaded == null) finish() else runDebug()
            }
        }
    }

    private fun runDebug() {
        val current = task ?: return
        debugJob?.cancel()
        Debug.cancelDebug(this)
        output.clear()
        binding.tvOutput.text = ""
        binding.progress.isVisible = true
        val sourceUrl = AutoTask.buildSource(current).bookSourceUrl
        if (!Debug.startSimpleDebug(this, sourceUrl)) {
            binding.progress.isVisible = false
            toastOnUi(R.string.auto_task_debug_busy)
            return
        }
        debugJob = lifecycleScope.launch(Dispatchers.IO) {
            val result = AutoTaskRunner.runTask(this@AutoTaskDebugActivity, current, persist = false)
            withContext(Dispatchers.Main) {
                appendLine(result.log)
                binding.progress.isVisible = false
                Debug.cancelDebug(this@AutoTaskDebugActivity)
            }
        }
    }

    override fun printLog(state: Int, msg: String) {
        runOnUiThread { appendLine(msg) }
    }

    private fun appendLine(line: String) {
        if (output.isNotEmpty()) output.append('\n')
        output.append(line)
        if (output.length > MAX_OUTPUT) output.delete(0, output.length - MAX_OUTPUT)
        binding.tvOutput.text = output.toString()
        binding.scrollView.post { binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        debugJob?.cancel()
        Debug.cancelDebug(this)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "autoTaskId"
        private const val MAX_OUTPUT = 20_000

        fun intent(context: Context, id: String): Intent {
            return Intent(context, AutoTaskDebugActivity::class.java).putExtra(EXTRA_ID, id)
        }
    }
}
