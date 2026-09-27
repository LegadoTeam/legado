import assert from 'node:assert/strict'
import test from 'node:test'
import { sourceCheckContent, sourceCheckStatus, sourceCheckSnapshots } from '../src/utils/sourceCheckState.js'

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

test('a saved device snapshot follows later checks without accepting unsaved edits or replacing other baselines', () => {
  const other = { ...source, bookSourceUrl: 'https://other' }
  const oldStates = [source, other].map(s => ({ ...passed, bookSourceUrl: s.bookSourceUrl }))
  const snapshots = sourceCheckSnapshots([source, other], oldStates)
  const saved = { ...source, ruleSearch: { name: 'saved rule' } }
  const state = { ...passed, bookSourceUrl: source.bookSourceUrl, sourceRevision: 'saved-rules', status: 'NEEDS_CHECK' }
  const editor = { ...saved, ruleSearch: { name: 'unsaved rule' } }
  const before = JSON.stringify(editor)
  Object.assign(snapshots, sourceCheckSnapshots([saved], [state]))
  assert.equal(sourceCheckStatus(saved, snapshots[source.bookSourceUrl], state), 'NEEDS_CHECK')
  assert.equal(sourceCheckStatus(saved, snapshots[source.bookSourceUrl], { ...state, status: 'PASSED' }), 'PASSED')
  assert.equal(sourceCheckStatus(editor, snapshots[source.bookSourceUrl], { ...state, status: 'PASSED' }), 'NEEDS_CHECK')
  assert.equal(sourceCheckStatus(other, snapshots[other.bookSourceUrl], oldStates[1]), 'PASSED')
  assert.equal(JSON.stringify(editor), before)
  assert.equal(sourceCheckStatus(saved, sourceCheckSnapshots([saved], [])[source.bookSourceUrl], state), 'NEEDS_CHECK')
})
