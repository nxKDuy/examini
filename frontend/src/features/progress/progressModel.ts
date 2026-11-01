import type { ProgressItem } from './progressApi'

/** Scored, valid results oldest → newest (at most `limit`), for the progress line. */
export function scoredTimeline(items: ProgressItem[], limit = 20): ProgressItem[] {
  const when = (i: ProgressItem) => Date.parse(i.releasedAt ?? i.submittedAt ?? '') || 0
  return items.filter(i => !i.invalidated && i.percent !== null).sort((a, b) => when(a) - when(b)).slice(-limit)
}

/** Short x label: "30/10" plus the attempt when one exam appears several times. */
export function pointLabel(i: ProgressItem, repeated: boolean): string {
  const d = new Date(i.releasedAt ?? i.submittedAt ?? '')
  const day = Number.isNaN(d.getTime()) ? '?' : `${d.getDate()}/${d.getMonth() + 1}`
  return repeated ? `${day} #${i.attemptNumber}` : day
}

export type Trend = 'UP' | 'DOWN' | 'FLAT' | 'NONE'
export const trendOf = (t: number | null): Trend => (t === null ? 'NONE' : t >= 5 ? 'UP' : t <= -5 ? 'DOWN' : 'FLAT')
