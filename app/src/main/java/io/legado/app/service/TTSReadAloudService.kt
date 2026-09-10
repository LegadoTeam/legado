package io.legado.app.service

import android.app.PendingIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicLong

internal fun pendingSpeechPageMoves(currentPageIndex: Int, targetPageIndex: Int): Int =
    (targetPageIndex - currentPageIndex).coerceAtLeast(0)

/**
 * 本地朗读
 */
class TTSReadAloudService : BaseReadAloudService(), TextToSpeech.OnInitListener {

    private var textToSpeech: TextToSpeech? = null
    private var ttsInitFinish = false
    private val ttsUtteranceListener = TTSUtteranceListener()
    private var speakJob: Coroutine<*>? = null
    private val playbackSessionId = AtomicLong()
    private val callbackHandler by lazy { buildMainHandler() }
    private val TAG = "TTSReadAloudService"

    override fun onCreate() {
        super.onCreate()
        kotlin.runCatching {
            initTts()
        }.onFailure {
            AppLog.put("${getString(R.string.tts_init_failed)}\n$it", it, true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clearTTS()
    }

    @Synchronized
    private fun initTts() {
        ttsInitFinish = false
        val engine = GSON.fromJsonObject<SelectItem<String>>(ReadAloud.ttsEngine).getOrNull()?.value
        LogUtils.d(TAG, "initTts engine:$engine")
        textToSpeech = if (engine.isNullOrBlank()) {
            TextToSpeech(this, this)
        } else {
            TextToSpeech(this, this, engine)
        }
        upSpeechRate()
    }

    @Synchronized
    fun clearTTS() {
        playbackSessionId.incrementAndGet()
        textToSpeech?.runCatching {
            stop()
            shutdown()
        }
        textToSpeech = null
        ttsInitFinish = false
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let {
                it.setOnUtteranceProgressListener(ttsUtteranceListener)
                ttsInitFinish = true
                play()
            }
        } else {
            toastOnUi(R.string.tts_init_failed)
        }
    }

    @Synchronized
    override fun play() {
        val sessionId = playbackSessionId.incrementAndGet()
        if (!ttsInitFinish) return
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
            return
        }
        super.play()
        MediaHelp.playSilentSound(this@TTSReadAloudService)
        speakJob?.cancel()
        val startSpeak = nowSpeak
        val startParagraphPos = paragraphStartPos
        speakJob = execute {
            LogUtils.d(TAG, "朗读列表大小 ${contentList.size}")
            LogUtils.d(TAG, "朗读页数 ${textChapter?.pageSize}")
            if (textToSpeech == null) throw NoStackTraceException("tts is null")
            val contentList = contentList
            var isAddedText = false
            for (i in startSpeak until contentList.size) {
                ensureActive()
                if (!isCurrentPlayback(sessionId)) return@execute
                val paragraphText = contentList[i]
                val paragraph = checkNotNull(textChapter).getParagraphs(readAloudByPage)[i]
                val firstOffset = if (i == startSpeak) startParagraphPos else 0
                val text = paragraphText.substring(firstOffset)
                if (text.matches(AppPattern.notReadAloudRegex)) continue
                var chunkStart = paragraph.chapterPosition + firstOffset
                val textEnd = chunkStart + text.length
                // Queue page boundaries ahead of playback: engines without range callbacks still
                // report the next page's real start, without an application pause or queue flush.
                val boundaries = checkNotNull(textChapter).pages.map { it.chapterPosition }
                    .filter { it > chunkStart && it < textEnd } + textEnd
                for (chunkEnd in boundaries) {
                    val chunk = paragraphText.substring(
                        chunkStart - paragraph.chapterPosition, chunkEnd - paragraph.chapterPosition
                    )
                    val result = speakCurrent(
                        sessionId, chunk,
                        if (isAddedText) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH,
                        i, chunkStart, chunkEnd == textEnd
                    ) ?: return@execute
                    if (result == TextToSpeech.ERROR) {
                        if (!isAddedText) {
                            AppLog.put("tts出错 尝试重新初始化")
                            clearTTS()
                            initTts()
                            return@execute
                        }
                        AppLog.put("tts朗读出错:$chunk")
                    }
                    isAddedText = true
                    chunkStart = chunkEnd
                }
            }
            LogUtils.d(TAG, "朗读内容添加完成")
            if (!isAddedText && isCurrentPlayback(sessionId)) {
                playStop()
                val stoppedSessionId = playbackSessionId.get()
                delay(1000)
                if (stoppedSessionId == playbackSessionId.get()) nextChapter(auto = true)
            }
        }.onError {
            AppLog.put("tts朗读出错\n${it.localizedMessage}", it, true)
        }
    }

    @Synchronized
    override fun playStop() {
        playbackSessionId.incrementAndGet()
        speakJob?.cancel()
        textToSpeech?.runCatching {
            stop()
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        if (AppConfig.ttsFlowSys) {
            if (reset) {
                clearTTS()
                initTts()
            }
        } else {
            val speechRate = (AppConfig.ttsSpeechRate + 5) / 10f
            textToSpeech?.setSpeechRate(speechRate)
        }
    }

    /**
     * 暂停朗读
     */
    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        playStop()
    }

    /**
     * 恢复朗读
     */
    override fun resumeReadAloud() {
        super.resumeReadAloud()
        play()
    }

    /**
     * 朗读监听
     */
    private inner class TTSUtteranceListener : UtteranceProgressListener() {

        private val TAG = "TTSUtteranceListener"
        private var startCallbackLogged = false
        private var rangeCallbackLogged = false

        override fun onStart(s: String) {
            dispatchCurrentCallback(s) {
                val msg = "onStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$s"
                LogUtils.d(TAG, msg)
                if (AppConfig.recordLog && !startCallbackLogged) {
                    startCallbackLogged = true
                    AppLog.putDebug("$TAG $msg")
                }
                if (textChapter != null) {
                    if (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex)) {
                        nextParagraph()
                    }
                    val position = utterancePosition(s)
                    moveToSpeechPage(position)
                    upTtsProgress(position)
                }
            }
        }

        override fun onDone(s: String) {
            dispatchCurrentCallback(s) {
                LogUtils.d(TAG, "onDone utteranceId:$s")
                if (s.substringAfterLast(':') != "false") nextParagraph()
            }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            super.onRangeStart(utteranceId, start, end, frame)
            dispatchCurrentCallback(utteranceId) {
                val msg =
                    "onRangeStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId start:$start end:$end frame:$frame"
                LogUtils.d(TAG, msg)
                if (AppConfig.recordLog && !rangeCallbackLogged) {
                    rangeCallbackLogged = true
                    AppLog.putDebug("$TAG $msg")
                }
                val position = utterancePosition(utteranceId) + start
                moveToSpeechPage(position)
                upTtsProgress(position)
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            dispatchCurrentCallback(utteranceId) {
                LogUtils.d(
                    TAG,
                    "onError nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId errorCode:$errorCode"
                )
                if (utteranceId?.substringAfterLast(':') != "false") nextParagraph()
            }
        }

        private fun nextParagraph() {
            //跳过全标点段落
            do {
                paragraphStartPos = 0
                nowSpeak++
                if (nowSpeak >= contentList.size) {
                    nextChapter(auto = true)
                    return
                }
                readAloudNumber = checkNotNull(textChapter).getParagraphs(readAloudByPage)[nowSpeak].chapterPosition
            } while (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex))
        }

        @Deprecated("Deprecated in Java")
        override fun onError(s: String) {
            dispatchCurrentCallback(s) {
                LogUtils.d(TAG, "onError nowSpeak:$nowSpeak pageIndex:$pageIndex s:$s")
                if (s.substringAfterLast(':') != "false") nextParagraph()
            }
        }

        private fun moveToSpeechPage(position: Int): Boolean {
            val targetPageIndex = textChapter?.getPageIndexByCharIndex(position) ?: return false
            val moves = pendingSpeechPageMoves(pageIndex, targetPageIndex)
            repeat(moves) {
                pageIndex++
                ReadBook.moveToNextPage(syncReadAloudFollow = true)
            }
            return moves > 0
        }

    }

    private fun utterancePosition(id: String?): Int =
        id?.split(':', limit = 5)?.getOrNull(3)?.toIntOrNull() ?: readAloudNumber

    private fun utteranceId(sessionId: Long, index: Int, position: Int, last: Boolean): String {
        return "${AppConst.APP_TAG}:$sessionId:$index:$position:$last"
    }

    private fun speakCurrent(
        sessionId: Long,
        text: String,
        queueMode: Int,
        index: Int,
        position: Int,
        last: Boolean
    ): Int? {
        synchronized(this) {
            if (!isCurrentPlayback(sessionId)) return null
            val tts = textToSpeech ?: return TextToSpeech.ERROR
            return tts.runCatching {
                speak(text, queueMode, null, utteranceId(sessionId, index, position, last))
            }.getOrElse {
                AppLog.put("tts出错\n${it.localizedMessage}", it, true)
                TextToSpeech.ERROR
            }
        }
    }

    private fun isCurrentPlayback(sessionId: Long): Boolean {
        return sessionId == playbackSessionId.get()
    }

    private fun dispatchCurrentCallback(utteranceId: String?, block: () -> Unit) {
        val prefix = "${AppConst.APP_TAG}:"
        val sessionId = utteranceId
            ?.takeIf { it.startsWith(prefix) }
            ?.substringAfter(prefix)
            ?.substringBefore(':')
            ?.toLongOrNull()
            ?: return
        if (sessionId != playbackSessionId.get()) return
        callbackHandler.post {
            synchronized(this) {
                if (sessionId == playbackSessionId.get()) block()
            }
        }
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<TTSReadAloudService>(actionStr)
    }

}
