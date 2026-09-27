import bookSourceEditConfig from './bookSourceEditConfig'
import rssSourceEditConfig from './rssSourceEditConfig'

type SourceConfigKey =
  | keyof typeof bookSourceEditConfig
  | keyof typeof rssSourceEditConfig
type SourceConfigRecord = {
  title: string
  type: string //"array" | "String" | "Boolean"
  array?: string[]
  hint?: string
  required?: boolean
  namespace?: string
  id: string
}
type SourceConfigValue = { name: string; children: SourceConfigRecord[] }
export type SourceConfig = Partial<Record<SourceConfigKey, SourceConfigValue>>
