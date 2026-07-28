package io.legado.app.ui.code

import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isJson
import io.legado.app.utils.isXml
import java.net.URLEncoder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Converts shared request fields using POSIX shell syntax; runtime defaults are excluded. */
object CurlAnalyzeUrlConverter {

    enum class ErrorReason {
        EMPTY_INPUT,
        INVALID_CURL,
        MISSING_URL,
        INVALID_ANALYZE_URL,
        UNSUPPORTED_METHOD,
        UNSUPPORTED_OPTION,
    }

    class ConversionException(
        val reason: ErrorReason,
        val detail: String = "",
    ) : IllegalArgumentException()

    private data class CurlRequest(
        var url: String = "",
        var customMethod: String? = null,
        val headers: LinkedHashMap<String, String> = linkedMapOf(),
        val bodyParts: MutableList<String> = mutableListOf(),
        var head: Boolean = false,
        var followRedirects: Boolean = false,
        var globOff: Boolean = false,
        var addJsonHeaders: Boolean = false,
    )

    private data class EffectivePost(
        val body: String,
        val contentType: String,
    )

    private val supportedMethods = setOf("GET", "POST", "HEAD")
    private val ignoredOptions = setOf(
        "-s", "--silent", "-S", "--show-error", "-sS", "-Ss",
        "-f", "--fail", "--fail-with-body",
        "--no-progress-meter", "--progress-bar",
    )
    private val ignoredOptionsWithValue = setOf(
        "-o", "--output", "-w", "--write-out",
    )
    private val analyzeOptionKeys = setOf(
        "method", "headers", "body", "followRedirects",
    )
    private val curlCommand = Regex("^\\s*curl(?:\\.exe)?(?:\\s|$)", RegexOption.IGNORE_CASE)
    private val safeShellValue = Regex("[A-Za-z0-9_@%+=:,./-]+")
    private const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"
    private const val JSON_CONTENT_TYPE = "application/json; charset=UTF-8"

    fun looksLikeCurl(text: String): Boolean {
        return curlCommand.containsMatchIn(text)
    }

    fun curlToAnalyzeUrl(text: String): String {
        if (text.isBlank()) throw ConversionException(ErrorReason.EMPTY_INPUT)
        val request = parseCurl(text)
        if (request.url.isBlank()) throw ConversionException(ErrorReason.MISSING_URL)
        validateUrl(request.url, request.globOff)

        val method = resolveCurlMethod(request)
        val body = request.bodyParts.takeIf { it.isNotEmpty() }?.joinToString("&")
        if (body?.isBlank() == true) {
            val contentType = request.headers["Content-Type"]
            if (body.isNotEmpty() || contentType != FORM_CONTENT_TYPE) {
                throw ConversionException(ErrorReason.UNSUPPORTED_OPTION, "blank body")
            }
        }

        val options = linkedMapOf<String, Any>()
        if (method != "GET" || body != null) {
            options["method"] = method
        }
        if (request.headers.isNotEmpty()) {
            options["headers"] = request.headers
        }
        if (body != null) {
            options["body"] = body
        }
        if (!request.followRedirects) options["followRedirects"] = false

        return if (options.isEmpty()) {
            request.url
        } else {
            request.url + "," + GSON.toJson(options)
        }
    }

    fun analyzeUrlToCurl(text: String): String {
        if (text.isBlank()) throw ConversionException(ErrorReason.EMPTY_INPUT)
        val raw = text.trim()
        val matcher = AnalyzeUrl.paramPattern.matcher(raw)
        val url: String
        val optionJson: String?
        if (matcher.find()) {
            url = raw.substring(0, matcher.start()).trim()
            optionJson = raw.substring(matcher.end()).trim()
        } else {
            url = raw
            optionJson = null
        }
        if (url.isBlank()) throw ConversionException(ErrorReason.MISSING_URL)
        validateUrl(url, globOff = true)

        val (option, rawOptions) = optionJson?.let {
            val rawOptions = GSON
                .fromJsonObject<LinkedHashMap<String, Any?>>(it)
                .getOrElse {
                    throw ConversionException(ErrorReason.INVALID_ANALYZE_URL)
                }
            val unsupported = rawOptions.entries
                .filter { entry -> entry.value != null && entry.key !in analyzeOptionKeys }
                .map { it.key }
            if (unsupported.isNotEmpty()) {
                throw ConversionException(
                    ErrorReason.UNSUPPORTED_OPTION,
                    unsupported.joinToString(", "),
                )
            }
            if (rawOptions["method"] != null && rawOptions["method"] !is String) {
                throw ConversionException(ErrorReason.INVALID_ANALYZE_URL)
            }
            if (
                rawOptions["headers"] != null &&
                rawOptions["headers"] !is Map<*, *> &&
                rawOptions["headers"] !is String
            ) {
                throw ConversionException(ErrorReason.INVALID_ANALYZE_URL)
            }
            val option = GSON.fromJsonObject<AnalyzeUrl.UrlOption>(it).getOrElse {
                throw ConversionException(ErrorReason.INVALID_ANALYZE_URL)
            }
            option to rawOptions
        } ?: (AnalyzeUrl.UrlOption() to linkedMapOf())

        val method = option.getMethod()?.uppercase().orEmpty().ifBlank { "GET" }
        validateMethod(method)
        val hasBody = rawOptions["body"] != null
        val body = if (hasBody) {
            option.getBody() ?: throw ConversionException(ErrorReason.INVALID_ANALYZE_URL)
        } else {
            null
        }
        if (body != null && method != "POST") {
            throw ConversionException(ErrorReason.UNSUPPORTED_OPTION, "$method body")
        }
        val headerMap = option.getHeaderMap()
        if (rawOptions["headers"] != null && headerMap == null) {
            throw ConversionException(ErrorReason.INVALID_ANALYZE_URL)
        }
        val followRedirects = rawOptions["followRedirects"]?.let {
            option.getFollowRedirects()
                ?: throw ConversionException(ErrorReason.INVALID_ANALYZE_URL)
        } ?: true
        val contentType = headerMap?.entries
            ?.firstOrNull { it.key.toString() == "Content-Type" }
            ?.value
            ?.toString()
        val effectivePost = if (method == "POST") {
            effectiveAnalyzePost(body, contentType)
        } else {
            null
        }

        val parts = mutableListOf("curl", "-g")
        if (followRedirects) parts += "-L"
        if (method == "HEAD") parts += "-I"
        parts += shellQuote(url)
        headerMap?.forEach { (key, value) ->
            val name = key.toString()
            if (name.equals("proxy", true) || name.equals("CookieJar", true)) {
                throw ConversionException(ErrorReason.UNSUPPORTED_OPTION, name)
            }
            if (
                name.equals("Content-Length", true) ||
                name.equals("Transfer-Encoding", true)
            ) {
                throw ConversionException(ErrorReason.UNSUPPORTED_OPTION, name)
            }
            if (method == "POST" && name.equals("Content-Type", true)) {
                return@forEach
            }
            if (name.equals("User-Agent", true) && value.toString() == "null") {
                parts += listOf("-A", shellQuote(""))
            } else {
                parts += listOf("-H", shellQuote("$key: $value"))
            }
        }
        effectivePost?.let {
            parts += listOf("-H", shellQuote("Content-Type: ${it.contentType}"))
            parts += listOf("--data-raw", shellQuote(it.body))
        }
        return parts.joinToString(" ")
    }

    private fun parseCurl(text: String): CurlRequest {
        val tokens = tokenize(text)
        if (
            tokens.isEmpty() ||
            (!tokens[0].equals("curl", true) && !tokens[0].equals("curl.exe", true))
        ) {
            throw ConversionException(ErrorReason.INVALID_CURL)
        }

        val request = CurlRequest()
        var endOfOptions = false
        var index = 1
        while (index < tokens.size) {
            val token = tokens[index]
            fun nextValue(): String {
                if (index + 1 >= tokens.size) {
                    throw ConversionException(ErrorReason.INVALID_CURL)
                }
                index++
                return tokens[index]
            }

            when {
                endOfOptions -> setUrl(request, token)
                token == "--" -> endOfOptions = true

                token == "-X" || token == "--request" -> request.customMethod = nextValue()
                token.startsWith("--request=") -> request.customMethod =
                    token.substringAfter("--request=")
                token.startsWith("-X") && token.length > 2 ->
                    request.customMethod = token.substring(2)

                token == "-I" || token == "--head" -> request.head = true

                token == "-H" || token == "--header" -> addHeader(request, nextValue())
                token.startsWith("--header=") -> addHeader(
                    request,
                    token.substringAfter("--header="),
                )
                token.startsWith("-H") && token.length > 2 -> addHeader(
                    request,
                    token.substring(2),
                )

                token == "-A" || token == "--user-agent" -> addUserAgent(
                    request,
                    nextValue(),
                )
                token.startsWith("--user-agent=") -> addUserAgent(
                    request,
                    token.substringAfter("--user-agent="),
                )
                token.startsWith("-A") && token.length > 2 -> addUserAgent(
                    request,
                    token.substring(2),
                )

                token == "-e" || token == "--referer" -> addReferer(
                    request,
                    nextValue(),
                )
                token.startsWith("--referer=") -> addReferer(
                    request,
                    token.substringAfter("--referer="),
                )
                token.startsWith("-e") && token.length > 2 -> addReferer(
                    request,
                    token.substring(2),
                )

                token == "-d" || token == "--data" || token == "--data-raw" ||
                    token == "--data-binary" -> addBody(request, token, nextValue())
                token.startsWith("--data=") -> addBody(
                    request,
                    "--data",
                    token.substringAfter("--data="),
                )
                token.startsWith("--data-raw=") -> addBody(
                    request,
                    "--data-raw",
                    token.substringAfter("--data-raw="),
                )
                token.startsWith("--data-binary=") -> addBody(
                    request,
                    "--data-binary",
                    token.substringAfter("--data-binary="),
                )
                token.startsWith("-d") && token.length > 2 -> addBody(
                    request,
                    "-d",
                    token.substring(2),
                )

                token == "--json" -> {
                    addBody(request, "--json", nextValue())
                    request.addJsonHeaders = true
                }
                token.startsWith("--json=") -> {
                    addBody(request, "--json", token.substringAfter("--json="))
                    request.addJsonHeaders = true
                }

                token == "-b" || token == "--cookie" -> addCookie(
                    request,
                    nextValue(),
                )
                token.startsWith("--cookie=") -> addCookie(
                    request,
                    token.substringAfter("--cookie="),
                )
                token.startsWith("-b") && token.length > 2 -> addCookie(
                    request,
                    token.substring(2),
                )

                token == "--url" -> setUrl(request, nextValue())
                token.startsWith("--url=") -> setUrl(
                    request,
                    token.substringAfter("--url="),
                )

                token == "-L" || token == "--location" -> request.followRedirects = true
                token == "--no-location" -> request.followRedirects = false
                token == "-g" || token == "--globoff" -> request.globOff = true
                token == "--no-globoff" -> request.globOff = false

                token in ignoredOptions -> Unit
                token in ignoredOptionsWithValue -> nextValue()
                ignoredOptionsWithValue.any { token.startsWith("$it=") } -> Unit
                (token.startsWith("-o") || token.startsWith("-w")) &&
                    token.length > 2 -> Unit

                token.startsWith("-") -> throw ConversionException(
                    ErrorReason.UNSUPPORTED_OPTION,
                    optionName(token),
                )
                else -> setUrl(request, token)
            }
            index++
        }
        if (request.addJsonHeaders) {
            putDefaultHeader(request, "Content-Type", "application/json")
            putDefaultHeader(request, "Accept", "application/json")
        } else if (request.bodyParts.isNotEmpty()) {
            putDefaultHeader(request, "Content-Type", "application/x-www-form-urlencoded")
        }
        return request
    }

    private fun addHeader(request: CurlRequest, value: String) {
        if (value.startsWith("@")) {
            throw ConversionException(ErrorReason.UNSUPPORTED_OPTION, "--header @file")
        }
        val separator = value.indexOf(':')
        if (separator <= 0) throw ConversionException(ErrorReason.INVALID_CURL)
        putHeader(
            request,
            value.substring(0, separator).trim(),
            value.substring(separator + 1).trim(),
        )
    }

    private fun addUserAgent(request: CurlRequest, value: String) {
        putHeader(request, "User-Agent", value)
    }

    private fun addReferer(request: CurlRequest, value: String) {
        if (value.endsWith(";auto", true)) {
            throw ConversionException(ErrorReason.UNSUPPORTED_OPTION, "--referer ;auto")
        }
        if (value.isNotEmpty()) putHeader(request, "Referer", value)
    }

    private fun addCookie(request: CurlRequest, value: String) {
        if (value.isEmpty() || '=' !in value) {
            throw ConversionException(ErrorReason.UNSUPPORTED_OPTION, "--cookie file")
        }
        putHeader(request, "Cookie", value)
    }

    private fun putHeader(request: CurlRequest, name: String, value: String) {
        val normalizedName = normalizeHeaderName(name)
        val isUserAgent = normalizedName == "User-Agent"
        if (
            normalizedName.isEmpty() ||
            normalizedName.any { it <= ' ' || it == ':' || it.code >= 127 } ||
            value.any { it == '\r' || it == '\n' } ||
            (value.isEmpty() && !isUserAgent)
        ) {
            throw ConversionException(ErrorReason.UNSUPPORTED_OPTION, "empty header")
        }
        if (isUserAgent && value == "null") {
            throw ConversionException(ErrorReason.UNSUPPORTED_OPTION, "User-Agent: null")
        }
        if (request.headers.keys.any { it.equals(normalizedName, true) }) {
            throw ConversionException(
                ErrorReason.UNSUPPORTED_OPTION,
                "duplicate header: $normalizedName",
            )
        }
        request.headers[normalizedName] = if (isUserAgent && value.isEmpty()) "null" else value
    }

    private fun normalizeHeaderName(name: String): String {
        return when (name.lowercase()) {
            "content-type" -> "Content-Type"
            "cookie" -> "Cookie"
            "user-agent" -> "User-Agent"
            "referer" -> "Referer"
            "accept" -> "Accept"
            "proxy", "cookiejar", "content-length", "transfer-encoding" ->
                throw ConversionException(
                ErrorReason.UNSUPPORTED_OPTION,
                name,
            )
            else -> name
        }
    }

    private fun putDefaultHeader(request: CurlRequest, name: String, value: String) {
        if (request.headers.keys.none { it.equals(name, true) }) {
            request.headers[name] = value
        }
    }

    private fun setUrl(request: CurlRequest, value: String) {
        if (request.url.isNotEmpty()) {
            throw ConversionException(ErrorReason.UNSUPPORTED_OPTION, "multiple URLs")
        }
        request.url = value
    }

    private fun addBody(request: CurlRequest, option: String, value: String) {
        if (option != "--data-raw" && value.startsWith("@")) {
            throw ConversionException(ErrorReason.UNSUPPORTED_OPTION, "$option @file")
        }
        request.bodyParts += value
    }

    private fun resolveCurlMethod(request: CurlRequest): String {
        val customMethod = request.customMethod
        if (customMethod != null) validateMethod(customMethod)
        if (request.followRedirects && customMethod == "POST") {
            throw ConversionException(ErrorReason.UNSUPPORTED_OPTION, "-X POST with -L")
        }
        if (request.head) {
            if (request.bodyParts.isNotEmpty() || customMethod?.let { it != "HEAD" } == true) {
                throw ConversionException(ErrorReason.UNSUPPORTED_OPTION, "-I with $customMethod")
            }
            return "HEAD"
        }
        if (request.bodyParts.isNotEmpty()) {
            if (customMethod?.let { it != "POST" } == true) {
                throw ConversionException(
                    ErrorReason.UNSUPPORTED_OPTION,
                    "$customMethod body",
                )
            }
            return "POST"
        }
        if (customMethod == null || customMethod == "GET") return "GET"
        throw ConversionException(
            ErrorReason.UNSUPPORTED_OPTION,
            "$customMethod without body",
        )
    }

    private fun validateMethod(method: String) {
        if (method !in supportedMethods) {
            throw ConversionException(ErrorReason.UNSUPPORTED_METHOD, method)
        }
    }

    private fun effectiveAnalyzePost(body: String?, contentType: String?): EffectivePost {
        return when {
            body.isNullOrBlank() -> EffectivePost("", FORM_CONTENT_TYPE)
            !contentType.isNullOrBlank() -> EffectivePost(body, contentType)
            !contentType.isNullOrEmpty() -> EffectivePost(body, JSON_CONTENT_TYPE)
            body.isJson() || body.isXml() -> EffectivePost(body, JSON_CONTENT_TYPE)
            else -> EffectivePost(encodeAnalyzeForm(body), FORM_CONTENT_TYPE)
        }
    }

    private fun validateUrl(value: String, globOff: Boolean) {
        if (!globOff && hasCurlGlob(value)) {
            throw ConversionException(ErrorReason.UNSUPPORTED_OPTION, "URL glob")
        }
        if (AnalyzeUrl.paramPattern.matcher(value).find()) {
            throw ConversionException(ErrorReason.UNSUPPORTED_OPTION, "URL ,{")
        }
        val url = value.toHttpUrlOrNull()
            ?: throw ConversionException(ErrorReason.UNSUPPORTED_OPTION, "HTTP(S) URL")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw ConversionException(ErrorReason.UNSUPPORTED_OPTION, "URL userinfo")
        }
    }

    private fun hasCurlGlob(value: String): Boolean {
        if ('{' in value || '}' in value) return true
        if ('[' !in value && ']' !in value) return false
        val authorityStart = value.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return true
        val authorityEnd = value.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
            .takeIf { it >= 0 } ?: value.length
        val authority = value.substring(authorityStart, authorityEnd)
        val ipv6Authority = Regex("(?:[^@]+@)?\\[[0-9A-Fa-f:.%]+](?::[0-9]+)?")
        return !ipv6Authority.matches(authority) ||
            value.substring(authorityEnd).any { it == '[' || it == ']' }
    }

    private fun optionName(token: String): String {
        return if (token.startsWith("--")) token.substringBefore('=') else token.take(2)
    }

    private fun encodeAnalyzeForm(params: String): String {
        return params.split('&').joinToString("&") { field ->
            val separator = field.indexOf('=')
            if (separator < 0) {
                encodeFormPart(field)
            } else {
                encodeFormPart(field.substring(0, separator)) + "=" +
                    encodeFormPart(field.substring(separator + 1))
            }
        }
    }

    private fun encodeFormPart(value: String): String {
        return if (NetworkUtils.encodedForm(value)) {
            value
        } else {
            URLEncoder.encode(value, Charsets.UTF_8)
        }
    }

    private fun shellQuote(value: String): String {
        if (value.isNotEmpty() && safeShellValue.matches(value)) return value
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private fun tokenize(command: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var tokenStarted = false
        var index = 0

        fun pushToken() {
            if (tokenStarted) {
                tokens += current.toString()
                current.setLength(0)
                tokenStarted = false
            }
        }

        while (index < command.length) {
            val char = command[index]
            if (quote != null) {
                if (char == quote) {
                    quote = null
                    tokenStarted = true
                    index++
                    continue
                }
                if (quote == '"' && char == '\\' && index + 1 < command.length) {
                    val next = command[index + 1]
                    if (next == '\n' || next == '\r') {
                        index += if (next == '\r' && command.getOrNull(index + 2) == '\n') 3 else 2
                        continue
                    }
                    if (next in charArrayOf('\\', '"', '$', '`')) {
                        current.append(next)
                        tokenStarted = true
                        index += 2
                        continue
                    }
                }
                if (quote == '"' && (char == '$' || char == '`')) {
                    throw ConversionException(
                        ErrorReason.UNSUPPORTED_OPTION,
                        "shell expansion",
                    )
                }
                current.append(char)
                tokenStarted = true
                index++
                continue
            }

            when {
                char == '\'' || char == '"' -> {
                    quote = char
                    tokenStarted = true
                    index++
                }

                char.isWhitespace() -> {
                    pushToken()
                    index++
                }

                char == '\\' -> {
                    if (index + 1 >= command.length) {
                        throw ConversionException(ErrorReason.INVALID_CURL)
                    }
                    val next = command[index + 1]
                    if (next == '\n' || next == '\r') {
                        index += if (next == '\r' && command.getOrNull(index + 2) == '\n') 3 else 2
                    } else {
                        current.append(next)
                        tokenStarted = true
                        index += 2
                    }
                }

                char == '$' || char == '`' -> throw ConversionException(
                    ErrorReason.UNSUPPORTED_OPTION,
                    "shell expansion",
                )

                else -> {
                    current.append(char)
                    tokenStarted = true
                    index++
                }
            }
        }
        if (quote != null) throw ConversionException(ErrorReason.INVALID_CURL)
        pushToken()
        return tokens
    }
}
