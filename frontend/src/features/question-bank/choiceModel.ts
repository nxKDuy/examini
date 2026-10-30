/**
 * Answer formats of the option-based types (contracts/question-media-and-skills.md):
 *  MULTIPLE_CHOICE / TRUE_FALSE: the chosen option id;
 *  MULTIPLE_RESPONSE: JSON array of chosen option ids;
 *  TRUE_FALSE_GROUP: JSON object option id -> true/false (statements left out are unanswered).
 * Pure functions, safe to load in node tests.
 */
export const OPTION_TYPES = ['MULTIPLE_CHOICE', 'TRUE_FALSE', 'MULTIPLE_RESPONSE', 'TRUE_FALSE_GROUP'] as const
export const isOptionType = (t: string) => (OPTION_TYPES as readonly string[]).includes(t)
/** Statements are labelled a), b), c) … like the printed THPT exam. */
export const statementLabel = (i: number) => `${String.fromCharCode(97 + i)})`

export function parseChosen(value: string | null | undefined): string[] {
  if (!value) return []
  try { const v = JSON.parse(value); return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [] } catch { return [] }
}
export function toggleChosen(value: string, id: string, on: boolean): string {
  const set = new Set(parseChosen(value))
  if (on) set.add(id); else set.delete(id)
  return set.size ? JSON.stringify([...set]) : ''
}

export function parseJudged(value: string | null | undefined): Record<string, boolean> {
  if (!value) return {}
  try {
    const v = JSON.parse(value)
    if (!v || typeof v !== 'object' || Array.isArray(v)) return {}
    return Object.fromEntries(Object.entries(v).filter(([, b]) => typeof b === 'boolean')) as Record<string, boolean>
  } catch { return {} }
}
export function judge(value: string, id: string, truth: boolean | null): string {
  const m = parseJudged(value)
  if (truth === null) delete m[id]; else m[id] = truth
  return Object.keys(m).length ? JSON.stringify(m) : ''
}

/** Answered = something the server would keep; a group counts once at least one statement is judged. */
export function isAnswered(type: string, value: string | null | undefined): boolean {
  if (!value || !value.trim()) return false
  if (type === 'MULTIPLE_RESPONSE') return parseChosen(value).length > 0
  if (type === 'TRUE_FALSE_GROUP') return Object.keys(parseJudged(value)).length > 0
  return true
}
/** "2/4 ý" progress of a true/false group. */
export const judgedCount = (value: string, total: number) => Math.min(total, Object.keys(parseJudged(value)).length)

/** THPT 2025 scale for 4 statements (1→10%, 2→25%, 3→50%, 4→100%), proportional otherwise; mirrors grading-service. */
export function groupShare(right: number, total: number): number {
  if (total === 4) return [0, 0.1, 0.25, 0.5, 1][Math.max(0, Math.min(4, right))]
  return total > 0 ? right / total : 0
}
