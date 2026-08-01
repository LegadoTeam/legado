export const MAX_RETAINED_CHAPTERS = 10

export const appendToChapterWindow = (chapters, chapter) => {
  const appended = [...chapters, chapter]
  const removedCount = Math.max(appended.length - MAX_RETAINED_CHAPTERS, 0)
  return {
    chapters: removedCount === 0 ? appended : appended.slice(removedCount),
    removedCount,
  }
}
