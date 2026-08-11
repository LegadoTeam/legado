/** https://github.com/gedoor/legado/tree/master/app/src/main/java/io/legado/app/api */
/** https://github.com/gedoor/legado/tree/master/app/src/main/java/io/legado/app/web */

import type { webReadConfig } from '@/web'
import ajax from './axios'
import {
  bindSourceApiTokenEndpoint,
  getSourceApiToken,
  requestSourceApiToken,
  sourceApiTokenWebSocketProtocols,
} from './sourceToken'
import type {
  BaseBook,
  Book,
  BookChapter,
  BookProgress,
  ReviewPage,
  ReviewSummary,
  SeachBook,
} from '@/book'
import type { BookSoure, Source } from '@/source'

export type LeagdoApiResponse<T> = {
  isSuccess: boolean
  errorMsg: string
  data: T
}

export type LegacyReviewSession = {
  id: string
  nonce: string
}

export let legado_http_entry_point = ''
export let legado_webSocket_entry_point = ''

let wsOnError: typeof WebSocket.prototype.onerror = () => {}
let wsOnMessage: typeof WebSocket.prototype.onmessage = () => {}
export const setWebsocketOnMessage = (callback: typeof wsOnMessage) =>
  (wsOnMessage = callback)
export const setWebsocketOnError = (callback: typeof wsOnError) => {
  //WebSocket.prototype.onerror = callback
  wsOnError = callback
}

export const setApiEntryPoint = (
  http_entry_point: string,
  webSocket_entry_point: string,
) => {
  const nextHttpEntryPoint = new URL(http_entry_point).toString()
  bindSourceApiTokenEndpoint(nextHttpEntryPoint)
  legado_http_entry_point = nextHttpEntryPoint
  legado_webSocket_entry_point = new URL(webSocket_entry_point).toString()
  ajax.defaults.baseURL = legado_http_entry_point
}

// 书架API
// Http
const getReadConfig = async (http_url = legado_http_entry_point) => {
  const { data } = await ajax.get<LeagdoApiResponse<string>>('getReadConfig', {
    baseURL: http_url.toString(),
    timeout: 3000,
  })
  if (data.isSuccess) {
    try {
      return JSON.parse(data.data) as webReadConfig
    } catch {}
  }
}
const saveReadConfig = (config: webReadConfig) =>
  ajax.post<LeagdoApiResponse<string>>('saveReadConfig', config)

/** @deprecated: 使用`API.saveBookProgressWithBeacon`以确保在页面或者直接关闭的情况下保存进度 */
const saveBookProgress = (bookProgress: BookProgress) =>
  ajax.post('saveBookProgress', bookProgress)

/**主要在直接关闭浏览器情况下可靠发送书籍进度 */
const saveBookProgressWithBeacon = (bookProgress: BookProgress) => {
  if (!bookProgress) return
  // 常规请求可能会被取消 使用Fetch keep-alive 或者 navigator.sendBeacon
  navigator.sendBeacon(
    new URL('saveBookProgress', legado_http_entry_point),
    JSON.stringify(bookProgress),
  )
}

const getBookShelf = () => ajax.get<LeagdoApiResponse<Book[]>>('getBookshelf')

const getChapterList = (/** @type {string} */ bookUrl: string) =>
  ajax.get<LeagdoApiResponse<BookChapter[]>>(
    'getChapterList?url=' + encodeURIComponent(bookUrl),
  )

const getBookContent = (
  /** @type {string} */ bookUrl: string,
  /** @type {number} */ chapterIndex: number,
) =>
  ajax.get<LeagdoApiResponse<string>>(
    'getBookContent?url=' +
      encodeURIComponent(bookUrl) +
      '&index=' +
      chapterIndex,
  )

const getReviewSummary = (bookUrl: string, chapterIndex: number) =>
  ajax.get<LeagdoApiResponse<ReviewSummary>>('getReviewSummary', {
    params: { url: bookUrl, index: chapterIndex },
  })

const getReviewDetail = (
  bookUrl: string,
  chapterIndex: number,
  paraIndex: number,
  paraData: string,
  page: number,
  cursor?: string | null,
) =>
  ajax.get<LeagdoApiResponse<ReviewPage>>('getReviewDetail', {
    params: {
      url: bookUrl,
      index: chapterIndex,
      paraIndex,
      paraData,
      page,
      cursor: cursor || undefined,
    },
  })

const getReviewReplies = (
  bookUrl: string,
  chapterIndex: number,
  paraIndex: number,
  paraData: string,
  reviewId: string,
  page: number,
) =>
  ajax.get<LeagdoApiResponse<ReviewPage>>('getReviewReplies', {
    params: {
      url: bookUrl,
      index: chapterIndex,
      paraIndex,
      paraData,
      reviewId,
      page,
    },
  })

const openLegacyReview = (
  bookUrl: string,
  chapterIndex: number,
  src: string,
) =>
  ajax.post<LeagdoApiResponse<LegacyReviewSession>>('openLegacyReview', {
    url: bookUrl,
    index: chapterIndex,
    src,
  })

const runLegacyReview = (id: string, script: string) =>
  ajax.post<LeagdoApiResponse<string>>('runLegacyReview', { id, script })

const getLegacyReviewPageUrl = (session: LegacyReviewSession) => {
  const url = new URL('legacyReviewPage', legado_http_entry_point)
  url.searchParams.set('id', session.id)
  url.searchParams.set('nonce', session.nonce)
  return url.toString()
}

// webSocket
const search = (
  searchKey: string,
  token: string | undefined,
  onReceive: (data: SeachBook[]) => void,
  onFinish: () => void,
  onAuthFailure?: () => void,
) => {
  let handshakeFailureReported = false
  const reportHandshakeFailure = () => {
    if (handshakeFailureReported) return
    handshakeFailureReported = true
    onAuthFailure?.()
  }
  const socket = new WebSocket(
    new URL('searchBook', legado_webSocket_entry_point),
    sourceApiTokenWebSocketProtocols(token),
  )
  socket.onerror = event => {
    reportHandshakeFailure()
    wsOnError?.call(socket, event)
  }

  socket.onopen = () => {
    socket.send(
      JSON.stringify({
        key: searchKey,
      }),
    )
  }
  socket.onmessage = event => {
    try {
      onReceive(JSON.parse(event.data))
      wsOnMessage?.call(socket, event)
    } catch {
      onFinish()
    }
  }

  socket.onclose = event => {
    if (event.code === 1008) reportHandshakeFailure()
    onFinish()
  }
}

const saveBook = (book: BaseBook) =>
  ajax.post<LeagdoApiResponse<string>>('saveBook', book)
const deleteBook = (book: BaseBook) =>
  ajax.post<LeagdoApiResponse<string>>('deleteBook', book)

const isBookSource = /bookSource/i.test(location.href)

// 源编辑API
// Http
const getSources = () =>
  isBookSource ? ajax.get('getBookSources') : ajax.get('getRssSources')

const saveSource = (data: Source) =>
  isBookSource
    ? ajax.post<LeagdoApiResponse<string>>('saveBookSource', data)
    : ajax.post<LeagdoApiResponse<string>>('saveRssSource', data)

const saveJsSource = (script: string, openedSourceUrl?: string) =>
  ajax.post<LeagdoApiResponse<BookSoure>>('saveJsSource', script, {
    params: { openedSourceUrl },
    headers: { 'Content-Type': 'text/plain; charset=utf-8' },
  })

const saveSources = (data: Source[]) =>
  isBookSource
    ? ajax.post<LeagdoApiResponse<Source[]>>('saveBookSources', data)
    : ajax.post<LeagdoApiResponse<Source[]>>('saveRssSources', data)

const deleteSource = (data: Source[]) =>
  isBookSource
    ? ajax.post<LeagdoApiResponse<string>>('deleteBookSources', data)
    : ajax.post<LeagdoApiResponse<string>>('deleteRssSources', data)

// webSocket
const debug = async (
  /** @type {string} */ sourceUrl: string,
  /** @type {string} */ searchKey: string,
  /** @type {(data: string) => void} */ onReceive: (data: string) => void,
  /** @type {() => void} */ onFinish: () => void,
) => {
  const token = getSourceApiToken() || (await requestSourceApiToken())
  const url = new URL(
    `${isBookSource ? 'bookSource' : 'rssSource'}Debug`,
    legado_webSocket_entry_point,
  )

  const socket = new WebSocket(url, sourceApiTokenWebSocketProtocols(token))
  socket.onerror = event => {
    wsOnError?.call(socket, event)
  }
  socket.onopen = () => {
    socket.send(
      JSON.stringify({
        tag: sourceUrl,
        key: searchKey,
      }),
    )
  }
  socket.onmessage = event => {
    onReceive(event.data)
    wsOnMessage?.call(socket, event)
  }

  socket.onclose = () => {
    onFinish()
  }
}

/**
 * 从阅读获取需要特定处理的书籍封面
 * @param {string} coverUrl
 */
const getProxyCoverUrl = (coverUrl: string) => {
  if (coverUrl.startsWith(legado_http_entry_point)) return coverUrl
  return new URL(
    'cover?path=' + encodeURIComponent(coverUrl),
    legado_http_entry_point,
  ).toString()
}
/**
 * 从阅读获取需要特定处理的图片
 * @param {string} bookUrl
 * @param {string} src
 * @param {number|`${number}`} width
 */
const getProxyImageUrl = (
  bookUrl: string,
  src: string,
  width: number | `${number}`,
) => {
  if (src.startsWith(legado_http_entry_point)) return src
  return new URL(
    'image?path=' +
      encodeURIComponent(src) +
      '&url=' +
      encodeURIComponent(bookUrl) +
      '&width=' +
      width,
    legado_http_entry_point,
  ).toString()
}

export default {
  getReadConfig,
  saveReadConfig,
  saveBookProgress,
  saveBookProgressWithBeacon,
  getBookShelf,
  getChapterList,
  getBookContent,
  getReviewSummary,
  getReviewDetail,
  getReviewReplies,
  openLegacyReview,
  runLegacyReview,
  getLegacyReviewPageUrl,
  search,
  saveBook,
  deleteBook,

  getSources,
  saveSources,
  saveSource,
  saveJsSource,
  deleteSource,
  debug,

  getProxyCoverUrl,
  getProxyImageUrl,
}
