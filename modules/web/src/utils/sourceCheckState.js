const ignored = new Set(['customOrder', 'enabled', 'enabledExplore', 'lastUpdateTime', 'respondTime', 'weight'])
const defaults = { bookSourceType: 0, enabledCookieJar: true, eventListener: false, customButton: false }

/** Compare rule content without changing the editor's source or its export. */
export const sourceCheckContent = source => {
  const normalize = (value, root = false) => {
    if (Array.isArray(value)) return value.map(item => normalize(item))
    if (!value || typeof value !== 'object') return value
    return Object.fromEntries(Object.keys(value).sort().flatMap(key => {
      const item = value[key]
      if (root && (ignored.has(key) || (key in defaults && item === defaults[key]))) return []
      if (item == null || (typeof item === 'string' && !item.trim())) return []
      const normalized = normalize(item)
      if (normalized && typeof normalized === 'object' && !Array.isArray(normalized) && !Object.keys(normalized).length) return []
      return [[key, normalized]]
    }))
  }
  return JSON.stringify(normalize(source, true))
}

export const sourceCheckStatus = (source, snapshot, state) =>
  snapshot && state && snapshot.content === sourceCheckContent(source) &&
  snapshot.sourceRevision === state.sourceRevision ? state.status : 'NEEDS_CHECK'

export const sourceCheckSnapshots = (sources, states) => {
  const revisions = new Map(states.map(state => [state.bookSourceUrl, state.sourceRevision]))
  return Object.fromEntries(sources.map(source => [source.bookSourceUrl, {
    content: sourceCheckContent(source), sourceRevision: revisions.get(source.bookSourceUrl) || '',
  }]))
}

export const sourceCheckOptions = [
  { value: '', label: '全部检验状态' },
  { value: 'NEEDS_CHECK', label: '需要检验' },
  { value: 'PASSED', label: '检验成功' },
  { value: 'FAILED', label: '检验失败' },
]
