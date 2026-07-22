package io.legado.app.ui.config

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.help.BottomBarSkinManager
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch

class BottomBarSkinAssignViewModel : ViewModel() {

    data class SaveOutcome(val name: String?, val error: Throwable?)

    val saveResult = MutableLiveData<SaveOutcome?>()

    @Volatile
    var isSaving = false
        private set

    @Volatile
    private var pendingResult: SaveOutcome? = null

    val hasPendingResult: Boolean
        get() = pendingResult != null

    fun save(
        name: String,
        assigns: Map<String, BottomBarSkinManager.SlotAssign>,
        sessionId: String,
        editName: String?,
    ) {
        if (isSaving) return
        isSaving = true
        viewModelScope.launch(IO) {
            val result = BottomBarSkinManager.saveSkin(name, assigns, sessionId, editName)
            val outcome = result.fold(
                onSuccess = { SaveOutcome(it, null) },
                onFailure = { SaveOutcome(null, it) },
            )
            pendingResult = outcome
            isSaving = false
            saveResult.postValue(outcome)
        }
    }

    fun consume(outcome: SaveOutcome) {
        if (pendingResult === outcome) pendingResult = null
        if (saveResult.value === outcome) saveResult.value = null
    }
}
