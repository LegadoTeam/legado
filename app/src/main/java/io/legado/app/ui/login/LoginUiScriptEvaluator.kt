package io.legado.app.ui.login

import com.script.rhino.RhinoInterruptError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun <T> evaluateLoginUiScript(
    block: suspend () -> T,
    onFailure: (Exception) -> Unit,
): Result<T> {
    return try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: RhinoInterruptError) {
        throw error
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        onFailure(error)
        Result.failure(error)
    }
}

internal data class LoginUiRenderDecision<T>(
    val value: T?,
    val shouldApply: Boolean,
)

internal fun <T> resolveLoginUiRender(
    previousValue: T?,
    result: Result<T>,
): LoginUiRenderDecision<T> {
    return result.fold(
        onSuccess = { LoginUiRenderDecision(it, shouldApply = true) },
        onFailure = { LoginUiRenderDecision(previousValue, shouldApply = false) },
    )
}
