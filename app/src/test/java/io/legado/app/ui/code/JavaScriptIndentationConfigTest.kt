package io.legado.app.ui.code

import com.google.gson.JsonParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

class JavaScriptIndentationConfigTest {

    @Test
    fun wrapperOpeningLineDoesNotIncreaseIndent() {
        val pattern = Pattern.compile(increaseIndentPattern())

        assertFalse(pattern.matcher("{{").matches())
        assertFalse(pattern.matcher("  {{  ").matches())
        assertTrue(pattern.matcher("if (enabled) {").matches())
    }

    private fun increaseIndentPattern(): String {
        val config = sequenceOf(
            File("src/main/assets/textmate/javascript/language-configuration.json"),
            File("app/src/main/assets/textmate/javascript/language-configuration.json")
        ).first(File::isFile)
        return JsonParser.parseString(config.readText())
            .asJsonObject["indentationRules"].asJsonObject
            .getAsJsonObject("increaseIndentPattern")["pattern"].asString
    }
}
