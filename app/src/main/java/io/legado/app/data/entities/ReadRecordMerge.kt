package io.legado.app.data.entities

internal fun mergeRestoredReadRecord(
    current: ReadRecord?,
    incoming: ReadRecord,
    localDevice: Boolean,
): ReadRecord {
    if (current == null) return incoming
    val latest = if (incoming.lastRead >= current.lastRead) incoming else current
    val previous = if (latest === incoming) current else incoming
    val chapter = if (latest.lastChapterIndex >= 0 || !latest.lastChapterTitle.isNullOrBlank()) latest else previous
    return incoming.copy(
        readTime = if (localDevice) maxOf(current.readTime, incoming.readTime) else incoming.readTime,
        lastRead = maxOf(current.lastRead, incoming.lastRead),
        author = ReadRecordAuthors.merge(current.author, incoming.author),
        lastChapterTitle = chapter.lastChapterTitle,
        lastChapterIndex = chapter.lastChapterIndex,
        lastChapterPos = chapter.lastChapterPos,
        coverUrl = latest.coverUrl?.takeIf { it.isNotBlank() } ?: previous.coverUrl,
    )
}
