import assert from 'node:assert/strict'
import test from 'node:test'
import {
  appendToChapterWindow,
  MAX_RETAINED_CHAPTERS,
} from '../src/utils/chapterWindow.js'

test('keeps a bounded window of the latest chapters', () => {
  const loaded = Array.from(
    { length: MAX_RETAINED_CHAPTERS },
    (_, index) => index,
  )

  const result = appendToChapterWindow(loaded, MAX_RETAINED_CHAPTERS)

  assert.equal(result.removedCount, 1)
  assert.deepEqual(
    result.chapters,
    Array.from({ length: MAX_RETAINED_CHAPTERS }, (_, index) => index + 1),
  )
  assert.deepEqual(
    loaded,
    Array.from({ length: MAX_RETAINED_CHAPTERS }, (_, index) => index),
  )
})

test('retains every chapter while the window is not full', () => {
  const result = appendToChapterWindow([1], 2)

  assert.equal(result.removedCount, 0)
  assert.deepEqual(result.chapters, [1, 2])
})
