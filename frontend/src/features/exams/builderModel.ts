// Exam Builder selection rules (scope lock B). Pure so they are testable without the UI.
export const MAX_EXAM_QUESTIONS = 200
export const BATCH_CHUNK = 200

/**
 * Adds ids to an ordered selection without duplicates (by questionId) and without
 * passing the exam limit. Returns what was added and what did not fit.
 */
export function addToSelection(selected: string[], incoming: string[], inExam: string[], limit = MAX_EXAM_QUESTIONS) {
  const taken = new Set([...inExam, ...selected])
  const room = Math.max(0, limit - inExam.length - selected.length)
  const fresh = incoming.filter((id, i) => !taken.has(id) && incoming.indexOf(id) === i)
  const added = fresh.slice(0, room)
  return { next: [...selected, ...added], added: added.length, duplicates: incoming.length - fresh.length, overflow: fresh.length - added.length }
}

export const chunk = <T,>(items: T[], size = BATCH_CHUNK) =>
  Array.from({ length: Math.ceil(items.length / size) }, (_, i) => items.slice(i * size, i * size + size))

export const SKIP_REASON: Record<string, string> = {
  QUESTION_NOT_FOUND: 'Câu hỏi không còn tồn tại',
  NOT_ACCESSIBLE: 'Bạn không còn quyền dùng câu này',
  NOT_ACTIVE: 'Câu chưa kích hoạt hoặc đã lưu trữ',
}

export const totalPoints = (items: { weight: number }[]) => items.reduce((s, i) => s + (Number.isFinite(i.weight) ? i.weight : 0), 0)

/** Sets the same points on every listed question (bulk edit in the selected panel). */
export const setAllPoints = <T extends { weight: number },>(items: T[], weight: number) => items.map(i => ({ ...i, weight }))
