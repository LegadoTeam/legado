package io.legado.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Locale
import java.util.ResourceBundle

class RuntimeResourceCompatibilityTest {

    @Test
    fun `kotlin builtins loaders are absent from the runtime`() {
        kotlinBuiltinsRuntimeClasses.forEach { className ->
            assertThrows(className, ClassNotFoundException::class.java) {
                Class.forName(className)
            }
        }
    }

    @Test
    fun `htmlunit english bundle prevents fallback to the default locale`() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            ResourceBundle.clearCache()

            val englishMessages = ResourceBundle.getBundle(messagesBundleName, Locale.ENGLISH)

            assertEquals(Locale.ENGLISH, englishMessages.locale)
            assertEquals(
                "Duplicate parameter name \"{0}\".",
                englishMessages.getString(duplicateParameterKey),
            )
        } finally {
            Locale.setDefault(originalLocale)
            ResourceBundle.clearCache()
        }
    }

    private companion object {
        const val messagesBundleName =
            "org.htmlunit.corejs.javascript.resources.Messages"
        const val duplicateParameterKey = "msg.dup.parms"
        val kotlinBuiltinsRuntimeClasses = listOf(
            "kotlin.reflect.jvm.internal.impl.builtins.BuiltInsLoader",
            "kotlin.reflect.jvm.internal.impl.builtins.KotlinBuiltIns",
            "kotlin.reflect.jvm.internal.impl.serialization.deserialization.builtins.BuiltInsResourceLoader",
        )
    }
}
