package io.legado.app.ui.association

import android.os.Bundle
import io.legado.app.base.BaseActivity
import io.legado.app.constant.SourceType
import io.legado.app.databinding.ActivityTranslucenceBinding
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS

/**
 * 验证码
 */
class VerificationCodeActivity :
    BaseActivity<ActivityTranslucenceBinding>() {

    override val binding by viewBinding(ActivityTranslucenceBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val verificationResultKey = intent.getStringExtra("verificationResultKey")
        if (!SourceVerificationHelp.attachVerificationUi(
                verificationResultKey,
                ::finishVerificationUi,
            )
        ) {
            finish()
            return
        }
        intent.getStringExtra("imageUrl")?.let {
            val sourceOrigin = intent.getStringExtra("sourceOrigin")
            val sourceName = intent.getStringExtra("sourceName")
            val sourceType = intent.getIntExtra("sourceType", SourceType.book)
            showDialogFragment(
                VerificationCodeDialog(
                    it,
                    sourceOrigin,
                    sourceName,
                    sourceType,
                    verificationResultKey,
                )
            )
        } ?: finish()
    }

    private fun finishVerificationUi() {
        val finished = CountDownLatch(1)
        runOnUiThread {
            try {
                finish()
            } finally {
                finished.countDown()
            }
        }
        finished.await(5, SECONDS)
    }

}
