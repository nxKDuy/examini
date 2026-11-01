/** Pure helpers for the administrator statistics page. */

/** Sum of the last `n` values and of the `n` before them (for "this week vs last week"). */
export function lastVsPrevious(values: number[], n = 7): { last: number; previous: number; change: number | null } {
  const last = values.slice(-n).reduce((s, v) => s + v, 0)
  const previous = values.slice(-2 * n, -n).reduce((s, v) => s + v, 0)
  return { last, previous, change: previous > 0 ? Math.round(((last - previous) * 1000) / previous) / 10 : null }
}

export const share = (part: number, total: number) => (total > 0 ? Math.round((part * 1000) / total) / 10 : 0)

/** Record → rows sorted by value, with labels from a map (unknown keys shown as-is). */
export function rowsOf(record: Record<string, number> | undefined, labels: Record<string, string> = {}): { label: string; value: number }[] {
  return Object.entries(record ?? {}).map(([k, v]) => ({ label: labels[k] ?? k, value: v })).sort((a, b) => b.value - a.value)
}

export interface Highlight { tone: 'good' | 'warn' | 'info'; key: string; vars: Record<string, string | number> }
/** Short findings for administrators, derived only from the figures on the page. */
export function highlights(input: {
  attemptsPerDay: number[]; usersNew: number; locked: number; needsReview: number; submissions: number
  aiGenerated: number; aiImported: number; aiJobs: number; aiFailed: number; questionsTotal: number; aiQuestions: number
}): Highlight[] {
  const out: Highlight[] = []
  const w = lastVsPrevious(input.attemptsPerDay)
  if (w.change !== null && Math.abs(w.change) >= 20) out.push({ tone: w.change > 0 ? 'good' : 'warn', key: w.change > 0 ? 'ATTEMPTS_UP' : 'ATTEMPTS_DOWN', vars: { 0: Math.abs(w.change), 1: w.last } })
  else if (w.last > 0) out.push({ tone: 'info', key: 'ATTEMPTS_WEEK', vars: { 0: w.last } })
  if (input.needsReview > 0) out.push({ tone: input.needsReview * 5 > Math.max(1, input.submissions) ? 'warn' : 'info', key: 'BACKLOG', vars: { 0: input.needsReview } })
  if (input.aiGenerated > 0) out.push({ tone: share(input.aiImported, input.aiGenerated) >= 50 ? 'good' : 'info', key: 'AI_ACCEPT', vars: { 0: share(input.aiImported, input.aiGenerated), 1: input.aiImported, 2: input.aiGenerated } })
  if (input.aiJobs > 0 && input.aiFailed * 5 >= input.aiJobs) out.push({ tone: 'warn', key: 'AI_FAIL', vars: { 0: share(input.aiFailed, input.aiJobs) } })
  if (input.questionsTotal > 0) out.push({ tone: 'info', key: 'AI_SHARE', vars: { 0: share(input.aiQuestions, input.questionsTotal) } })
  if (input.locked > 0) out.push({ tone: 'info', key: 'LOCKED', vars: { 0: input.locked } })
  if (input.usersNew > 0) out.push({ tone: 'good', key: 'NEW_USERS', vars: { 0: input.usersNew } })
  return out
}
