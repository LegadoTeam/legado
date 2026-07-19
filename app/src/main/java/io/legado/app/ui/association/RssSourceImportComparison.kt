package io.legado.app.ui.association

import io.legado.app.data.entities.RssSource
import java.util.concurrent.atomic.AtomicBoolean

// Keep enough headroom below SQLite's host parameter limit.
internal const val RSS_SOURCE_QUERY_CHUNK_SIZE = 900

internal class RssSourceImportRequestGate {
    private val requested = AtomicBoolean()

    fun tryStart(): Boolean = requested.compareAndSet(false, true)
}

internal data class RssSourceImportComparison(
    val existingSources: List<RssSource?>,
    val selectStatus: List<Boolean>,
)

internal fun compareImportedRssSources(
    importedSources: List<RssSource>,
    loadExistingSources: (List<String>) -> List<RssSource>,
): RssSourceImportComparison {
    val existingSourcesByUrl = importedSources
        .map { it.sourceUrl }
        .distinct()
        .chunked(RSS_SOURCE_QUERY_CHUNK_SIZE)
        .flatMap(loadExistingSources)
        .associateBy { it.sourceUrl }
    val existingSources = importedSources.map { existingSourcesByUrl[it.sourceUrl] }
    return RssSourceImportComparison(
        existingSources = existingSources,
        selectStatus = importedSources.mapIndexed { index, source ->
            val existingSource = existingSources[index]
            existingSource == null || existingSource.lastUpdateTime < source.lastUpdateTime
        },
    )
}
