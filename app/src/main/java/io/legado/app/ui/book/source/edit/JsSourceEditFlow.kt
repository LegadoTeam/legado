package io.legado.app.ui.book.source.edit

internal enum class JsSourceEditStage {
    READY,
    EDITOR_OPEN,
    SAVING,
    SAVING_FOR_DEBUG,
    SAVING_FOR_LOGIN,
    DEBUG_READY,
    DEBUG_OPEN,
    LOGIN_READY,
    LOGIN_OPEN,
}

internal enum class JsSourceEditRestoreAction {
    OPEN_EDITOR,
    SAVE_AND_FINISH,
    SAVE_FOR_DEBUG,
    SAVE_FOR_LOGIN,
    LAUNCH_DEBUG,
    LAUNCH_LOGIN,
    AWAIT_RESULT,
}

internal fun stageForEditorResult(
    debugRequested: Boolean,
    loginRequested: Boolean = false,
): JsSourceEditStage {
    return when {
        loginRequested -> JsSourceEditStage.SAVING_FOR_LOGIN
        debugRequested -> JsSourceEditStage.SAVING_FOR_DEBUG
        else -> JsSourceEditStage.SAVING
    }
}

internal fun JsSourceEditStage.restoreAction(): JsSourceEditRestoreAction {
    return when (this) {
        JsSourceEditStage.READY -> JsSourceEditRestoreAction.OPEN_EDITOR
        JsSourceEditStage.SAVING -> JsSourceEditRestoreAction.SAVE_AND_FINISH
        JsSourceEditStage.SAVING_FOR_DEBUG -> JsSourceEditRestoreAction.SAVE_FOR_DEBUG
        JsSourceEditStage.SAVING_FOR_LOGIN -> JsSourceEditRestoreAction.SAVE_FOR_LOGIN
        JsSourceEditStage.DEBUG_READY -> JsSourceEditRestoreAction.LAUNCH_DEBUG
        JsSourceEditStage.LOGIN_READY -> JsSourceEditRestoreAction.LAUNCH_LOGIN
        JsSourceEditStage.EDITOR_OPEN,
        JsSourceEditStage.DEBUG_OPEN,
        JsSourceEditStage.LOGIN_OPEN -> JsSourceEditRestoreAction.AWAIT_RESULT
    }
}

internal fun JsSourceEditStage.afterSuccessfulSave(): JsSourceEditStage {
    return when (this) {
        JsSourceEditStage.SAVING -> JsSourceEditStage.READY
        JsSourceEditStage.SAVING_FOR_DEBUG -> JsSourceEditStage.DEBUG_READY
        JsSourceEditStage.SAVING_FOR_LOGIN -> JsSourceEditStage.LOGIN_READY
        else -> error("Unexpected successful save from $this")
    }
}

internal fun JsSourceEditStage.afterDebugResult(): JsSourceEditStage {
    check(this == JsSourceEditStage.DEBUG_OPEN) {
        "Unexpected debug result from $this"
    }
    return JsSourceEditStage.READY
}

internal fun JsSourceEditStage.afterLoginResult(): JsSourceEditStage {
    check(this == JsSourceEditStage.LOGIN_OPEN) {
        "Unexpected login result from $this"
    }
    return JsSourceEditStage.READY
}
