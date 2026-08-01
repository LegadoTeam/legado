export const MAX_RETAINED_CHAPTERS: number

export function appendToChapterWindow<T>(
  chapters: readonly T[],
  chapter: T,
): { chapters: T[]; removedCount: number }
