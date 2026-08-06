import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const readSource = path =>
  readFileSync(new URL(`../src/${path}`, import.meta.url), 'utf8')

test('keeps the source editor usable without opening hotkeys on load', () => {
  const editor = readSource('views/SourceEditor.vue')
  const toolbar = readSource('components/ToolBar.vue')

  assert.match(editor, /segmented\/style\/css/)
  assert.match(editor, /max-width: 900px/)
  assert.match(toolbar, /hotkeysDialogVisible = ref\(false\)/)
  assert.match(toolbar, /ElMessageBox\.confirm/)
  assert.match(toolbar, /\[0, 1, 2, 3, 4, 7, 8\]/)
  assert.doesNotMatch(toolbar, /撤销操作|重做操作/)
})

test('uses bounded labels and stable source list rows', () => {
  const form = readSource('components/SourceTabForm.vue')
  const list = readSource('components/SourceList.vue')

  assert.match(form, /label-width="140px"/)
  assert.match(list, /:data-key="getSourceUniqueKey"/)
  assert.match(list, /class="source-list-panel"/)
  assert.doesNotMatch(list, /calc\(100% - 75px\)/)
})

test('keeps the JavaScript source toolbar balanced on narrow screens', () => {
  const editor = readSource('components/JsSourceEditor.vue')

  assert.match(editor, /max-width: 600px/)
  assert.match(editor, /grid-template-columns: repeat\(3, minmax\(0, 1fr\)\)/)
  assert.match(editor, /grid-column: 1 \/ -1/)
})
