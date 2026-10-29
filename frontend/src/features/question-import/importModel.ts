import type { ImportItem, ImportOption, ItemFilter, ImportSummary, ItemPatch } from './importApi'
import { t, i18nMap } from '../../lib/i18n'

export const MAX_FILE_BYTES = 20 * 1024 * 1024
export const ACCEPT = '.docx,.pdf,.png,.jpg,.jpeg'
export const LOW_CONFIDENCE = 0.8

export const FLAG_LABEL: Record<string, string> = i18nMap({
  OCR: 'Nhận dạng ảnh (OCR)', LOW_CONFIDENCE: 'Độ tin cậy thấp', MISSING_CORRECT_ANSWER: 'Chưa có đáp án đúng',
  ANSWER_NOT_IN_OPTIONS: 'Đáp án không khớp phương án', MULTIPLE_ANSWERS: 'Nhiều đáp án', DUPLICATE_OPTION: 'Phương án trùng',
  OPTION_SEQUENCE_GAP: 'Thiếu phương án trong dãy', NO_ANSWER_ESSAY: 'Tự luận chưa có đáp án', CONTENT_TRUNCATED: 'Nội dung bị cắt',
  QUESTION_SPLIT_UNCERTAIN: 'Có thể bị tách câu sai', NUMBERING_GAP: 'Số câu bị nhảy', POSSIBLE_MERGED_QUESTIONS: 'Có thể dồn nhiều câu',
  AI_GENERATED: 'Do AI tạo', POSSIBLE_DUPLICATE: 'Có thể trùng câu đã có',
})

export const FILTERS: { value: ItemFilter; label: string; count: (s: ImportSummary) => number }[] = i18nMap([
  { value: 'ALL', label: 'Tất cả', count: s => s.total },
  { value: 'NEEDS_REVIEW', label: 'Cần xem lại', count: s => s.needsReview },
  { value: 'INVALID', label: 'Chưa hợp lệ', count: s => s.invalid },
  { value: 'IMPORTABLE', label: 'Sẵn sàng nhập', count: s => s.importable },
  { value: 'IMPORTED', label: 'Đã nhập', count: s => s.imported },
  { value: 'EXCLUDED', label: 'Đã loại', count: s => s.excluded },
])

/** OCR or low confidence must stand out and be reviewed (contract C). */
export const mustReview = (i: Pick<ImportItem, 'reviewRequired' | 'reviewed' | 'excluded' | 'importedQuestionId'> & { flags?: string[] }) =>
  (i.reviewRequired || hasUnknownFlag(i.flags ?? [])) && !i.reviewed && !i.excluded && !i.importedQuestionId
/** A flag this client does not know yet is still shown and treated as needing review. */
export const hasUnknownFlag = (flags: string[]) => flags.some(f => !(f in FLAG_LABEL))
export const flagLabel = (f: string) => (f in FLAG_LABEL ? FLAG_LABEL[f] : t('Cần xem lại ({0})', { 0: f }))
export const isOcr = (i: Pick<ImportItem, 'source' | 'flags'>) => i.source.method === 'OCR' || i.flags.includes('OCR')
export const lowConfidence = (i: Pick<ImportItem, 'confidence'>) => i.confidence < LOW_CONFIDENCE
export const isAi = (i: Pick<ImportItem, 'source' | 'flags'>) => i.source.method === 'AI' || i.flags.includes('AI_GENERATED')
/** AI confidence is a structural score, not a measure of correctness; it is shown only when there are problems. */
export const lowConfidenceShown = (i: Pick<ImportItem, 'confidence' | 'source' | 'flags'>) =>
  isAi(i) ? i.flags.some(f => f !== 'AI_GENERATED') : lowConfidence(i)
/** Where an item came from, for list rows and the raw-text summary. */
export const sourceText = (i: Pick<ImportItem, 'source' | 'flags'>) => isAi(i) ? 'AI' : t('trang {0}', { 0: i.source.page ?? '?' })

export interface AiForm { topic: string; categoryId: string; count: number; questionTypes: string[]; material: string }
/** Client-side checks mirror the provider limits; the server re-validates everything. */
export function aiProblems(f: AiForm, maxQuestions = 20, maxMaterial = 20000) {
  const p: Partial<Record<'topic' | 'categoryId' | 'count' | 'questionTypes' | 'material', string>> = {}
  if (!f.topic.trim()) p.topic = t('Nhập chủ đề hoặc mục tiêu kiến thức.')
  else if (f.topic.trim().length > 500) p.topic = t('Chủ đề tối đa 500 ký tự.')
  if (!f.categoryId) p.categoryId = t('Chọn danh mục cho các câu được tạo.')
  if (!Number.isInteger(f.count) || f.count < 1 || f.count > maxQuestions) p.count = t('Số câu từ 1 đến {0}.', { 0: maxQuestions })
  if (!f.questionTypes.length) p.questionTypes = t('Chọn ít nhất một loại câu hỏi.')
  if (f.material.length > maxMaterial) p.material = t('Tài liệu tối đa {0} ký tự.', { 0: maxMaterial })
  return p
}

export function itemState(i: ImportItem): { label: string; tone: 'success' | 'warning' | 'danger' | 'muted' | 'neutral' } {
  if (i.importedQuestionId) return { label: t('Đã nhập'), tone: 'success' }
  if (i.excluded) return { label: t('Đã loại'), tone: 'muted' }
  if (!i.valid) return { label: t('Chưa hợp lệ'), tone: 'danger' }
  if (mustReview(i)) return { label: t('Cần xem lại'), tone: 'warning' }
  return { label: t('Sẵn sàng'), tone: 'neutral' }
}

export function fileProblem(f: File | null) {
  if (!f) return t('Chọn một tệp DOCX, PDF hoặc ảnh PNG/JPG.')
  if (!/\.(docx|pdf|png|jpe?g)$/i.test(f.name)) return t('Chỉ nhận DOCX, PDF, PNG hoặc JPG.')
  if (f.size > MAX_FILE_BYTES) return t('Tệp vượt quá 20 MB.')
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

/** Readable provider names (fallback chain: cloud model → self-hosted model → local generator). */
export const PROVIDER_LABEL: Record<string, string> = i18nMap({ gemini: 'Gemini', ollama: 'Mô hình tự chạy', local: 'Bộ sinh nội bộ (không cần AI)' })
export const providerLabel = (model: string | null | undefined) => {
  if (!model) return ''
  const [name, ...rest] = model.split('/')
  return name in PROVIDER_LABEL ? (name === 'local' ? PROVIDER_LABEL.local : `${PROVIDER_LABEL[name]} · ${rest.join('/')}`) : model
}
