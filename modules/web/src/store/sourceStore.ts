import { defineStore } from 'pinia'
import {
  emptyBookSource,
  emptyRssSource,
  getSourceUniqueKey,
  convertSourcesToMap,
} from '@utils/souce'
import type { BookSoure, RssSource, Source } from '@/source'
import { sourceCheckContent, sourceCheckStatus, sourceCheckSnapshots } from '@/utils/sourceCheckState'
import type { SourceCheckState, SourceCheckSnapshot } from '@/utils/sourceCheckState'

const isBookSource = /bookSource/i.test(location.href)
const emptySource = isBookSource ? emptyBookSource : emptyRssSource

export const useSourceStore = defineStore('source', {
  state: () => {
    return {
      bookSources: shallowRef([] as BookSoure[]), // 临时存放所有书源,
      rssSources: shallowRef([] as RssSource[]), // 临时存放所有订阅源
      savedSources: [] as Source[], // 批量保存到阅读app成功的源
      currentSource: JSON.parse(JSON.stringify(emptySource)) as Source, // 当前编辑的源
      sourceMode: 'json' as 'json' | 'javascript',
      currentTab: localStorage.getItem('tabName') || 'editTab',
      editTabSource: {} as Source, // 生成序列化的json数据
      isDebuging: false,
      checkStatusFilter: '',
      checkStates: {} as Record<string, SourceCheckState>,
      checkSnapshots: {} as Record<string, SourceCheckSnapshot>,
      checkSessionToken: null as string | null,
    }
  },
  getters: {
    sources: (state): Source[] =>
      isBookSource ? state.bookSources : state.rssSources,
    sourcesMap: function (): Map<string, Source> {
      return convertSourcesToMap(this.sources)
    },
    savedSourcesMap: (state): Map<string, Source> =>
      convertSourcesToMap(state.savedSources),
    currentSourceUrl: state =>
      isBookSource
        ? (state.currentSource as BookSoure).bookSourceUrl
        : (state.currentSource as RssSource).sourceUrl,
    searchKey: (state): string =>
      isBookSource
        ? (state.currentSource as BookSoure)?.ruleSearch?.checkKeyWord || '我的'
        : '',
  },
  actions: {
    setCheckStates(states: SourceCheckState[], sessionToken: string | null = null) {
      this.checkStates = Object.fromEntries(states.map(state => [state.bookSourceUrl, state]))
      this.checkSessionToken = sessionToken
    },
    rememberDeviceSources(sources: Source[], states: SourceCheckState[]) {
      this.setCheckStates(states)
      this.checkSnapshots = sourceCheckSnapshots(sources, states)
    },
    rememberCheckStart(sources: Source[], revisions: Record<string, string>) {
      sources.forEach(source => {
        const url = getSourceUniqueKey(source)
        this.checkSnapshots[url] = { content: sourceCheckContent(source), sourceRevision: revisions[url] }
        this.checkStates[url] = { bookSourceUrl: url, sourceRevision: revisions[url], status: 'NEEDS_CHECK', detail: '' }
      })
    },
    rememberSavedSources(sources: Source[], states: SourceCheckState[]) {
      states.forEach(state => { this.checkStates[state.bookSourceUrl] = state })
      Object.assign(this.checkSnapshots, sourceCheckSnapshots(sources, states))
    },
    invalidateCheckSources(sources: Source[]) {
      sources.forEach(source => { delete this.checkSnapshots[getSourceUniqueKey(source)] })
    },
    checkStatus(source: Source) {
      const url = getSourceUniqueKey(source)
      return sourceCheckStatus(source, this.checkSnapshots[url], this.checkStates[url])
    },
    startDebug() {
      this.currentTab = 'editDebug'
      this.isDebuging = true
    },
    debugFinish() {
      this.isDebuging = false
    },

    //拉取源后保存
    saveSources(data: Source[]) {
      if (isBookSource) {
        this.bookSources = markRaw(data) as BookSoure[]
      } else {
        this.rssSources = markRaw(data) as RssSource[]
      }
    },
    //批量推送
    setPushReturnSources(returnSoures: Source[]) {
      this.savedSources = returnSoures
    },
    //删除源
    deleteSources(data: Source[]) {
      const sources: Source[] = isBookSource
        ? this.bookSources
        : this.rssSources
      data.forEach(source => {
        const index = sources.indexOf(source)
        if (index > -1) sources.splice(index, 1)
      })
    },
    //保存当前编辑源
    saveCurrentSource() {
      const source = this.currentSource,
        map = this.sourcesMap
      map.set(getSourceUniqueKey(source), JSON.parse(JSON.stringify(source)))
      this.saveSources(Array.from(map.values()))
    },
    updateSource(oldKey: string, source: Source) {
      const list = this.sources
      const index = list.findIndex(item => getSourceUniqueKey(item) === oldKey)
      if (index === -1) return
      const next = [...list]
      next[index] = source
      this.saveSources(next)
    },
    saveJsSource(source: BookSoure, openedSourceUrl?: string) {
      const map = this.sourcesMap
      if (openedSourceUrl && openedSourceUrl !== source.bookSourceUrl) {
        map.delete(openedSourceUrl)
      }
      map.set(source.bookSourceUrl, JSON.parse(JSON.stringify(source)))
      this.saveSources(Array.from(map.values()))
    },
    // 更改当前编辑的源qq
    changeCurrentSource(source: Source) {
      this.currentSource = JSON.parse(JSON.stringify(source))
      if (isBookSource && (source as BookSoure).mainJs?.trim()) {
        this.sourceMode = 'javascript'
      }
    },
    // update editTab tabName and editTab info
    changeTabName(tabName: string) {
      this.currentTab = tabName
      localStorage.setItem('tabName', tabName)
    },
    changeEditTabSource(source: Source) {
      this.editTabSource = JSON.parse(JSON.stringify(source))
    },
    editHistory(history: Source) {
      let historyObj
      if (localStorage.getItem('history')) {
        historyObj = JSON.parse(localStorage.getItem('history')!)
        historyObj.new.push(history)
        if (historyObj.new.length > 50) {
          historyObj.new.shift()
        }
        if (historyObj.old.length > 50) {
          historyObj.old.shift()
        }
        localStorage.setItem('history', JSON.stringify(historyObj))
      } else {
        const arr = { new: [history], old: [] }
        localStorage.setItem('history', JSON.stringify(arr))
      }
    },
    editHistoryUndo() {
      if (localStorage.getItem('history')) {
        const historyObj = JSON.parse(localStorage.getItem('history')!)
        historyObj.old.push(this.currentSource)
        if (historyObj.new.length) {
          this.currentSource = historyObj.new.pop()
        }
        localStorage.setItem('history', JSON.stringify(historyObj))
      }
    },
    clearAllHistory() {
      localStorage.setItem('history', JSON.stringify({ new: [], old: [] }))
    },
    clearEdit() {
      this.editTabSource = {} as Source
      this.currentSource = JSON.parse(JSON.stringify(emptySource)) //复制一份新对象
    },

    // clear all source
    clearAllSource() {
      this.bookSources = []
      this.rssSources = []
      this.savedSources = []
    },
  },
})
