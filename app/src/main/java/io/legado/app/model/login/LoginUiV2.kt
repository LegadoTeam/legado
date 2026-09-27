package io.legado.app.model.login

import com.google.gson.JsonObject
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject

object LoginUiV2 {

    const val MARKER = """{"version":2}"""

    private val knownCommands = setOf("state", "error", "login", "close")

    fun isV2(loginUi: String?): Boolean {
        val text = loginUi?.trim() ?: return false
        if (!text.startsWith("{")) return false
        val obj = GSON.fromJsonObject<JsonObject>(text).getOrNull() ?: return false
        return runCatching { obj.get("version")?.asInt == 2 }.getOrDefault(false)
    }

    fun parseRender(json: String?): List<RowUi>? {
        if (json.isNullOrBlank()) return null
        val obj = GSON.fromJsonObject<JsonObject>(json).getOrNull() ?: return null
        val rowsJson = obj.get("rows")?.takeIf { it.isJsonArray } ?: return null
        val rows = GSON.fromJsonArray<RowUi>(rowsJson.toString()).getOrNull() ?: return null
        if (rows.isEmpty() || rows.any { !it.isValid() }) return null
        val keys = rows.filter {
            it.type == RowUi.Type.text ||
                it.type == RowUi.Type.password ||
                it.type == RowUi.Type.select ||
                it.type == RowUi.Type.toggle
        }.mapNotNull { it.key }
        val actions = rows.filter {
            it.type == RowUi.Type.button || it.type == RowUi.Type.toggle
        }.mapNotNull { it.action }
        if (keys.size != keys.distinct().size || actions.size != actions.distinct().size) {
            return null
        }
        return rows
    }

    private fun RowUi.isValid(): Boolean {
        if (name.isBlank() || countdown?.let { it < 0 } == true) return false
        return when (type) {
            RowUi.Type.text, RowUi.Type.password -> !key.isNullOrBlank()
            RowUi.Type.label -> true
            RowUi.Type.select -> !key.isNullOrBlank() && !options.isNullOrEmpty()
            RowUi.Type.toggle -> !key.isNullOrBlank() &&
                (value == null || value == "true" || value == "false") &&
                (action == null || action.isNotBlank())
            RowUi.Type.button -> !action.isNullOrBlank()
            else -> false
        }
    }

    data class ActionResult(
        val stateJson: String? = null,
        val error: Map<String, String>? = null,
        val loginJson: String? = null,
        val close: Boolean = false,
        val unknownKeys: List<String> = emptyList(),
        val malformed: Boolean = false,
    )

    fun parseActionResult(json: String?): ActionResult {
        if (json.isNullOrBlank()) return ActionResult()
        val obj = GSON.fromJsonObject<JsonObject>(json).getOrNull()
            ?: return ActionResult(malformed = true)
        val state = obj.get("state")?.takeUnless { it.isJsonNull }
        val error = obj.get("error")?.takeUnless { it.isJsonNull }
        val login = obj.get("login")?.takeUnless { it.isJsonNull }
        val close = obj.get("close")?.takeUnless { it.isJsonNull }
        if (state != null && !state.isJsonObject ||
            error != null && !error.isJsonObject ||
            login != null && !login.isJsonObject ||
            close != null && (!close.isJsonPrimitive || !close.asJsonPrimitive.isBoolean)
        ) {
            return ActionResult(malformed = true)
        }
        val errors = error?.let {
            GSON.fromJsonObject<Map<String, String>>(it.toString()).getOrNull()
                ?: return ActionResult(malformed = true)
        }
        val closeValue = close?.let {
            runCatching { it.asBoolean }.getOrNull()
                ?: return ActionResult(malformed = true)
        } ?: false
        return ActionResult(
            stateJson = state?.toString(),
            error = errors,
            loginJson = login?.toString(),
            close = closeValue,
            unknownKeys = obj.keySet().filterNot { it in knownCommands },
        )
    }

    fun resolveFieldValue(renderValue: String?, sessionInput: String?, stored: String?): String? {
        return renderValue ?: sessionInput ?: stored
    }
}
