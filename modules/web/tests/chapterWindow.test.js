import assert from 'node:assert/strict'
import test from 'node:test'
import {
  MAX_RETAINED_CHAPTERS,
  trimChapterWindowBeforeAppend,
} from '../src/utils/chapterWindow.js'

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
