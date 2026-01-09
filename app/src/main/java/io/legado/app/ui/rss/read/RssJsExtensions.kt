package io.legado.app.ui.rss.read

import android.webkit.JavascriptInterface
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssReadRecord
import io.legado.app.data.entities.RssSource
import io.legado.app.help.JsExtensions
import io.legado.app.ui.association.AddToBookshelfDialog
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.rss.article.RssSortActivity
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.lang.ref.WeakReference


@Suppress("unused")
open class RssJsExtensions(activity: AppCompatActivity, source: BaseSource?) : JsExtensions {

    val activityRef: WeakReference<AppCompatActivity> = WeakReference(activity)
    val sourceRef: WeakReference<BaseSource?> = WeakReference(source)

    override fun getSource(): BaseSource? {
        return sourceRef.get()
    }

    @JavascriptInterface
    fun put(key: String, value: String): String {
        getSource()?.put(key, value)
        return value
    }

    @JavascriptInterface
    fun get(key: String): String {
        return getSource()?.get(key) ?: ""
    }

    @JavascriptInterface
    fun searchBook(key: String) {
        searchBook(key, null)
    }

    @JavascriptInterface
    fun searchBook(key: String, searchScope: String?) {
        activityRef.get()?.let {
            SearchActivity.start(it, key, searchScope)
        }
    }

    @JavascriptInterface
    fun addBook(bookUrl: String) {
        activityRef.get()?.showDialogFragment(AddToBookshelfDialog(bookUrl))
    }

    @JavascriptInterface
    fun open(name: String, url: String) {
        return open(name, url, null)
    }

    @JavascriptInterface
    fun open(name: String, url: String?, title: String?) {
        return open(name, url, title, null)
    }

    @JavascriptInterface
    fun open(name: String, url: String?, title: String?, origin: String?) {
        activityRef.get()?.lifecycleScope?.launch(IO) {
            val source = getSource() ?: return@launch
            when (name) {
                "sort" -> {
                    val toSource = origin?.let { o ->
                        appDb.rssSourceDao.getByKey(o)
                    } ?: (source as? RssSource) ?: return@launch
                    val sortUrl = if (url.isJsonObject()) {
                        url
                    } else {
                        title?.let {
                            JSONObject().put(title, url).toString()
                        } ?: url
                    }
                    val sourceUrl = toSource.sourceUrl
                    withContext(Main) {
                        activityRef.get()?.let {
                            RssSortActivity.start(it, sortUrl, sourceUrl)
                        }
                    }
                }

                "rss" -> {
                    val toSource = origin?.let { o ->
                        appDb.rssSourceDao.getByKey(o)
                    } ?: (source as? RssSource) ?: return@launch
                    val title = title ?: toSource.sourceName
                    val sourceUrl = toSource.sourceUrl
                    val link = url ?: return@launch
                    val rss =appDb.rssStarDao.get(sourceUrl, link)?.toRecord() ?: appDb.rssArticleDao.getByLink(sourceUrl, link)?.toRecord()
                    val rssReadRecord = rss ?: RssReadRecord(
                        record = link,
                        title = title,
                        origin = sourceUrl,
                        readTime = System.currentTimeMillis()
                    )
                    appDb.rssReadRecordDao.insertRecord(rssReadRecord) //留下历史记录
                    withContext(Main) {
                        activityRef.get()?.let {
                            ReadRssActivity.start(it, title, url, sourceUrl)
                        }
                    }
                }

                "search" -> {
                    title?.let {
                        val searchScope = origin?.let { o  ->
                            appDb.bookSourceDao.getBookSource(o)?.let { s ->
                                "${s.bookSourceName.replace(":", "")}::${o}"
                            }
                        }
                        withContext(Main) {
                            searchBook(it, searchScope)
                        }
                    }
                }

                "explore" -> {
                    val toSource = origin?.let { o ->
                        appDb.bookSourceDao.getBookSource(o)
                    } ?: (source as? BookSource) ?: return@launch
                    val sourceUrl = toSource.bookSourceUrl
                    withContext(Main) {
                        activityRef.get()?.run {
                            startActivity<ExploreShowActivity> {
                                putExtra("exploreName", title)
                                putExtra("sourceUrl", sourceUrl)
                                putExtra("exploreUrl", url)
                            }
                        }
                    }
                }
            }
        }
    }

}
