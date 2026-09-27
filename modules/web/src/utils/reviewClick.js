const legacyReviewPattern =
  /(["'])click\1\s*:\s*(["'])(getDP\(\s*\d+\s*,\s*\d+\s*\)|getZP\(\s*\d+\s*\))\2/

export const parseLegacyReviewClick = src => {
  let original = src
  try {
    original = new URL(src, 'http://localhost').searchParams.get('path') || src
  } catch {}

  const click = original.match(legacyReviewPattern)?.[3]
  if (!click) return null
  return { kind: click.startsWith('getDP') ? 'paragraph' : 'chapter', src: original }
}
