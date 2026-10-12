import type { ImportItem, ImportOption, ItemFilter, ImportSummary, ItemPatch } from './importApi'

export const MAX_FILE_BYTES = 20 * 1024 * 1024
export const ACCEPT = '.docx,.pdf,.png,.jpg,.jpeg'
export const LOW_CONFIDENCE = 0.8

export const FLAG_LABEL: Record<string, string> = {
  OCR: 'Nhận dạng ảnh (OCR)', LOW_CONFIDENCE: 'Độ tin cậy thấp', MISSING_CORRECT_ANSWER: 'Chưa có đáp án đúng',
  ANSWER_NOT_IN_OPTIONS: 'Đáp án không khớp phương án', MULTIPLE_ANSWERS: 'Nhiều đáp án', DUPLICATE_OPTION: 'Phương án trùng',
  OPTION_SEQUENCE_GAP: 'Thiếu phương án trong dãy', NO_ANSWER_ESSAY: 'Tự luận chưa có đáp án', CONTENT_TRUNCATED: 'Nội dung bị cắt',
}

export const FILTERS: { value: ItemFilter; label: string; count: (s: ImportSummary) => number }[] = [
  { value: 'ALL', label: 'Tất cả', count: s => s.total },
  { value: 'NEEDS_REVIEW', label: 'Cần xem lại', count: s => s.needsReview },
  { value: 'INVALID', label: 'Chưa hợp lệ', count: s => s.invalid },
  { value: 'IMPORTABLE', label: 'Sẵn sàng nhập', count: s => s.importable },
  { value: 'IMPORTED', label: 'Đã nhập', count: s => s.imported },
  { value: 'EXCLUDED', label: 'Đã loại', count: s => s.excluded },
]

/** OCR or low confidence must stand out and be reviewed (contract C). */
export const mustReview = (i: Pick<ImportItem, 'reviewRequired' | 'reviewed' | 'excluded' | 'importedQuestionId'>) =>
  i.reviewRequired && !i.reviewed && !i.excluded && !i.importedQuestionId
export const isOcr = (i: Pick<ImportItem, 'source' | 'flags'>) => i.source.method === 'OCR' || i.flags.includes('OCR')
export const lowConfidence = (i: Pick<ImportItem, 'confidence'>) => i.confidence < LOW_CONFIDENCE

export function itemState(i: ImportItem): { label: string; tone: 'success' | 'warning' | 'danger' | 'muted' | 'neutral' } {
  if (i.importedQuestionId) return { label: 'Đã nhập', tone: 'success' }
  if (i.excluded) return { label: 'Đã loại', tone: 'muted' }
  if (!i.valid) return { label: 'Chưa hợp lệ', tone: 'danger' }
  if (mustReview(i)) return { label: 'Cần xem lại', tone: 'warning' }
  return { label: 'Sẵn sàng', tone: 'neutral' }
}

export function fileProblem(f: File | null) {
  if (!f) return 'Chọn một tệp DOCX, PDF hoặc ảnh PNG/JPG.'
  if (!/\.(docx|pdf|png|jpe?g)$/i.test(f.name)) return 'Chỉ nhận DOCX, PDF, PNG hoặc JPG.'
  if (f.size > MAX_FILE_BYTES) return 'Tệp vượt quá 20 MB.'
  return null
}

export interface Draft { content: string; questionType: ImportItem['questionType']; difficultyLevel: ImportItem['difficultyLevel']; categoryId: string; options: ImportOption[]; expectedAnswer: string; explanation: string }
export const draftOf = (i: ImportItem): Draft => ({
  content: i.content, questionType: i.questionType, difficultyLevel: i.difficultyLevel, categoryId: i.categoryId ?? '',
  options: i.options.map((o, n) => ({ ...o, displayOrder: n })), expectedAnswer: i.expectedAnswer ?? '', explanation: i.explanation ?? '',
})

/** Only changed fields are sent (null = unchanged). Options always go as a whole list in display order. */
export function patchOf(i: ImportItem, d: Draft, extra: Pick<ItemPatch, 'reviewed' | 'excluded'> = {}): ItemPatch {
  const base = draftOf(i)
  const p: ItemPatch = { version: i.version, ...extra }
  if (d.content !== base.content) p.content = d.content
  if (d.questionType !== base.questionType) p.questionType = d.questionType
  if (d.difficultyLevel !== base.difficultyLevel) p.difficultyLevel = d.difficultyLevel
  if (d.categoryId && d.categoryId !== base.categoryId) p.categoryId = d.categoryId
  const opts = d.options.map((o, n) => ({ content: o.content, correct: o.correct, displayOrder: n }))
  if (JSON.stringify(opts) !== JSON.stringify(base.options.map((o, n) => ({ content: o.content, correct: o.correct, displayOrder: n })))) p.options = opts
  if (d.expectedAnswer !== base.expectedAnswer) p.expectedAnswer = d.expectedAnswer
  if (d.explanation !== base.explanation) p.explanation = d.explanation
  return p
}

export const dirty = (i: ImportItem, d: Draft) => Object.keys(patchOf(i, d)).length > 1

export const optionLetter = (n: number) => String.fromCharCode(65 + n)
