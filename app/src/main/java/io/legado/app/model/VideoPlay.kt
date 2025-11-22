package io.legado.app.model

import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.core.content.edit
import com.shuyu.gsyvideoplayer.listener.GSYMediaPlayerListener
import com.shuyu.gsyvideoplayer.utils.CommonUtil
import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer
import io.legado.app.constant.AppLog
import io.legado.app.constant.SourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssReadRecord
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssStar
import io.legado.app.exception.ContentEmptyException
import io.legado.app.help.book.update
import io.legado.app.help.gsyVideo.ExoVideoManager
import io.legado.app.help.gsyVideo.ExoVideoManager.Companion.FULLSCREEN_ID
import io.legado.app.help.gsyVideo.FloatingPlayer
import io.legado.app.help.gsyVideo.VideoPlayer
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.rss.Rss
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.externalCache
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

object VideoPlay : CoroutineScope by MainScope(){
    const val VIDEO_PREF_NAME = "video_config"
    private val videoPrefs: SharedPreferences by lazy { appCtx.getSharedPreferences(VIDEO_PREF_NAME, MODE_PRIVATE) }
    /**  是否自动播放  **/
    var autoPlay
        get() = videoPrefs.getBoolean("autoPlay", true)
        set(value) {
            videoPrefs.edit { putBoolean("autoPlay", value) }
        }
    /**  直接全屏，需先启用自动播放  **/
    var startFull
        get() = videoPrefs.getBoolean("startFull", false)
        set(value) {
            videoPrefs.edit { putBoolean("startFull", value) }
        }
    /**  长按倍速  **/
    var longPressSpeed
        get() = videoPrefs.getInt("longPressSpeed", 30)
        set(value) {
            videoPrefs.edit { putInt("longPressSpeed", value) }
        }
    /**  全屏底部进度条  **/
    var fullBottomProgressBar
        get() = videoPrefs.getBoolean("fullBottomProgressBar", true)
        set(value) {
            videoPrefs.edit { putBoolean("fullBottomProgressBar", value) }
        }
    val videoManager by lazy { ExoVideoManager() }
    var videoUrl: String? = null //播放链接
    var singleUrl = false
    var videoTitle: String? = null
    var source: BaseSource? = null
    var book: Book? = null
    var toc: List<BookChapter>? =  null
    var volumes = arrayListOf<BookChapter>()
    var episodes: List<BookChapter>? =  null
    /**  在当前episodes中的位置  **/
    var chapterInVolumeIndex = 0
    /**  卷章节 -> 线路或者季数  **/
    var durVolumeIndex = 0
    /**  当前卷  **/
    var durVolume: BookChapter? = null
    /**  本集的进度  **/
    var durChapterPos = 0
    var inBookshelf = true
    /**  订阅收藏  **/
    var rssStar: RssStar? = null
    /**  订阅历史记录,收藏优先  **/
    var rssRecord: RssReadRecord? = null

    /**
     * 开始播放
     */
    fun startPlay(player: StandardGSYVideoPlayer) {
        if (source == null) return
        val player = player.getCurrentPlayer()
        durChapterPos.takeIf { it > 0 }?.toLong()?.let { player.seekOnStart = it }
        if (singleUrl) {
            if (videoUrl == null) return
            inBookshelf = true
            val analyzeUrl = AnalyzeUrl(
                videoUrl!!,
                source = source,
                ruleData = book,
                chapter = null
            )
            player.setUp(analyzeUrl.url, false, File(appCtx.externalCache, "exoplayer"),analyzeUrl.headerMap.toMap(), videoTitle)
            if (autoPlay) {
                player.startPlayLogic()
            }
            return
        }
        (source as? RssSource)?.let { s ->
            val rssArticle = rssStar?.toRssArticle() ?: rssRecord?.toRssArticle()
            if (rssArticle == null) {
                appCtx.toastOnUi("未找到订阅")
                return
            }
            val ruleContent = s.ruleContent
            if (ruleContent.isNullOrBlank()) {
                videoUrl = rssArticle.link
                val analyzeUrl = AnalyzeUrl(
                    videoUrl!!,
                    source = source,
                    ruleData = rssArticle
                )
                player.mapHeadData = analyzeUrl.headerMap
                player.setUp(analyzeUrl.url, false, File(appCtx.externalCache, "exoplayer"), videoTitle)
                if (autoPlay) {
                    player.startPlayLogic()
                }
            } else {
                Rss.getContent(this, rssArticle, ruleContent, s)
                    .onSuccess(IO) { body ->
                        if (body.isBlank()) {
                            throw ContentEmptyException("正文为空")
                        }
                        val url = NetworkUtils.getAbsoluteURL(rssArticle.link, body)
                        videoUrl = url
                        val analyzeUrl = AnalyzeUrl(
                            url,
                            source = source,
                            ruleData = rssArticle
                        )
                        withContext(Main) {
                            player.mapHeadData = analyzeUrl.headerMap
                            player.setUp(analyzeUrl.url, false, File(appCtx.externalCache, "exoplayer"), videoTitle)
                            if (autoPlay) {
                                player.startPlayLogic()
                            }
                        }
                    }.onError {
                        AppLog.put("加载为链接的正文失败", it, true)
                    }
            }
            return
        }
        if (book == null) {
            appCtx.toastOnUi("未找到书籍")
            return
        }
        val chapter = if (episodes.isNullOrEmpty()) {
            //没有卷目录，那么卷就是播放的章节（适合电影类，没有剧集，全是线路卷章节，如果全是章节没有卷的写法，播放完后会继续下一个线路重复播放）
            when {
                durVolume == null -> null
                durVolume!!.url.startsWith(durVolume!!.title) -> null //卷章节没获取到链接（链接以标题开头）则返回null
                else -> durVolume
            }
        } else {
            // 优先获取当前索引的剧集，如果不存在则尝试获取第一个剧集
            episodes?.getOrNull(chapterInVolumeIndex) ?: run {
                chapterInVolumeIndex = 0
                episodes?.getOrNull(chapterInVolumeIndex)
            }
        }
        if (chapter == null) {
            appCtx.toastOnUi("未找到章节")
            return
        }
        WebBook.getContent(this, source as BookSource, book!!, chapter)
            .onSuccess(IO) { content ->
                if (content.isEmpty()) {
                    appCtx.toastOnUi("未获取到资源链接")
                } else {
                    videoUrl = content
                    val analyzeUrl = AnalyzeUrl(
                        content,
                        source = source,
                        ruleData = book,
                        chapter = chapter
                    )
                    withContext(Main) {
                        player.mapHeadData = analyzeUrl.headerMap
                        player.setUp(analyzeUrl.url, false, File(appCtx.externalCache, "exoplayer"), videoTitle)
                        if (autoPlay) {
                            player.startPlayLogic()
                        }
                    }
                }
            }.onError {
                AppLog.put("获取资源链接出错\n$it", it, true)
            }
    }

    /**
     * 退出全屏，主要用于返回键
     *
     * @return 返回是否全屏
     */
    fun backFromWindowFull(context: Context?): Boolean {
        var backFrom = false
        val vp =
            (CommonUtil.scanForActivity(context)).findViewById<View?>(Window.ID_ANDROID_CONTENT) as ViewGroup
        val oldF = vp.findViewById<View?>(FULLSCREEN_ID)
        if (oldF != null) {
            backFrom = true
            CommonUtil.hideNavKey(context)
            if (videoManager.lastListener() != null) {
                videoManager.lastListener().onBackFullscreen()
            }
        }
        return backFrom
    }
    /**
     * 页面销毁了记得调用是否所有的video
     */
    fun releaseAllVideos() {
        if (videoManager.listener() != null) {
            videoManager.listener().onCompletion()
        }
        videoManager.releaseMediaPlayer()
        //还原所有状态
        videoUrl = null
        singleUrl = false
        videoTitle = null
        source = null
        book = null
        toc = null
        volumes.clear()
        episodes = null
        chapterInVolumeIndex = 0
        durVolumeIndex = 0
        durVolume = null
        durChapterPos = 0
        inBookshelf = true
        rssStar = null
        rssRecord = null
    }
    /**
     * 暂停播放
     */
    fun onPause() {
        if (videoManager.listener() != null) {
            videoManager.listener().onVideoPause()
        }
    }

    /**
     * 恢复播放
     */
    fun onResume() {
        if (videoManager.listener() != null) {
            videoManager.listener().onVideoResume()
        }
    }


    /**
     * 恢复暂停状态
     *
     * @param seek 是否产生seek动作,直播设置为false
     */
    fun onResume(seek: Boolean) {
        if (videoManager.listener() != null) {
            videoManager.listener().onVideoResume(seek)
        }
    }

    /**
     * 播放器移植 - 辅助函数
     *
     *
     */
    @SuppressLint("StaticFieldLeak")
    private var sSwitchVideo: StandardGSYVideoPlayer? = null
    private var sMediaPlayerListener: GSYMediaPlayerListener? = null
    fun savePlayState(switchVideo: StandardGSYVideoPlayer) {
        when (switchVideo) {
            is VideoPlayer -> sSwitchVideo = switchVideo.saveState()
            is FloatingPlayer -> sSwitchVideo = switchVideo.saveState()
        }
        sMediaPlayerListener = switchVideo
    }

    fun clonePlayState(switchVideo: StandardGSYVideoPlayer) {
        when (switchVideo) {
            is VideoPlayer -> sSwitchVideo?.let { switchVideo.cloneState(it) }
            is FloatingPlayer -> sSwitchVideo?.let { switchVideo.cloneState(it) }
        }
    }

    fun release() {
        sMediaPlayerListener?.onAutoCompletion()
        sSwitchVideo = null
        sMediaPlayerListener = null
    }

    fun initSource(sourceKey: String?, sourceType: Int?, bookUrl: String?, record:String?): Boolean {
        source = sourceKey?.let {
            when (sourceType) {
                SourceType.book -> appDb.bookSourceDao.getBookSource(it)
                SourceType.rss -> appDb.rssSourceDao.getByKey(it)
                else -> null
            }
        }
        book = bookUrl?.let {
            toc = appDb.bookChapterDao.getChapterList(it)
            volumes.clear()
            toc?.forEach { t ->
                if (t.isVolume) {
                    volumes.add(t)
                }
            }
            appDb.bookDao.getBook(it) ?: appDb.searchBookDao.getSearchBook(it)?.toBook()
        }?.also { b ->
            chapterInVolumeIndex = b.chapterInVolumeIndex
            durVolumeIndex = b.durVolumeIndex
            durChapterPos = b.durChapterPos
            source = appDb.bookSourceDao.getBookSource(b.origin)
        }
        upEpisodes()
        if (source == null) {
            appCtx.toastOnUi("未找到源")
            return false
        }
        record?.let{ //订阅源
            rssStar =appDb.rssStarDao.get(sourceKey!!, it)?.also{ r ->
                durChapterPos = r.durPos
            }
            if (rssStar == null) {
                rssRecord = appDb.rssReadRecordDao.getRecord(it,sourceKey)?.also{ r ->
                    durChapterPos = r.durPos
                }
            }
        }
        return true
    }

    fun upSource() {
        when (source) {
            is BookSource -> {
                source = appDb.bookSourceDao.getBookSource(source!!.getKey())
            }
            is RssSource -> {
                source = appDb.rssSourceDao.getByKey(source!!.getKey())
            }
        }
    }

    fun upEpisodes() {
        if (volumes.isEmpty()) {
            durVolume = null
            episodes = toc
            return
        }
        durVolume = volumes.getOrNull(durVolumeIndex)
        if (durVolume == null) {
            durVolumeIndex = 0
            durVolume = volumes.getOrNull(durVolumeIndex)
        }
        val startInt = durVolume?.index ?: 0
        val endInt = volumes.getOrNull(durVolumeIndex + 1)?.index ?: toc!!.size
        episodes = toc!!.subList(startInt + 1, endInt)
    }

    fun upDurIndex(offset: Int): Boolean {
        episodes ?: return false
        val index = chapterInVolumeIndex + offset
        if (index < 0 || index >= episodes!!.size) {
            appCtx.toastOnUi("已播放完")
            return false
        }
        chapterInVolumeIndex = index
        durChapterPos = 0
        return true
    }

    fun saveRead() {
        book?.let { book ->
            book.lastCheckCount = 0
            book.durChapterTime = System.currentTimeMillis()
            book.durVolumeIndex = durVolumeIndex
            book.chapterInVolumeIndex = chapterInVolumeIndex
            val durChapterIndex = if (volumes.isEmpty()) chapterInVolumeIndex else
                (durVolume?.index ?: 0) + chapterInVolumeIndex + 1
            book.durChapterIndex = durChapterIndex
            book.durChapterPos = durChapterPos
            videoTitle = toc?.getOrNull(durChapterIndex)?.title
            book.durChapterTitle = videoTitle
            book.update()
        }
        rssStar?.let {
            it.durPos = durChapterPos
            videoTitle = it.title
            appDb.rssStarDao.update(it)
        }
        rssRecord?.let {
            it.durPos = durChapterPos
            videoTitle = it.title
            appDb.rssReadRecordDao.update(it)
        }
    }
}