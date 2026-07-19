package io.legado.app.api

internal enum class ReaderProviderRequestCode {
    SaveBookSource,
    SaveBookSources,
    DeleteBookSources,
    GetBookSource,
    GetBookSources,
    SaveRssSource,
    SaveRssSources,
    DeleteRssSources,
    GetRssSource,
    GetRssSources,
    SaveBook,
    GetBookshelf,
    RefreshToc,
    GetChapterList,
    GetBookContent,
    GetBookCover,
    SaveBookProgress,
}

internal data class ReaderProviderRoute(
    val path: String,
    val requestCode: ReaderProviderRequestCode,
)

internal object ReaderProviderRoutes {

    val all = listOf(
        ReaderProviderRoute("bookSource/insert", ReaderProviderRequestCode.SaveBookSource),
        ReaderProviderRoute("bookSources/insert", ReaderProviderRequestCode.SaveBookSources),
        ReaderProviderRoute("bookSources/delete", ReaderProviderRequestCode.DeleteBookSources),
        ReaderProviderRoute("bookSource/query", ReaderProviderRequestCode.GetBookSource),
        ReaderProviderRoute("bookSources/query", ReaderProviderRequestCode.GetBookSources),
        ReaderProviderRoute("rssSource/insert", ReaderProviderRequestCode.SaveRssSource),
        ReaderProviderRoute("rssSources/insert", ReaderProviderRequestCode.SaveRssSources),
        ReaderProviderRoute("rssSources/delete", ReaderProviderRequestCode.DeleteRssSources),
        ReaderProviderRoute("rssSource/query", ReaderProviderRequestCode.GetRssSource),
        ReaderProviderRoute("rssSources/query", ReaderProviderRequestCode.GetRssSources),
        ReaderProviderRoute("book/insert", ReaderProviderRequestCode.SaveBook),
        ReaderProviderRoute("books/query", ReaderProviderRequestCode.GetBookshelf),
        ReaderProviderRoute("book/refreshToc/query", ReaderProviderRequestCode.RefreshToc),
        ReaderProviderRoute("book/chapter/query", ReaderProviderRequestCode.GetChapterList),
        ReaderProviderRoute("book/content/query", ReaderProviderRequestCode.GetBookContent),
        ReaderProviderRoute("book/cover/query", ReaderProviderRequestCode.GetBookCover),
    )

    private val requestByPath = all.associate { it.path to it.requestCode }

    init {
        require(requestByPath.size == all.size) { "ReaderProvider paths must be unique" }
        require(all.map { it.requestCode }.distinct().size == all.size) {
            "ReaderProvider request codes must be unique"
        }
    }

    fun requestForPath(path: String): ReaderProviderRequestCode? = requestByPath[path]

    fun requestForMatcherCode(code: Int): ReaderProviderRequestCode? =
        ReaderProviderRequestCode.entries.getOrNull(code)
}

internal fun dispatchReaderProviderDelete(
    requestCode: ReaderProviderRequestCode,
    selection: String?,
    deleteBookSources: (String?) -> Unit,
    deleteRssSources: (String?) -> Unit,
) {
    when (requestCode) {
        ReaderProviderRequestCode.DeleteBookSources -> deleteBookSources(selection)
        ReaderProviderRequestCode.DeleteRssSources -> deleteRssSources(selection)
        else -> error("Unexpected delete request: ${requestCode.name}")
    }
}

internal suspend fun dispatchReaderProviderInsert(
    requestCode: ReaderProviderRequestCode,
    postData: String?,
    valuesPresent: Boolean = true,
    saveBookSource: suspend (String?) -> Unit = { unexpectedReaderProviderRequest(requestCode) },
    saveBookSources: suspend (String?) -> Unit = { unexpectedReaderProviderRequest(requestCode) },
    saveRssSource: suspend (String?) -> Unit = { unexpectedReaderProviderRequest(requestCode) },
    saveRssSources: suspend (String?) -> Unit = { unexpectedReaderProviderRequest(requestCode) },
    saveBook: suspend (String?) -> Unit = { unexpectedReaderProviderRequest(requestCode) },
    saveBookProgress: suspend (String?) -> Unit = { unexpectedReaderProviderRequest(requestCode) },
) {
    when (requestCode) {
        ReaderProviderRequestCode.SaveBookSource -> if (valuesPresent) saveBookSource(postData)
        ReaderProviderRequestCode.SaveBookSources -> if (valuesPresent) saveBookSources(postData)
        ReaderProviderRequestCode.SaveRssSource -> if (valuesPresent) saveRssSource(postData)
        ReaderProviderRequestCode.SaveRssSources -> if (valuesPresent) saveRssSources(postData)
        ReaderProviderRequestCode.SaveBook -> if (valuesPresent) saveBook(postData)
        ReaderProviderRequestCode.SaveBookProgress -> if (valuesPresent) saveBookProgress(postData)
        else -> unexpectedReaderProviderRequest(requestCode)
    }
}

internal fun <T> dispatchReaderProviderQuery(
    requestCode: ReaderProviderRequestCode,
    parameters: Map<String, List<String>>,
    getBookSource: (Map<String, List<String>>) -> T = {
        unexpectedReaderProviderRequest(requestCode)
    },
    getBookSources: () -> T = { unexpectedReaderProviderRequest(requestCode) },
    getRssSource: (Map<String, List<String>>) -> T = {
        unexpectedReaderProviderRequest(requestCode)
    },
    getRssSources: () -> T = { unexpectedReaderProviderRequest(requestCode) },
    getBookshelf: () -> T = { unexpectedReaderProviderRequest(requestCode) },
    getBookContent: (Map<String, List<String>>) -> T = {
        unexpectedReaderProviderRequest(requestCode)
    },
    refreshToc: (Map<String, List<String>>) -> T = {
        unexpectedReaderProviderRequest(requestCode)
    },
    getChapterList: (Map<String, List<String>>) -> T = {
        unexpectedReaderProviderRequest(requestCode)
    },
    getBookCover: (Map<String, List<String>>) -> T = {
        unexpectedReaderProviderRequest(requestCode)
    },
): T {
    return when (requestCode) {
        ReaderProviderRequestCode.GetBookSource -> getBookSource(parameters)
        ReaderProviderRequestCode.GetBookSources -> getBookSources()
        ReaderProviderRequestCode.GetRssSource -> getRssSource(parameters)
        ReaderProviderRequestCode.GetRssSources -> getRssSources()
        ReaderProviderRequestCode.GetBookshelf -> getBookshelf()
        ReaderProviderRequestCode.GetBookContent -> getBookContent(parameters)
        ReaderProviderRequestCode.RefreshToc -> refreshToc(parameters)
        ReaderProviderRequestCode.GetChapterList -> getChapterList(parameters)
        ReaderProviderRequestCode.GetBookCover -> getBookCover(parameters)
        else -> unexpectedReaderProviderRequest(requestCode)
    }
}

private fun unexpectedReaderProviderRequest(requestCode: ReaderProviderRequestCode): Nothing {
    error("Unexpected ReaderProvider request: ${requestCode.name}")
}
