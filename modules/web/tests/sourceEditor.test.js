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

test('measures source textareas only while their tab is visible', () => {
  const form = readSource('components/SourceTabForm.vue')
  const editor = readSource('views/SourceEditor.vue')

  assert.match(form, /<el-tabs id="source-edit" v-model="activeTab">/)
  assert.match(form, /:name="name"/)
  assert.match(form, /v-if="activeTab === name"/)
  assert.match(editor, /\.right \{\s*flex: 1/)
  assert.doesNotMatch(editor, /flex: 0 0 360px/)
})

test('keeps the JavaScript source toolbar balanced on narrow screens', () => {
  const editor = readSource('components/JsSourceEditor.vue')

  assert.match(editor, /max-width: 600px/)
  assert.match(editor, /grid-template-columns: repeat\(3, minmax\(0, 1fr\)\)/)
  assert.match(editor, /grid-column: 1 \/ -1/)
})

test('keeps source editor state and mobile controls reachable', () => {
  const editor = readSource('components/JsSourceEditor.vue')
  const view = readSource('views/SourceEditor.vue')
  const tools = readSource('components/SourceTabTools.vue')
  const json = readSource('components/SourceJson.vue')
  const config = readSource('config/bookSourceEditConfig.ts')

  assert.match(editor, /store\.currentSource !== source/)
  assert.match(editor, /let restoringCurrentSource = false/)
  assert.match(editor, /const previousSource = previous\?\.\[1\]/)
  assert.match(editor, /store\.changeCurrentSource\(previousSource\)/)
  assert.match(editor, /source\.bookSourceName.*source\.bookSourceUrl/)
  assert.match(view, /height: 100dvh/)
  assert.match(view, /height: calc\(100dvh - 48px\)/)
  assert.match(
    view,
    /grid-template-rows: minmax\(0, 1fr\) auto minmax\(0, 1fr\)/,
  )
  assert.doesNotMatch(view, /min-height: 520px/)
  assert.match(tools, /set: val => store\.changeTabName\(val\)/)
  assert.doesNotMatch(json, /margin-bottom: 4px/)
  assert.match(config, /返回 -1 表示章评，1 开始表示正文段落/)
  assert.match(
    config,
    /id: 'replyContentRule',[\s\S]*hint: 'text\/replyToName\/img\/audio\/time\/likeCount'/,
  )
})

test('keeps a validated source token after debug transport errors', () => {
  const debugView = readSource('components/SourceDebug.vue')
  const api = readSource('api/api.ts')
  const axios = readSource('api/axios.ts')
  const debugApi = api.slice(
    api.indexOf('const debug = async'),
    api.indexOf('const getProxyCoverUrl'),
  )

  assert.match(debugView, /await API\.saveSource[\s\S]*await API\.debug/)
  assert.doesNotMatch(debugApi, /clearSourceApiToken/)
  assert.match(axios, /'saveBookSource'[\s\S]*'saveRssSource'/)
  assert.match(
    axios,
    /errorMsg\.includes\('访问令牌'\)[\s\S]*clearSourceApiToken\(\)/,
  )
})

test('skips source tokens only when the server disables protection', () => {
  const token = readSource('api/sourceToken.ts')
  const api = readSource('api/api.ts')
  const axios = readSource('api/axios.ts')

  assert.match(token, /getJsSourceApiTokenRequired/)
  assert.match(token, /cache: 'no-store'/)
  assert.match(token, /if \(!response\.ok\) return true/)
  assert.match(token, /catch \{\s*return true\s*\}/)
  assert.match(token, /if \(!\(await isSourceApiTokenRequired\(\)\)\) return undefined/)
  assert.match(
    token,
    /token \? \['legado', sourceApiTokenWebSocketProtocol\(token\)\] : \['legado'\]/,
  )
  assert.match(axios, /if \(token\) config\.headers\.set\('X-Legado-Token', token\)/)
  assert.match(api, /token: string \| undefined/)
  assert.match(api, /sourceApiTokenWebSocketProtocols\(token\)/)
})
