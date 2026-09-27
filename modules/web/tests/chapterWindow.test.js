import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import {
  MAX_RETAINED_CHAPTERS,
  trimChapterWindowBeforeAppend,
} from '../src/utils/chapterWindow.js'
import { parseLegacyReviewClick } from '../src/utils/reviewClick.js'

test('reserves one chapter slot before the next chapter is rendered', () => {
  const loaded = Array.from(
    { length: MAX_RETAINED_CHAPTERS },
    (_, index) => index,
  )

  const prepared = trimChapterWindowBeforeAppend(loaded)
  prepared.push(MAX_RETAINED_CHAPTERS)

  assert.deepEqual(
    prepared,
    Array.from({ length: MAX_RETAINED_CHAPTERS }, (_, index) => index + 1),
  )
  assert.deepEqual(
    loaded,
    Array.from({ length: MAX_RETAINED_CHAPTERS }, (_, index) => index),
  )
})

test('does not trim while the window has room', () => {
  const loaded = [1]

  assert.equal(trimChapterWindowBeforeAppend(loaded), loaded)
})

test('keeps legacy paragraph and chapter review image clicks on their script path', () => {
  const paragraph =
    'http://,{"style":"text","js":"getDPSvg(3,0)","click":"getDP(12,3)"}'
  const proxy = `http://localhost/image?path=${encodeURIComponent(paragraph)}`

  assert.deepEqual(parseLegacyReviewClick(proxy), {
    kind: 'paragraph',
    src: paragraph,
  })
  assert.deepEqual(
    parseLegacyReviewClick(
      "http://,{'style':'full','click':'getZP(3)'}",
    ),
    {
      kind: 'chapter',
      src: "http://,{'style':'full','click':'getZP(3)'}",
    },
  )
  assert.equal(
    parseLegacyReviewClick(
      'http://,{"style":"text","click":"getZS(3)"}',
    ),
    null,
  )
  assert.equal(
    parseLegacyReviewClick(
      'http://,{"style":"text","click":"getDP(3)"}',
    ),
    null,
  )
})

test('does not report a canceled token prompt as a backend failure', () => {
  const apiIndex = readFileSync(
    new URL('../src/api/index.ts', import.meta.url),
    'utf8',
  )
  const cancelGuard = apiIndex.indexOf(
    "if (err === 'cancel' || err === 'close') throw err",
  )
  const backendFailureNotice = apiIndex.indexOf(
    "message: '后端连接失败，请检查阅读WEB服务或者设置其它可用链接'",
  )

  assert.ok(cancelGuard >= 0)
  assert.ok(cancelGuard < backendFailureNotice)
})

test('tracks reading progress without observing every paragraph', () => {
  const chapterContent = readFileSync(
    new URL('../src/components/ChapterContent.vue', import.meta.url),
    'utf8',
  )
  const bookChapter = readFileSync(
    new URL('../src/views/BookChapter.vue', import.meta.url),
    'utf8',
  )

  assert.doesNotMatch(chapterContent, /IntersectionObserver/)
  assert.match(bookChapter, /:data-chapter-index="data.index"/)
  assert.match(
    bookChapter,
    /elementsFromPoint\(\s*window.innerWidth \/ 2,\s*24,\s*\)/,
  )
  assert.match(bookChapter, /requestAnimationFrame\(updateReadingProgress\)/)
  assert.match(
    bookChapter,
    /addEventListener\('scroll', onScroll, \{ passive: true \}\)/,
  )
  assert.match(bookChapter, /removeEventListener\('scroll', onScroll\)/)
  assert.match(bookChapter, /cancelAnimationFrame\(progressFrame\)/)
})
