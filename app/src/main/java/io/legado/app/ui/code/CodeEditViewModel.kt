package io.legado.app.ui.code

import android.app.Application
import android.content.Intent
import com.script.ScriptException
import com.script.rhino.RhinoScriptEngine
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.help.CacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.BackstageWebView
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.toastOnUi
import org.eclipse.tm4e.core.registry.IThemeSource
import org.jsoup.Jsoup
import splitties.init.appCtx

class CodeEditViewModel(application: Application) : BaseViewModel(application) {
    private val beautifyJs by lazy {
        appCtx.assets.open("scripts/beautify.min.js").bufferedReader().use { it.readText() }
    }
    private val themeFileNames = arrayOf(
        "d_monokai_dimmed",
        "d_monokai",
        "d_modern",
        "l_modern",
        "d_solarized",
        "l_solarized",
        "d_abyss",
        "l_quiet"
    )

    var initialText = ""
    var cursorPosition = 0
    internal var language: RuntimeObjectCompletionLanguage? = null
    private var languageName = "source.js"
    private val themeRegistry: ThemeRegistry = ThemeRegistry.getInstance()
    var writable = true
    var title: String? = null
    internal var canCheckJavaScriptSyntax = false
        private set

    fun initSora() {
        //初始化sora加载
        FileProviderRegistry.getInstance().addFileProvider(
            AssetsFileResolver(appCtx.assets)
        )
        GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
    }

    fun initData(
        intent: Intent, success: () -> Unit
    ) {
        execute {
            val cacheKey = intent.getStringExtra("cacheKey")
            if (cacheKey != null) {
                val cacheText = CacheManager.getFromMemory(cacheKey) as? String ?: throw Exception("未获取到查看文本")
                writable = false
                initialText = cacheText
            } else {
                initialText = intent.getStringExtra("text") ?: throw Exception("未获取到待编辑文本")
            }
            if (isHtmlStr(initialText)) {
                languageName = "text.html.basic"
            } else {
                intent.getStringExtra("languageName")?.let { languageName = it }
            }
            language = RuntimeObjectCompletionLanguage(
                TextMateLanguage.create(languageName, AppConfig.editAutoComplete)
            )
            cursorPosition = intent.getIntExtra("cursorPosition", 0)
            title = intent.getStringExtra("title")
            canCheckJavaScriptSyntax = intent.getBooleanExtra(
                CodeEditActivity.EXTRA_CHECK_JAVASCRIPT_SYNTAX,
                false,
            )
        }.onSuccess {
            success.invoke()
        }.onError {
            context.toastOnUi("error\n${it.localizedMessage}")
            it.printOnDebug()
        }
    }

    private fun isHtmlStr(text: String): Boolean {
        val trimmedText = text.trim()
        val htmlRegex = Regex("""^(?:\[[\s\d.]])?<(?:html|!DOCTYPE)""", RegexOption.IGNORE_CASE)
        return htmlRegex.containsMatchIn(trimmedText) && trimmedText.endsWith(">")
    }

    fun loadTextMateThemes(index: Int) {
        val theme = themeFileNames.getOrElse(index) { "d_monokai" }
        val themeModel = themeRegistry.findThemeByFileName(theme)
        if (themeModel == null) {
            val themeAssetsPath = "textmate/$theme.json"
            val themeSource = IThemeSource.fromInputStream(
                FileProviderRegistry.getInstance().tryGetInputStream(themeAssetsPath),
                themeAssetsPath,
                null
            )
            themeRegistry.loadTheme(ThemeModel(themeSource, theme).apply {
                isDark = theme.startsWith("d_")
            })
        } else {
            themeRegistry.setTheme(themeModel)
        }
    }

    fun formatCode(editor: CodeEditor) {
        val source = editor.text.toString()
        execute {
            val text = source
            if (languageName.contains("markdown")) {
                context.toastOnUi("markdown不需要格式化")
                return@execute text
            }
            val isHtml = languageName.contains("html")
            if (isHtml) {
                return@execute formatCodeHtml(text)
            }
            formatRuleExpression(text, ::webFormatCode)?.let {
                return@execute it
            }
            var result = ""
            var start = 0
            val indexS = text.indexOf("<js>")
            if (indexS >= 0) {
                if (indexS > 0) {
                    result += text.substring(start, indexS).trim()
                }
                val indexE = text.indexOf("</js>", indexS)
                val jsCode = text.substring(indexS + 4, indexE)
                result += "<js>\n"
                result += webFormatCode(jsCode)
                result += "\n</js>"
                start = indexE + 5
            }
            val indexS2 = text.indexOf("@js:")
            if (indexS2 >= 0) {
                if (indexS2 > start) {
                    result += text.substring(start, indexS2).trim()
                }
                val jsCode = text.substring(indexS2 + 4)
                result += "@js:\n"
                result += webFormatCode(jsCode)
                start = text.length
            } else {
                val indexS2 = text.indexOf("@webjs:")
                if (indexS2 >= 0) {
                    if (indexS2 > start) {
                        result += text.substring(start, indexS2).trim()
                    }
                    val jsCode = text.substring(indexS2 + 7)
                    result += "@webjs:\n"
                    result += webFormatCode(jsCode)
                    start = text.length
                }
            }
            if (start == 0) {
                result += webFormatCode(text)
                start = text.length
            }
            if (text.length > start) {
                result += text.substring(start).trim()
            }
            result
        }.onSuccess { formatted ->
            if (formatted != null && formatted != source && editor.text.toString() == source) {
                editor.text.replace(0, editor.text.length, formatted)
            }
        }.onError {
            AppLog.put("格式化失败",it, true)
        }
    }

    fun checkJavaScriptSyntax(editor: CodeEditor) {
        val source = editor.text.toString()
        executeLazy {
            RhinoScriptEngine.compile(source)
        }.onSuccess {
            if (editor.text.toString() == source) {
                context.toastOnUi(R.string.javascript_syntax_correct)
            }
        }.onError { error ->
            if (editor.text.toString() != source) return@onError
            (error as? ScriptException)?.takeIf { it.lineNumber > 0 }?.let {
                val index = scriptSourceIndex(source, it.lineNumber, it.columnNumber)
                val position = editor.cursor.indexer.getCharPosition(index)
                editor.setSelection(position.line, position.column, true)
                editor.requestFocus()
            }
            AppLog.put(
                error.localizedMessage ?: context.getString(R.string.javascript_syntax_error),
                error,
                true,
            )
        }.start()
    }

    private suspend fun webFormatCode(jsCode: String): String? {
        CacheManager.putMemory("web_format_code", jsCode)
        return BackstageWebView(
            url = null,
            html = """<html><body><script>
                $beautifyJs
                window.re = js_beautify($nameCache.getFromMemory('web_format_code'), {
                indent_size: 4,
                indent_char: ' ',
                preserve_newlines: true,
                max_preserve_newlines: 5,
                brace_style: 'collapse',
                space_before_conditional: true,
                unescape_strings: false,
                jslint_happy: false,
                end_with_newline: false,
                wrap_line_length: 0,
                comma_first: false
                });
                </script></body></html>""".trimIndent(),
            javaScript = "window.re",
            timeout = 5000,
            isRule = true
        ).getStrResponse().body
    }

    private fun formatCodeHtml(html: String): String? {
        val doc = Jsoup.parse(html)
        doc.outputSettings()
            .indentAmount(4)
            .prettyPrint(true)
        return doc.outerHtml()
    }

}

internal suspend fun formatRuleExpression(
    text: String,
    formatter: suspend (String) -> String?
): String? {
    val matcher = AppPattern.EXP_PATTERN.matcher(text.trim())
    if (!matcher.matches()) return null
    val body = matcher.group(1).trim()
    val formattedBody = if (body.isEmpty()) body else formatter(body) ?: body
    return "{{$formattedBody}}"
}

internal fun scriptSourceIndex(source: String, lineNumber: Int, columnNumber: Int): Int {
    if (lineNumber <= 0) return 0
    var lineStart = 0
    repeat(lineNumber - 1) {
        val lineEnd = source.indexOf('\n', lineStart)
        if (lineEnd < 0) return source.length
        lineStart = lineEnd + 1
    }
    val lineEnd = source.indexOf('\n', lineStart).takeIf { it >= 0 } ?: source.length
    return (lineStart + (columnNumber - 1).coerceAtLeast(0)).coerceAtMost(lineEnd)
}
