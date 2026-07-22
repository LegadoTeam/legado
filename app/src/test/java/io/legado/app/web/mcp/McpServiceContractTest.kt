package io.legado.app.web.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class McpServiceContractTest {

    @Test
    fun `transport authenticates before installing protected MCP route`() {
        val application = projectFile("app/src/main/java/io/legado/app/web/mcp/McpApplication.kt")
        val auth = application.indexOf("BookSourceController.matchesJsSourceApiToken")
        val route = application.indexOf("mcpStreamableHttp(")

        assertTrue(auth >= 0)
        assertTrue(route > auth)
        assertTrue(application.contains("context.request.header(McpAccess.TOKEN_HEADER)"))
        assertTrue(application.contains("HttpStatusCode.Unauthorized"))
        assertTrue(application.contains("allowedHosts = allowedHosts"))
        assertTrue(application.contains("allowedOrigins = allowedOrigins"))
        assertFalse(application.contains("enableDnsRebindingProtection = false"))
        assertFalse(application.contains("request.path()"))

        val service = projectFile("app/src/main/java/io/legado/app/service/McpService.kt")
        assertTrue(service.indexOf("token.isNullOrBlank()") < service.indexOf("embeddedServer("))

        val manifest = projectFile("app/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("FOREGROUND_SERVICE_SPECIAL_USE"))
        assertTrue(manifest.contains("foregroundServiceType=\"specialUse\""))
        assertTrue(manifest.contains("PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))

        val settings = projectFile("app/src/main/java/io/legado/app/ui/config/OtherConfigFragment.kt")
        assertTrue(settings.contains("previousToken != token"))
        assertTrue(settings.contains("McpService.restart(requireContext())"))

        val network = projectFile(
            "app/src/main/java/io/legado/app/receiver/NetworkChangedListener.kt"
        )
        assertTrue(network.contains("includeDetailedChanges: Boolean = false"))
        assertTrue(network.contains("onLinkPropertiesChanged"))
        assertTrue(network.contains("onLost"))
        assertTrue(service.contains("activeAddressKeys"))
        assertTrue(service.contains("includeDetailedChanges = true"))
        assertTrue(service.contains("private var destroyed = false"))
        assertTrue(service.contains("if (destroyed) return"))
        assertTrue(Regex("""@Synchronized\s+override fun onDestroy\(\)""").containsMatchIn(service))

        val build = projectFile("app/build.gradle")
        assertTrue(build.contains("module: 'kotlin-reflect'"))
    }

    @Test
    fun `server exposes the expected eight tools on current safe APIs`() {
        val tools = projectFile("app/src/main/java/io/legado/app/web/mcp/McpToolServer.kt")
        val registrations = tools.substringAfter("private fun registerTools")
        val names = Regex("name = \\\"([a-z_]+)\\\"")
            .findAll(registrations)
            .map { it.groupValues[1] }
            .toList()

        assertEquals(
            listOf(
                "save_source",
                "debug_source",
                "list_sources",
                "get_source",
                "delete_sources",
                "get_http_logs",
                "get_http_log",
                "set_http_log_recording",
            ),
            names,
        )
        assertTrue(tools.contains("HttpLogRecord"))
        assertTrue(tools.contains("JsSourceUpsert.withSaveLock"))
        assertTrue(tools.contains("catch (error: CancellationException)"))
        assertTrue(tools.contains("val logs = data[\"logs\"] as List<*>"))
        assertTrue(tools.contains("val log = item as Map<*, *>"))
        assertFalse(tools.contains("UNCHECKED_CAST"))
        assertFalse(tools.contains("HttpRecord"))
        assertFalse(tools.contains("HttpLogger"))

        val sourceStore = projectFile("app/src/main/java/io/legado/app/web/mcp/McpSourceStore.kt")
        assertTrue(sourceStore.contains("JsSourceUpsert.prepareForSave"))
        assertTrue(sourceStore.contains("JsSourceUpsert.withSaveLock"))
        assertTrue(sourceStore.contains("mainJs"))
        assertTrue(sourceStore.contains("MAX_SOURCE_BYTES"))
        val upsert = projectFile(
            "app/src/main/java/io/legado/app/model/jsSource/JsSourceUpsert.kt"
        )
        assertTrue(upsert.contains("internal suspend fun <T> withSaveLock"))
    }

    @Test
    fun `documentation and shrinker keep the security boundary`() {
        val api = projectFile("api.md")
        val updateLog = projectFile("app/src/main/assets/updateLog.md")
        val proguard = projectFile("app/proguard-rules.pro")

        assertTrue(api.contains("X-Legado-Token"))
        assertTrue(api.contains("Host 和 Origin 校验"))
        assertTrue(api.contains("可信局域网"))
        assertTrue(updateLog.contains("**2026/07/22**"))
        assertTrue(updateLog.contains("原生 MCP 书源开发服务"))
        assertFalse(proguard.contains("-keep class io.ktor.**"))
        assertFalse(proguard.contains("-keep class kotlinx.coroutines.**"))
    }

    private fun projectFile(path: String): String {
        var root = File(requireNotNull(System.getProperty("user.dir")))
        repeat(6) {
            val candidate = File(root, path)
            if (candidate.isFile) return candidate.readText()
            root = root.parentFile ?: error("Project root not found for: $path")
        }
        error("Project file not found: $path")
    }
}
