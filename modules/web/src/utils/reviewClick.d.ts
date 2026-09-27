export type LegacyReviewClick = {
  kind: 'paragraph' | 'chapter'
  src: string
}

export declare const parseLegacyReviewClick: (
  src: string,
) => LegacyReviewClick | null
