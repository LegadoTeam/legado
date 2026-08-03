export type LegacyReviewClick = {
  paraIndex: number
  paraData: string
  count: number
}

export declare const parseLegacyReviewClick: (
  src: string,
) => LegacyReviewClick | null
