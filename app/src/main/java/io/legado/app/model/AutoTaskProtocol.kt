package io.legado.app.model

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.isLocal
import io.legado.app.model.jsSource.JsSourceEngine
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.isTrue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.CoroutineContext

object AutoTaskProtocol {

    internal const val MAX_NOTIFICATION_TITLE_LENGTH = 200
    internal const val MAX_NOTIFICATION_CONTENT_LENGTH = 4_000

    suspend fun handle(
        result: Any?,
        context: Context,
        taskName: String? = null,
        logger: ((String) -> Unit)? = null
    ): String? {
        val actions = parseActions(result, currentCoroutineContext()) ?: return null
        val summaries = mutableListOf<String>()
        for (action in actions) {
            currentCoroutineContext().ensureActive()
            val summary = when (actionType(action)) {
                "refreshtoc" -> handleRefreshToc(action, context)
                "notify" -> handleNotify(action, context, taskName)
                else -> error("Unsupported automatic task action")
            }
            currentCoroutineContext().ensureActive()
            if (summary.isNotBlank()) {
                summaries.add(summary)
                logger?.invoke(summary)
            }
        }
        return summaries.joinToString(" | ").ifBlank { null }
    }

    internal fun parseActions(
        result: Any?,
        coroutineContext: CoroutineContext? = null,
    ): List<Map<String, Any?>>? {
        if (result == null) return null
        val json = try {
            JsSourceEngine.normalizeJsResult(result, coroutineContext)
        } catch (error: Throwable) {
            error.autoTaskCancellation()?.let { throw it }
            null
        }
        val text = json?.trim().orEmpty()
        return when {
            text.isJsonArray() -> GSON.fromJsonArray<Map<String, Any?>>(text).getOrNull()
                ?.mapNotNull(::stringKeyMap)
            text.isJsonObject() -> GSON.fromJsonObject<Map<String, Any?>>(text).getOrNull()
                ?.let(::mapToActions)
            else -> null
        }
    }

    internal fun actionType(action: Map<String, Any?>): String {
        val type = string(action, "type")?.lowercase(Locale.ROOT).orEmpty()
        require(type == "notify" || type == "refreshtoc") {
            "Unsupported automatic task action: $type"
        }
        return type
    }

    private fun mapToActions(root: Map<String, Any?>): List<Map<String, Any?>>? {
        val normalized = stringKeyMap(root) ?: return null
        val actions = value(normalized, "actions")
        return when (actions) {
            is List<*> -> actions.mapNotNull(::stringKeyMap)
            else -> normalized.takeIf { value(it, "type") != null }?.let(::listOf)
        }
    }

    private fun stringKeyMap(value: Any?): Map<String, Any?>? {
        if (value !is Map<*, *>) return null
        return buildMap {
            value.forEach { (key, item) -> if (key != null) put(key.toString(), item) }
        }
    }

    private suspend fun handleRefreshToc(
        action: Map<String, Any?>,
        context: Context
    ): String {
        val bookUrl = string(action, "bookUrl")?.trim().orEmpty()
        require(bookUrl.isNotBlank()) { "refreshToc requires bookUrl" }
        val book = appDb.bookDao.getBook(bookUrl)
            ?: error("refreshToc book was not found")
        val chaptersBefore = appDb.bookChapterDao.getChapterList(bookUrl)
        val chapters = refreshBookToc(book)
        val newCount = countNewChapters(chaptersBefore, chapters)

        val notify = map(action, "notify")
        val notifyEnabled = notify?.let { boolean(it, "enable", true) } ?: false
        val notifyMin = notify?.let { integer(it, "minCount") } ?: 1
        val notifyTitle = notify?.let { string(it, "title") }
        val notifyContent = notify?.let { string(it, "content") }
        val shouldNotify = notifyEnabled && newCount >= notifyMin && newCount > 0
        val notified = shouldNotify && notifyBookUpdate(
                context,
                book,
                newCount,
                latestChapterTitle(chapters),
                notifyTitle,
                notifyContent
            )

        return buildString {
            append(book.name.ifBlank { book.bookUrl })
            append(": +").append(newCount)
            if (notified) append(", notified")
        }
    }

    private suspend fun refreshBookToc(book: Book): List<BookChapter> {
        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()
        val chapters = if (book.isLocal) {
            LocalBook.getChapterList(book)
        } else {
            val bookSource = appDb.bookSourceDao.getBookSource(book.origin)
                ?: error("未找到对应书源,请换源")
            coroutineContext.ensureActive()
            if (book.tocUrl.isBlank()) {
                WebBook.getBookInfoAwait(bookSource, book)
                coroutineContext.ensureActive()
            }
            WebBook.getChapterListAwait(bookSource, book).getOrThrow()
        }
        coroutineContext.ensureActive()
        appDb.runInTransaction {
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookChapterDao.insert(*chapters.toTypedArray())
            appDb.bookDao.update(book)
        }
        coroutineContext.ensureActive()
        return chapters
    }

    private fun handleNotify(
        action: Map<String, Any?>,
        context: Context,
        taskName: String?
    ): String {
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        val title = trimNotificationTitle(
            formatCommon(
                string(action, "title") ?: context.getString(R.string.auto_task_notify_title),
                taskName,
                time
            )
        )
        val content = trimNotificationContent(
            formatCommon(
                string(action, "content")
                    ?: context.getString(R.string.auto_task_notify_content, taskName.orEmpty()),
                taskName,
                time
            )
        )
        val priority = when (string(action, "level")?.lowercase(Locale.ROOT)) {
            "high", "error", "fail", "failed" -> NotificationCompat.PRIORITY_HIGH
            "low" -> NotificationCompat.PRIORITY_LOW
            else -> NotificationCompat.PRIORITY_DEFAULT
        }
        val notifyId = taskNotificationId(
            integer(action, "id"),
            "${taskName.orEmpty()}|$title|$content"
        )
        val notification = NotificationCompat.Builder(context, AppConst.channelIdWeb)
            .setSmallIcon(R.drawable.ic_web_service_noti)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(priority)
            .setAutoCancel(true)
            .build()
        return if (postNotification(context, notifyId, notification)) {
            "Notification: $title"
        } else {
            "Notification skipped: $title"
        }
    }

    private fun notifyBookUpdate(
        context: Context,
        book: Book,
        newCount: Int,
        latestTitle: String?,
        titleTemplate: String?,
        contentTemplate: String?
    ): Boolean {
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        val title = trimNotificationTitle(
            formatBook(
                titleTemplate ?: context.getString(R.string.auto_task_book_update_title, book.name),
                book,
                newCount,
                latestTitle,
                time
            )
        )
        val defaultContent = if (latestTitle.isNullOrBlank()) {
            context.getString(R.string.auto_task_book_update_content_count, newCount)
        } else {
            context.getString(R.string.auto_task_book_update_content, newCount, latestTitle)
        }
        val content = trimNotificationContent(
            formatBook(
                contentTemplate ?: defaultContent,
                book,
                newCount,
                latestTitle,
                time
            )
        )
        val notification = NotificationCompat.Builder(context, AppConst.channelIdWeb)
            .setSmallIcon(R.drawable.ic_web_service_noti)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setAutoCancel(true)
            .build()
        val notifyId = bookUpdateNotificationId(book.bookUrl)
        return postNotification(context, notifyId, notification)
    }

    private fun postNotification(
        context: Context,
        id: Int,
        notification: android.app.Notification
    ): Boolean {
        return try {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return false
            manager.notify(id, notification)
            true
        } catch (error: RuntimeException) {
            AppLog.put("Automatic task notification failed", error)
            false
        }
    }

    internal fun trimNotificationTitle(text: String): String {
        return text.take(MAX_NOTIFICATION_TITLE_LENGTH)
    }

    internal fun trimNotificationContent(text: String): String {
        return text.take(MAX_NOTIFICATION_CONTENT_LENGTH)
    }

    internal fun taskNotificationId(explicitId: Int?, key: String): Int {
        val value = explicitId ?: key.hashCode()
        return NotificationId.AutoTaskNotifyBase + (value and Int.MAX_VALUE) % 10_000
    }

    internal fun bookUpdateNotificationId(bookUrl: String): Int {
        return NotificationId.AutoTaskBookUpdateBase +
            (bookUrl.hashCode() and Int.MAX_VALUE) % 10_000
    }

    internal fun countNewChapters(
        before: List<BookChapter>,
        after: List<BookChapter>
    ): Int {
        return (after.count { !it.isVolume } - before.count { !it.isVolume })
            .coerceAtLeast(0)
    }

    private fun latestChapterTitle(chapters: List<BookChapter>): String? {
        return chapters.lastOrNull { !it.isVolume }?.title ?: chapters.lastOrNull()?.title
    }

    private fun string(map: Map<String, Any?>, key: String): String? {
        return value(map, key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun integer(map: Map<String, Any?>, key: String): Int? {
        return when (val item = value(map, key)) {
            is Number -> item.toInt()
            is String -> item.toIntOrNull()
            else -> null
        }
    }

    private fun boolean(map: Map<String, Any?>, key: String, default: Boolean): Boolean {
        return when (val item = value(map, key)) {
            is Boolean -> item
            is Number -> item.toInt() != 0
            is String -> item.isTrue(default)
            else -> default
        }
    }

    private fun map(map: Map<String, Any?>, key: String): Map<String, Any?>? {
        return stringKeyMap(value(map, key))
    }

    private fun value(map: Map<String, Any?>, key: String): Any? {
        map[key]?.let { return it }
        return map.entries.firstOrNull { it.key.equals(key, true) }?.value
    }

    private fun formatCommon(template: String, taskName: String?, time: String): String {
        return template.replace("{task}", taskName.orEmpty()).replace("{time}", time)
    }

    private fun formatBook(
        template: String,
        book: Book,
        newCount: Int,
        latestTitle: String?,
        time: String
    ): String {
        return template
            .replace("{book}", book.name)
            .replace("{author}", book.author)
            .replace("{newCount}", newCount.toString())
            .replace("{chapter}", latestTitle.orEmpty())
            .replace("{time}", time)
    }
}
