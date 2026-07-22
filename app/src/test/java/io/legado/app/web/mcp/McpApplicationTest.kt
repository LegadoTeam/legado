package io.legado.app.web.mcp

import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import org.junit.Assert.assertEquals
import org.junit.Test

class McpApplicationTest {

    @Test
    fun missingOrWrongTokenRejectsEveryMcpMethodBeforeParsing() = testApplication {
        application { testMcpApplication() }

        listOf(HttpMethod.Get, HttpMethod.Post, HttpMethod.Delete).forEach { method ->
            listOf(null, "wrong").forEach { token ->
                val response = client.request(McpAccess.PATH) {
                    this.method = method
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Accept, "application/json, text/event-stream")
                    token?.let { header(McpAccess.TOKEN_HEADER, it) }
                    contentType(ContentType.Application.Json)
                    setBody("not-json")
                }

                assertEquals(HttpStatusCode.Unauthorized, response.status)
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            }
        }

        val trailingSlash = client.request("${McpAccess.PATH}/") {
            method = HttpMethod.Post
            header(HttpHeaders.Host, "localhost")
        }
        assertEquals(HttpStatusCode.Unauthorized, trailingSlash.status)

        val encodedPath = client.request("/m%63p") {
            method = HttpMethod.Post
            header(HttpHeaders.Host, "localhost")
        }
        assertEquals(HttpStatusCode.Unauthorized, encodedPath.status)
    }

    @Test
    fun validTokenReachesTheSdkRoute() = testApplication {
        application { testMcpApplication() }

        val response = client.request(McpAccess.PATH) {
            method = HttpMethod.Post
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            header(McpAccess.TOKEN_HEADER, "secret")
            contentType(ContentType.Application.Json)
            setBody("not-json")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun validTokenStillRequiresAllowedHostAndOrigin() = testApplication {
        application { testMcpApplication() }

        val hostileHost = client.request(McpAccess.PATH) {
            method = HttpMethod.Post
            header(HttpHeaders.Host, "example.test")
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            header(McpAccess.TOKEN_HEADER, "secret")
            contentType(ContentType.Application.Json)
            setBody("not-json")
        }
        assertEquals(HttpStatusCode.Forbidden, hostileHost.status)

        val hostileOrigin = client.request(McpAccess.PATH) {
            method = HttpMethod.Post
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://example.test")
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            header(McpAccess.TOKEN_HEADER, "secret")
            contentType(ContentType.Application.Json)
            setBody("not-json")
        }
        assertEquals(HttpStatusCode.Forbidden, hostileOrigin.status)
    }

    private fun io.ktor.server.application.Application.testMcpApplication() {
        configureMcp(
            tokenProvider = { "secret" },
            unauthorizedMessage = { "unauthorized" },
            allowedHosts = listOf("localhost"),
            allowedOrigins = listOf("http://localhost"),
        ) {
            Server(
                serverInfo = Implementation(name = "test", version = "1"),
                options = ServerOptions(capabilities = ServerCapabilities()),
            )
        }
    }
}
