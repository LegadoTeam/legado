import { ElMessageBox } from 'element-plus'

const sourceApiTokenKey = 'sourceApiToken'
const sourceApiEndpointKey = 'sourceApiEndpoint'
let sourceApiToken = sessionStorage.getItem(sourceApiTokenKey) || undefined
let sourceApiEndpoint = sessionStorage.getItem(sourceApiEndpointKey) || undefined

// Remove credentials persisted by earlier development builds.
localStorage.removeItem('apiToken')

export const getSourceApiToken = () => sourceApiToken

export const clearSourceApiToken = () => {
  sourceApiToken = undefined
  sessionStorage.removeItem(sourceApiTokenKey)
}

export const bindSourceApiTokenEndpoint = (endpoint: string) => {
  if (sourceApiEndpoint && sourceApiEndpoint !== endpoint) clearSourceApiToken()
  sourceApiEndpoint = endpoint
  sessionStorage.setItem(sourceApiEndpointKey, endpoint)
}

const isSourceApiTokenRequired = async () => {
  try {
    const response = await fetch(
      new URL(
        'getJsSourceApiTokenRequired',
        sourceApiEndpoint || location.origin,
      ),
      { cache: 'no-store' },
    )
    if (!response.ok) return true
    const result = (await response.json()) as {
      isSuccess?: boolean
      data?: boolean
    }
    return result.isSuccess !== true || result.data !== false
  } catch {
    return true
  }
}

export const requestSourceApiToken = async (
  options: { force?: boolean; remember?: boolean } = {},
) => {
  if (!(await isSourceApiTokenRequired())) return undefined
  const remember = options.remember ?? true
  const currentToken = remember ? sourceApiToken : undefined
  if (!options.force && currentToken) return currentToken

  const { value } = await ElMessageBox.prompt(
    '请输入阅读应用中配置的访问令牌',
    'Web 与 MCP 访问令牌',
    {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputValue: currentToken || '',
      inputType: 'password',
      inputValidator: value => value.trim().length > 0 || '令牌不能为空',
      inputErrorMessage: '请输入令牌',
    },
  )
  const token = value.trim()
  if (remember) {
    sourceApiToken = token
    sessionStorage.setItem(sourceApiTokenKey, token)
  }
  return token
}

export const sourceApiTokenWebSocketProtocol = (token: string) => {
  const bytes = new TextEncoder().encode(token)
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  const encoded = btoa(binary)
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
  return `legado.token.${encoded}`
}

export const sourceApiTokenWebSocketProtocols = (token?: string) =>
  token ? ['legado', sourceApiTokenWebSocketProtocol(token)] : ['legado']
