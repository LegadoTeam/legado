package io.legado.app.ui.code

import android.os.Bundle
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.CodeEditor

internal class RuntimeObjectCompletionLanguage(
    private val delegate: TextMateLanguage,
) : Language by delegate {

    var isAutoCompleteEnabled: Boolean
        get() = delegate.isAutoCompleteEnabled
        set(value) {
            delegate.isAutoCompleteEnabled = value
        }

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle,
    ) {
        if (!isAutoCompleteEnabled) return
        val context = contextBeforeCursor(
            content.getLine(position.line),
            position.column,
        )
        val result = context?.let(::complete)
        if (result == null) {
            delegate.requireAutoComplete(content, position, publisher, extraArguments)
            return
        }
        publisher.addItems(result.suggestions.map { suggestion ->
            RuntimeCompletionItem(result, suggestion)
        })
    }

    private class RuntimeCompletionItem(
        result: CompletionResult,
        private val suggestion: Suggestion,
    ) : SimpleCompletionItem(
        suggestion.label,
        result.objectName,
        result.prefix.length,
        suggestion.commitText,
    ) {
        init {
            kind(suggestion.kind)
        }

        override fun performCompletion(
            editor: CodeEditor,
            text: Content,
            line: Int,
            column: Int,
        ) {
            super.performCompletion(editor, text, line, column)
            if (suggestion.cursorOffset != 0) {
                editor.setSelection(
                    line,
                    column - prefixLength + commitText.length + suggestion.cursorOffset,
                )
            }
        }
    }

    internal data class CompletionResult(
        val objectName: String,
        val prefix: String,
        val suggestions: List<Suggestion>,
    )

    internal data class Suggestion(
        val label: String,
        val commitText: String,
        val cursorOffset: Int,
        val kind: CompletionItemKind,
    )

    private data class MemberGroup(
        val methods: List<String> = emptyList(),
        val properties: List<String> = emptyList(),
    )

    companion object {
        private const val MAX_CONTEXT_LENGTH = 128
        private val contextPattern = Regex(
            "(?:^|[^A-Za-z0-9_$.])([A-Za-z_$][A-Za-z0-9_$]*)\\." +
                "([A-Za-z0-9_$]*)${'$'}"
        )

        private val javaMethods = words(
            """
            ajax ajaxAll ajaxTestAll androidId() base64Decode base64DecodeToByteArray
            base64Encode bytesToStr cacheFile connect createAsymmetricCrypto createSign
            createSymmetricCrypto deleteFile digestBase64Str digestHex downloadFile encodeURI
            get get7zByteArrayContent get7zStringContent getCookie getFile getRarByteArrayContent
            getRarStringContent getReadBookConfig() getReadBookConfigMap() getSource() getTag()
            getThemeConfig() getThemeConfigMap() getThemeMode() getTxtInFolder
            getVerificationCode getWebViewUA() getZipByteArrayContent getZipStringContent head
            hexDecodeToByteArray hexDecodeToString hexEncodeToString HMacBase64 HMacHex
            htmlFormat importScript lock log logType longToast md5Encode md5Encode16 openUrl
            openVideoPlayer post queryBase64TTF queryTTF randomUUID() readFile readTxtFile
            replaceFont s2t showBrowser singleFlight startBrowser startBrowserAwait strToBytes
            t2s tick timeFormat timeFormatUTC toast toNumChapter toURL un7zFile
            unArchiveFile unrarFile unzipFile webView webViewGetOverrideUrl webViewGetSource
            """
        )

        private val groups = mapOf(
            "java" to MemberGroup(methods = javaMethods),
            "source" to MemberGroup(
                methods = words(
                    """
                    evalJS evalLoginActionV2 evalLoginUiV2 get getHeaderMap getKey()
                    getLoginHeader() getLoginHeaderMap() getLoginInfo() getLoginInfoMap()
                    getLoginJs() getLoginUiJs() getSource() getTag() getVariable() hasLogin()
                    hasLoginForm() isLoginUiV2() login() put putConcurrent putLoginHeader
                    putLoginInfo putVariable refreshExplore() refreshJSLib() removeLoginHeader()
                    removeLoginInfo() setVariable
                    """
                ),
                properties = words(
                    "concurrentRate enabledCookieJar header jsLib loginUi loginUrl"
                ),
            ),
            "config" to MemberGroup(
                properties = words(
                    """
                    bookSourceComment bookSourceGroup bookSourceName bookSourceType bookSourceUrl
                    bookUrlPattern concurrentRate coverDecodeJs customButton enabledCookieJar
                    eventListener exploreUrl header jsLib lastUpdateTime loginCheckJs loginUi
                    loginUrl variableComment
                    """
                ),
            ),
            "cookie" to MemberGroup(
                methods = words(
                    """
                    clear() cookieToMap getCookie getKey mapToCookie removeCookie replaceCookie
                    setCookie setWebCookie
                    """
                ),
            ),
            "cache" to MemberGroup(
                methods = words(
                    """
                    delete deleteMemory get getByteArray getDouble getFile getFloat getFromMemory
                    getInt getLong put putFile putMemory
                    """
                ),
            ),
            "book" to MemberGroup(
                methods = words(
                    """
                    addDelTag createBookMark() delete() equals fileCharset() getBigVariable
                    getCloseCredits() getCoverSourceOrigin() getCustomVariable() getDailyChapters()
                    getDelTag getDisplayCover() getDisplayIntro() getFolderName() getImageStyle()
                    getKindList() getOpenCredits() getPageAnim() getPlayMode() getPlaySpeed()
                    getVariable
                    getReadSimulating() getRealAuthor() getReSegment() getReverseToc()
                    getSplitLongChapter() getStartChapter() getStartDate() getTtsEngine()
                    getUnreadChapterNum() getUseReplaceRule() hashCode() migrateTo putBigVariable
                    putCustomVariable putVariable removeDelTag save() setCloseCredits
                    setDailyChapters setImageStyle setOpenCredits setPageAnim setPlayMode setPlaySpeed
                    setReadSimulating setReSegment setReverseToc setSplitLongChapter setStartChapter
                    setStartDate setTtsEngine setUseReplaceRule toReplaceBook() toSearchBook()
                    upCustomIntro()
                    """
                ),
                properties = words(
                    """
                    author bookUrl canUpdate charset chapterInVolumeIndex closeCredits config coverUrl
                    customCoverUrl customIntro customTag dailyChapters downloadUrls durChapterIndex
                    durChapterPos durChapterTime durChapterTitle durVolumeIndex group imageStyle
                    infoHtml intro kind lastChapterIndex lastCheckCount lastCheckTime latestChapterTime
                    latestChapterTitle name openCredits order origin originName originOrder pageAnim
                    persistedCoverUrl playMode playSpeed readConfig readSimulating reSegment reverseToc splitLongChapter
                    startChapter startDate syncTime tocHtml tocUrl totalChapterNum ttsEngine type
                    useReplaceRule variable variableMap wordCount
                    """
                ),
            ),
            "chapter" to MemberGroup(
                methods = words(
                    """
                    equals getAbsoluteURL() getBigVariable getDisplayTitle getFileName getFontName()
                    getVariable hashCode() isPay() isVip() isVolume() primaryStr() putBigVariable putDanmaku
                    putImgUrl putLyric putVariable update()
                    """
                ),
                properties = words(
                    """
                    baseUrl bookUrl end endFragmentId imgUrl index resourceUrl start startFragmentId
                    tag title titleMD5 url variable variableMap wordCount
                    """
                ),
            ),
        )

        internal fun complete(lineBeforeCursor: String): CompletionResult? {
            val match = contextPattern.find(lineBeforeCursor) ?: return null
            val objectName = match.groupValues[1]
            val group = groups[objectName] ?: return null
            val prefix = match.groupValues[2]
            val suggestions = buildList {
                group.properties.forEach { property ->
                    if (property.startsWith(prefix) && property != prefix) {
                        add(
                            Suggestion(
                                property,
                                property,
                                0,
                                CompletionItemKind.Property,
                            )
                        )
                    }
                }
                group.methods.forEach { method ->
                    val noArguments = method.endsWith("()")
                    val name = method.removeSuffix("()")
                    if (name.startsWith(prefix)) {
                        add(
                            Suggestion(
                                method,
                                "$name()",
                                if (noArguments) 0 else -1,
                                CompletionItemKind.Method,
                            )
                        )
                    }
                }
            }.distinctBy { it.label }.sortedBy { it.label }
            return CompletionResult(objectName, prefix, suggestions)
        }

        internal fun contextBeforeCursor(line: CharSequence, column: Int): String? {
            var start = column
            val limit = maxOf(0, column - MAX_CONTEXT_LENGTH)
            while (start > limit && isContextChar(line[start - 1])) {
                start--
            }
            if (start == limit && start > 0 && isContextChar(line[start - 1])) {
                return null
            }
            return line.subSequence(start, column).toString()
        }

        private fun isContextChar(char: Char): Boolean {
            return char == '_' || char == '$' || char == '.' ||
                char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9'
        }

        private fun words(value: String): List<String> {
            return value.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        }
    }
}
