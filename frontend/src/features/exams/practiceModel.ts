import type { PracticeCheck } from './examApi'

/** Types a practice attempt can check at once; essays and speaking wait for the teacher. */
export const CHECKABLE = ['MULTIPLE_CHOICE', 'TRUE_FALSE', 'SHORT_ANSWER', 'MULTIPLE_RESPONSE', 'TRUE_FALSE_GROUP', 'FILL_BLANK', 'MATCHING']

export type Verdict = 'right' | 'partial' | 'wrong'
export const verdictOf = (c: PracticeCheck): Verdict => (c.correct ? 'right' : c.share > 0 ? 'partial' : 'wrong')

/** Per option id: 'right' / 'wrong' for statements, gaps and pairs; for choice questions the key and the wrong pick. */
export function partStates(c: PracticeCheck | undefined, chosen: string[]): Record<string, 'right' | 'wrong'> {
  if (!c) return {}
  const out: Record<string, 'right' | 'wrong'> = {}
  for (const [id, ok] of Object.entries(c.parts ?? {})) out[id] = ok ? 'right' : 'wrong'
  for (const id of c.correctOptionIds ?? []) out[id] = 'right'
  for (const id of chosen) if ((c.correctOptionIds ?? []).length && !out[id]) out[id] = 'wrong'
  return out
}
