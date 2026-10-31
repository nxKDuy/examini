import api from '../../lib/api'

export type QuestionType = 'MULTIPLE_CHOICE' | 'TRUE_FALSE' | 'SHORT_ANSWER' | 'ESSAY' | 'SPEAKING' | 'MULTIPLE_RESPONSE' | 'TRUE_FALSE_GROUP' | 'FILL_BLANK' | 'MATCHING'
export type MediaKind = 'IMAGE' | 'AUDIO'
export interface MediaRef { id: string; kind: MediaKind; name?: string | null }
export interface Passage { key: string; title?: string | null; content: string; media?: MediaRef[] | null }
/** How a question is shown: attached image/audio, option images, a shared reading passage and per-type limits. */
export interface Presentation {
  media?: MediaRef[] | null
  /** Option displayOrder -> image media id. */
  optionMedia?: Record<string, string> | null
  passage?: Passage | null
  maxPlays?: number | null
  minWords?: number | null
  maxWords?: number | null
  maxRecordSeconds?: number | null
  /** MATCHING in an exam: the right-hand items, alphabetical (added by exam-service, never stored in the bank). */
  matchTargets?: string[] | null
}
export interface UploadedMedia { id: string; kind: MediaKind; contentType: string; size: number; name: string }
export type QuestionStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED'
export type SourceType = 'NONE' | 'USER_DOCUMENT' | 'EXTERNAL_WEB' | 'ORGANIZATION_BANK' | 'PUBLIC_BANK' | 'SYSTEM_BANK'
export interface Subject { id: string; name: string; description: string }
export interface Category { id: string; subjectId: string; subjectName: string; name: string; parentCategoryId: string | null }
export interface QuestionOption { id: string; content: string; correct?: boolean; displayOrder: number }
export interface Question {
  bankId?: string; bankType?: 'PERSONAL' | 'SYSTEM' | 'ORGANIZATION' | 'PUBLIC'; eligible?: boolean; publicationStatus?: string
  id: string; categoryId: string; categoryName: string; content: string
  questionType: QuestionType; difficultyLevel: 'EASY' | 'MEDIUM' | 'HARD'
  source: string | null; createdAt: string; updatedAt: string; createdBy: string; options: QuestionOption[]
  status: QuestionStatus; questionVersion: number; scope: 'PERSONAL' | 'ORGANIZATION' | 'SYSTEM'
  creationMethod: 'MANUAL' | 'IMPORT' | 'AI_GENERATED' | 'COPIED'; sourceType: SourceType
  sourceRefId?: string | null; sourceLabel?: string | null; sourceUrl?: string | null; sourceExcerpt?: string | null
  expectedAnswer?: string | null; explanation?: string | null
  presentation?: Presentation | null
}
export interface QuestionInput {
  categoryId: string; content: string; questionType: QuestionType; difficultyLevel: Question['difficultyLevel']
  options: { content: string; correct: boolean; displayOrder: number }[]; expectedAnswer: string
  sourceType: SourceType; sourceRefId: string; sourceLabel: string; sourceUrl: string; sourceExcerpt: string
  explanation?: string | null
  presentation?: Presentation | null
  questionVersion?: number
}
export interface Page<T> { content: T[]; totalElements: number; totalPages: number; number: number }
export interface Filters {
  categoryId?: string; difficulty?: string; questionType?: string; status?: string
  creationMethod?: string; sourceType?: string; text?: string; page?: number; size?: number
}
export interface Version { versionNumber: number; createdAt: string; changedBy: string; question: Question }
export interface Review { id: string; questionVersion: number; reviewerId: string; decision: 'APPROVED' | 'REJECTED'; reviewNote: string | null; reviewedAt: string }
export interface ImportResult { totalRows: number; imported: number; failed: number; errors: string[] }
export const questionBankApi = {
  getSubjects: () => api.get<Subject[]>('/subjects').then(r => r.data),
  createSubject: (data: { name: string; description?: string }) => api.post<Subject>('/subjects', data).then(r => r.data),
  deleteSubject: (id: string) => api.delete(`/subjects/${id}`),
  createCategory: (data: { subjectId: string; name: string; parentCategoryId?: string }) => api.post<Category>('/categories', data).then(r => r.data),
  getCategory: (id: string) => api.get<Category>(`/categories/${id}`).then(r => r.data),
  getCategories: (subjectId: string) => api.get<Category[]>('/categories', { params: { subjectId } }).then(r => r.data),
  getQuestions: (params: Filters) => api.get<Page<Question>>('/questions', { params: { size: 20, ...params } }).then(r => r.data),
  getQuestion: (id: string, includeAnswers = false) => api.get<Question>(`/questions/${id}`, { params: { includeAnswers } }).then(r => r.data),
  createQuestion: (data: QuestionInput) => api.post<Question>('/questions', data).then(r => r.data),
  updateQuestion: (id: string, data: QuestionInput) => api.put<Question>(`/questions/${id}`, data).then(r => r.data),
  archive: (q: Question) => api.delete(`/questions/${q.id}`, { params: { questionVersion: q.questionVersion } }),
  getVersions: (id: string, includeAnswers = false) => api.get<Version[]>(`/questions/${id}/versions`, { params: { includeAnswers } }).then(r => r.data),
  getReviews: (id: string) => api.get<Review[]>(`/questions/${id}/reviews`).then(r => r.data),
  uploadMedia: (file: Blob, name = 'file') => {
    const form = new FormData(); form.append('file', file, name)
    return api.post<UploadedMedia>('/question-media', form, { headers: { 'Content-Type': 'multipart/form-data' } }).then(r => r.data)
  },
  mediaBlob: (id: string) => api.get<Blob>(`/question-media/${id}`, { responseType: 'blob' }).then(r => r.data),
  passages: () => api.get<Passage[]>('/questions/passages').then(r => r.data),
  importCsv: (file: File, categoryId: string) => {
    const form = new FormData(); form.append('file', file)
    return api.post<ImportResult>('/questions/import/csv', form, {
      params: { categoryId }, headers: { 'Content-Type': 'multipart/form-data' },
    }).then(r => r.data)
  },
}
