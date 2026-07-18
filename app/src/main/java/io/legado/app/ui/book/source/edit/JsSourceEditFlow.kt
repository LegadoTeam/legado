package io.legado.app.ui.book.source.edit

internal enum class JsSourceEditStage {
    READY,
    EDITOR_OPEN,
    SAVING,
    SAVING_FOR_DEBUG,
    DEBUG_READY,
    DEBUG_OPEN,
}

internal enum class JsSourceEditRestoreAction {
    OPEN_EDITOR,
    SAVE_AND_FINISH,
    SAVE_FOR_DEBUG,
    LAUNCH_DEBUG,
    AWAIT_RESULT,
}

internal fun stageForEditorResult(debugRequested: Boolean): JsSourceEditStage {
    return if (debugRequested) {
        JsSourceEditStage.SAVING_FOR_DEBUG
    } else {
        JsSourceEditStage.SAVING
    }
}

internal fun JsSourceEditStage.restoreAction(): JsSourceEditRestoreAction {
    return when (this) {
        JsSourceEditStage.READY -> JsSourceEditRestoreAction.OPEN_EDITOR
        JsSourceEditStage.SAVING -> JsSourceEditRestoreAction.SAVE_AND_FINISH
        JsSourceEditStage.SAVING_FOR_DEBUG -> JsSourceEditRestoreAction.SAVE_FOR_DEBUG
        JsSourceEditStage.DEBUG_READY -> JsSourceEditRestoreAction.LAUNCH_DEBUG
        JsSourceEditStage.EDITOR_OPEN,
        JsSourceEditStage.DEBUG_OPEN -> JsSourceEditRestoreAction.AWAIT_RESULT
    }
}

internal fun JsSourceEditStage.afterSuccessfulSave(): JsSourceEditStage {
    return when (this) {
        JsSourceEditStage.SAVING -> JsSourceEditStage.READY
        JsSourceEditStage.SAVING_FOR_DEBUG -> JsSourceEditStage.DEBUG_READY
        else -> error("Unexpected successful save from $this")
    }
}

internal fun JsSourceEditStage.afterDebugResult(): JsSourceEditStage {
    check(this == JsSourceEditStage.DEBUG_OPEN) {
        "Unexpected debug result from $this"
    }
    return JsSourceEditStage.READY
}
