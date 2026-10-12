import type { DraftRequest, GradeItem, Submission, SubmissionDetail } from './gradingApi'

/** Pure grading-form logic: what to send, what is valid, what the Student sees. No React, no fetch. */

/** The answer IS one of the offered options (shown by option label; may arrive as an option id). Short answers are free text. */
export const isChoiceAnswer = (type: string) => type === 'MULTIPLE_CHOICE' || type === 'TRUE_FALSE'
/** The server auto-scores it, so changing that score is an override that needs its own reason. Includes SHORT_ANSWER. */
export const isAutoGraded = (type: string) => isChoiceAnswer(type) || type === 'SHORT_ANSWER'
export const shortStudent = (id: string) => id.slice(0, 8)
export const studentName = (submission: { studentId: string; studentDisplayName?: string | null }) => submission.studentDisplayName?.trim() || `Thí sinh ${shortStudent(submission.studentId)}`

export interface ItemForm { score: string; feedback: string; overrideReason: string }
export interface GradeForm { feedback: string; items: Record<string, ItemForm> }

const num = (v: number | null | undefined) => (v === null || v === undefined ? '' : String(v))

/** A score box starts with the Teacher's saved manual score only; auto scores are shown beside it and stay untouched unless overridden. */
export function formFromDetail(d: SubmissionDetail): GradeForm {
  return {
    feedback: d.feedback ?? '',
    items: Object.fromEntries(d.items.map(i => [i.questionId, { score: num(i.manualScore), feedback: i.feedback ?? '', overrideReason: '' }])),
  }
}

const parseScore = (s: string) => (s.trim() === '' ? null : Number(s))

export function scoreProblem(item: GradeItem, f: ItemForm): string | null {
  const v = parseScore(f.score)
  if (v === null) return null
  if (!Number.isFinite(v)) return 'Nhập một số hợp lệ.'
  const max = item.pointValue ?? 0
  if (v < 0 || v > max) return `Điểm phải từ 0 đến ${max}.`
  return null
}

/** Overriding an auto-graded score (choice or short answer) needs its own internal reason (never shown to the Student). */
export function reasonRequired(item: GradeItem, f: ItemForm, initial: ItemForm): boolean {
  const v = parseScore(f.score)
  if (v === null || !isAutoGraded(item.questionType)) return false
  if (f.score.trim() === initial.score.trim()) return false // unchanged, already overridden earlier
  return v !== item.autoScore
}

export function itemProblems(d: SubmissionDetail, form: GradeForm, initial: GradeForm): Record<string, { score?: string; reason?: string }> {
  const out: Record<string, { score?: string; reason?: string }> = {}
  for (const i of d.items) {
    const f = form.items[i.questionId]; if (!f) continue
    const score = scoreProblem(i, f)
    const reason = reasonRequired(i, f, initial.items[i.questionId]) && f.overrideReason.trim() === '' ? 'Nhập lý do khi sửa điểm tự động.' : undefined
    if (score || reason) out[i.questionId] = { ...(score ? { score } : {}), ...(reason ? { reason } : {}) }
  }
  return out
}

export function isDirty(form: GradeForm, initial: GradeForm): boolean {
  if (form.feedback !== initial.feedback) return true
  return Object.keys(form.items).some(id => {
    const a = form.items[id], b = initial.items[id]
    return !b || a.score !== b.score || a.feedback !== b.feedback || a.overrideReason.trim() !== ''
  })
}

/** Scores that did not change are sent as null (server keeps them); feedback is sent as typed, empty clears it. */
export function toDraftRequest(d: SubmissionDetail, form: GradeForm, initial: GradeForm): DraftRequest {
  return {
    expectedRevision: d.resultRevision,
    feedback: form.feedback.trim() === '' ? null : form.feedback,
    items: d.items.map(i => {
      const f = form.items[i.questionId], b = initial.items[i.questionId]
      const changed = f.score !== b.score
      return {
        questionId: i.questionId,
        score: changed ? parseScore(f.score) : null,
        feedback: f.feedback.trim() === '' ? null : f.feedback,
        overrideReason: changed && f.overrideReason.trim() !== '' ? f.overrideReason.trim() : null,
      }
    }),
  }
}

/** Draft total: typed score, else what the server already awards. Essays with nothing yet count 0 here (the server decides the real total). */
export function previewTotal(d: SubmissionDetail, form: GradeForm): number {
  return d.items.reduce((sum, i) => {
    const v = parseScore(form.items[i.questionId]?.score ?? '')
    return sum + (v !== null && Number.isFinite(v) ? v : i.awardedPoints ?? 0)
  }, 0)
}

/** Items that still need a Teacher score: flagged by the server and not yet scored in the form. */
export function unresolvedItems(d: SubmissionDetail, form: GradeForm): GradeItem[] {
  return d.items.filter(i => i.needsReview && parseScore(form.items[i.questionId]?.score ?? '') === null && i.manualScore === null)
}

export type Publication = 'NONE' | 'CURRENT' | 'OUTDATED'
/** What the Student can see compared with the current result: nothing, this very revision, or an older one. */
export function publicationOf(s: Pick<Submission, 'publishedRevision' | 'resultRevision'>): Publication {
  if (s.publishedRevision === null || s.publishedRevision === undefined) return 'NONE'
  return s.publishedRevision === s.resultRevision ? 'CURRENT' : 'OUTDATED'
}
/** An invalidated attempt keeps its result and audit, but nothing about it may be graded, released or regraded (the server answers 409 RESULT_INVALIDATED). */
export const isInvalidated = (s: { invalidated?: boolean }) => s.invalidated === true
export const canEdit = (s: Pick<Submission, 'status' | 'invalidated'>) => s.status !== 'GRADED' && !isInvalidated(s)
export const canRelease = (s: Pick<Submission, 'status' | 'publishedRevision' | 'resultRevision' | 'invalidated'>) => s.status === 'GRADED' && publicationOf(s) !== 'CURRENT' && !isInvalidated(s)
export const canRegrade = (s: Pick<Submission, 'status' | 'invalidated'>) => s.status === 'GRADED' && !isInvalidated(s)

export function scoreDelta(draft: number | null, published: number | null): number | null {
  if (draft === null || published === null) return null
  return Math.round((draft - published) * 100) / 100
}

export const isStaleRevision = (error: unknown) => {
  const e = error as { response?: { status?: number; data?: { detail?: string } } }
  return e?.response?.status === 409 && /STALE_RESULT_REVISION/.test(e.response.data?.detail ?? '')
}

// ---- Student side -----------------------------------------------------------------------

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
/** Own answer text; an objective answer that is only an option id cannot be shown by name (Contract Request), so say so instead of guessing. */
export function studentAnswerText(type: string, answer: string | undefined, answerDisplay?: string | null): { text: string; unresolved: boolean } | null {
  if (answerDisplay !== undefined && answerDisplay !== null && answerDisplay.trim() !== '') return { text: answerDisplay, unresolved: false }
  if (answer === undefined || answer === null || answer.trim() === '') return null
  if (isChoiceAnswer(type) && UUID.test(answer.trim())) return { text: 'Đã chọn một phương án', unresolved: true }
  return { text: answer, unresolved: false }
}
export const readableAnswers = (list: string[] | undefined) => (list ?? []).filter(a => !UUID.test(a.trim()))

export type StudentPhase = 'IN_PROGRESS' | 'WAITING' | 'REVIEWING' | 'UNRELEASED' | 'PUBLISHED' | 'UNKNOWN'
export function studentPhase(status: string): StudentPhase {
  switch (status) {
    case 'IN_PROGRESS': return 'IN_PROGRESS'
    case 'SUBMITTED': case 'GRADING': return 'WAITING'
    case 'NEEDS_REVIEW': return 'REVIEWING'
    case 'GRADED_UNRELEASED': return 'UNRELEASED'
    case 'GRADED': return 'PUBLISHED'
    default: return 'UNKNOWN'
  }
}
export const studentPolls = (status: string | undefined): number | false => {
  const p = status ? studentPhase(status) : 'WAITING'
  return p === 'WAITING' ? 3000 : p === 'REVIEWING' || p === 'UNRELEASED' ? 30_000 : false
}
