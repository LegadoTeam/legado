import axios from 'axios'
import {
  clearSourceApiToken,
  getSourceApiToken,
  requestSourceApiToken,
} from './sourceToken'

/** @type {string} localStorage保存自定义阅读http服务接口的键值 */
export const baseURL_localStorage_key = 'remoteUrl'
const SECOND = 1000
const protectedSourcePaths = new Set([
  'saveJsSource',
  'saveBookSource',
  'saveBookSources',
  'deleteBookSources',
  'saveRssSource',
  'saveRssSources',
  'deleteRssSources',
  'saveReplaceRule',
  'deleteReplaceRule',
  'testReplaceRule',
])

const ajax = axios.create({
  baseURL:
    import.meta.env.VITE_API ||
    localStorage.getItem(baseURL_localStorage_key) ||
    location.origin,
  timeout: 120 * SECOND,
})

ajax.interceptors.request.use(async config => {
  const path = new URL(
    config.url || '',
    config.baseURL || location.origin,
  ).pathname
  const endpoint = path.split('/').filter(Boolean).pop() || ''
  if (protectedSourcePaths.has(endpoint)) {
    const token = getSourceApiToken() || (await requestSourceApiToken())
    config.headers.set('X-Legado-Token', token)
  }
  return config
})

ajax.interceptors.response.use(response => {
  const errorMsg = response.data?.errorMsg
  if (typeof errorMsg === 'string' && errorMsg.includes('访问令牌')) {
    clearSourceApiToken()
  }
  return response
})

export default ajax
