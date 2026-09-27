package io.legado.app.ui.about

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.legado.app.help.update.AppUpdate
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

/** Shared by About and the optional My-page update entries. */
fun Fragment.checkAppUpdate(beta: Boolean = false) {
    val waitDialog = WaitDialog(requireContext())
    waitDialog.show()
    val request = if (beta) AppUpdate.checkBeta(lifecycleScope)
        else AppUpdate.gitHubUpdate.check(lifecycleScope)
    request.onSuccess {
        if (isAdded && !childFragmentManager.isStateSaved) showDialogFragment(UpdateDialog(it))
    }.onError {
        appCtx.toastOnUi(it.localizedMessage)
    }.onFinally {
        waitDialog.dismiss()
    }
}
