import type { Source } from '@/source'
export type SourceCheckState = {
  bookSourceUrl: string
  sourceRevision: string
  status: 'NEEDS_CHECK' | 'PASSED' | 'FAILED' | 'UNCHECKABLE'
  detail: string
}
export type SourceCheckSnapshot = { content: string; sourceRevision: string }
export function sourceCheckContent(source: Source): string
export function sourceCheckStatus(source: Source, snapshot?: SourceCheckSnapshot, state?: SourceCheckState): SourceCheckState['status']
export function sourceCheckDetailMatches(source: Source, snapshot: SourceCheckSnapshot | undefined, state: SourceCheckState | undefined, searchKey: string): boolean
export function sourceCheckSnapshots(sources: Source[], states: SourceCheckState[]): Record<string, SourceCheckSnapshot>
export const sourceCheckOptions: { value: string; label: string }[]
