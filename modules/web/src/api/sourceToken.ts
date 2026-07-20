import { ElMessageBox } from 'element-plus'

let sourceApiToken: string | undefined

// Remove credentials persisted by earlier development builds.
localStorage.removeItem('apiToken')

export const getSourceApiToken = () => sourceApiToken

export const clearSourceApiToken = () => {
  sourceApiToken = undefined
}

export const requestSourceApiToken = async (
  options: { force?: boolean; remember?: boolean } = {},
) => {
  const remember = options.remember ?? true
  const currentToken = remember ? sourceApiToken : undefined
  if (!options.force && currentToken) return currentToken

  const { value } = await ElMessageBox.prompt(
    '请输入阅读 Web 服务中配置的访问令牌',
    'Web 书源访问令牌',
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
  if (remember) sourceApiToken = token
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
