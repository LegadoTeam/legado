import assert from 'node:assert/strict'
import test from 'node:test'
import { sourceCheckContent, sourceCheckStatus } from '../src/utils/sourceCheckState.js'

const source = { bookSourceUrl: 'https://source', bookSourceName: 'Source', ruleSearch: { name: 'a' } }
const snapshot = { content: sourceCheckContent(source), sourceRevision: 'original-rules' }
const passed = { status: 'PASSED', sourceRevision: 'original-rules' }

test('only reports results for the exact local and device source version', () => {
  assert.equal(sourceCheckStatus(source, snapshot, passed), 'PASSED')
  assert.equal(sourceCheckStatus({ ...source, ruleSearch: { name: 'b' } }, snapshot, passed), 'NEEDS_CHECK')
  assert.equal(sourceCheckStatus(source, snapshot, { ...passed, sourceRevision: 'edited-on-device' }), 'NEEDS_CHECK')
  assert.equal(sourceCheckStatus(source, undefined, passed), 'NEEDS_CHECK')
  assert.equal(sourceCheckStatus(source, snapshot, undefined), 'NEEDS_CHECK')
})

test('metadata and harmless JSON property order do not mark rules dirty or mutate exports', () => {
  const local = { weight: 5, ...source, enabledCookieJar: true, bookSourceComment: '', ruleToc: {} }
  const before = JSON.stringify(local)
  assert.equal(sourceCheckContent(local), sourceCheckContent(source))
  assert.equal(JSON.stringify(local), before)
  assert.equal(sourceCheckStatus(source, snapshot, { ...passed, status: 'FAILED' }), 'FAILED')
  assert.equal(sourceCheckStatus(source, snapshot, { ...passed, status: 'NEEDS_CHECK' }), 'NEEDS_CHECK')
})
