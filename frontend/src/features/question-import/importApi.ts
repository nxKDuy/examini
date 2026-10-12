import api from '../../lib/api'
import type { QuestionType } from '../question-bank/questionApi'

// contracts/question-import.md (LOCKED). Items are the author's own draft, so they include answer keys.
export type JobStatus = 'PROCESSING' | 'READY' | 'FAILED' | 'DISCARDED'
export type ItemFilter = 'ALL' | 'NEEDS_REVIEW' | 'INVALID' | 'IMPORTABLE' | 'IMPORTED' | 'EXCLUDED'
export type Difficulty = 'EASY' | 'MEDIUM' | 'HARD'
export interface ImportSummary { total: number; valid: number; invalid: number; needsReview: number; importable: number; imported: number; excluded: number }
export interface ImportJob {
  id: string; fileName: string; fileKind: 'DOCX' | 'PDF' | 'IMAGE'; fileSize: number; pageCount: number; ocrPageCount: number
  status: JobStatus; failureReason?: string | null; warnings: string[]; categoryId?: string | null; defaultDifficulty: Difficulty
  createdAt: string; updatedAt: string; summary: ImportSummary
}
export interface ImportOption { content: string; correct: boolean; displayOrder: number }
export interface ImportItem {
  id: string; position: number; version: number; content: string; questionType: QuestionType; difficultyLevel: Difficulty; categoryId?: string | null
  options: ImportOption[]; expectedAnswer?: string | null; explanation?: string | null
  source: { fileName: string; page: number | null; method: 'TEXT' | 'OCR' }; confidence: number; rawText: string
  flags: string[]; reviewRequired: boolean; reviewed: boolean; excluded: boolean; valid: boolean; errors: { code: string; message: string }[]
  importable: boolean; importedQuestionId?: string | null; importedAt?: string | null
}
export interface ItemPatch {
  version: number; content?: string; questionType?: QuestionType; difficultyLevel?: Difficulty; categoryId?: string
  options?: ImportOption[]; expectedAnswer?: string; explanation?: string; reviewed?: boolean; excluded?: boolean
}
export interface ImportResult { imported: { itemId: string; questionId: string }[]; skipped: { itemId: string; reason: string }[]; summary: ImportSummary }
interface Page<T> { content: T[]; totalElements: number; totalPages: number; number: number }

export const importApi = {
  upload: (file: File, opts: { categoryId?: string; defaultDifficulty?: Difficulty }) => {
    const form = new FormData()
    form.append('file', file)
    if (opts.categoryId) form.append('categoryId', opts.categoryId)
    if (opts.defaultDifficulty) form.append('defaultDifficulty', opts.defaultDifficulty)
    return api.post<ImportJob>('/question-imports', form).then(r => r.data)
  },
  jobs: (page = 0, size = 20) => api.get<Page<ImportJob>>('/question-imports', { params: { page, size } }).then(r => r.data),
  job: (id: string) => api.get<ImportJob>(`/question-imports/${id}`).then(r => r.data),
  items: (id: string, filter: ItemFilter, page = 0, size = 50) => api.get<Page<ImportItem>>(`/question-imports/${id}/items`, { params: { filter, page, size } }).then(r => r.data),
  update: (id: string, itemId: string, patch: ItemPatch) => api.put<ImportItem>(`/question-imports/${id}/items/${itemId}`, patch).then(r => r.data),
  validate: (id: string) => api.post<ImportSummary>(`/question-imports/${id}/validate`).then(r => r.data),
  importItems: (id: string, itemIds?: string[]) => api.post<ImportResult>(`/question-imports/${id}/import`, itemIds ? { itemIds } : {}).then(r => r.data),
  discard: (id: string) => api.delete(`/question-imports/${id}`).then(() => undefined),
}
