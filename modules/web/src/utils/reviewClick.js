const legacyReviewPattern =
  /["']click["']\s*:\s*["']getDP\(\s*(\d+)\s*,\s*(\d+)\s*\)["']/

export const parseLegacyReviewClick = src => {
  let original = src
  try {
    original = new URL(src, 'http://localhost').searchParams.get('path') || src
  } catch {}

  const match = original.match(legacyReviewPattern)
  if (!match) return null

  const paragraph = Number(match[1])
  const count = Number(match[2])
  if (!Number.isSafeInteger(paragraph) || !Number.isSafeInteger(count)) return null

  return {
    paraIndex: paragraph + 1,
    paraData: match[1],
    count,
  }
}
