import api from '../../lib/api'

export interface Subject { id: string; name: string; description: string }
export interface Category { id: string; subjectId: string; subjectName: string; name: string; parentCategoryId: string | null }
export interface QuestionOption { id: string; content: string; correct: boolean; displayOrder: number }
export interface Question {
  id: string; categoryId: string; categoryName: string; content: string;
  questionType: 'MULTIPLE_CHOICE' | 'TRUE_FALSE' | 'SHORT_ANSWER' | 'ESSAY'
  difficultyLevel: 'EASY' | 'MEDIUM' | 'HARD'
  source: string; createdAt: string; options: QuestionOption[]
}
export interface Page<T> { content: T[]; totalElements: number; totalPages: number; number: number }

export const questionBankApi = {
  // Subjects
  getSubjects: () => api.get<Subject[]>('/subjects').then(r => r.data),
  createSubject: (data: { name: string; description?: string }) =>
    api.post<Subject>('/subjects', data).then(r => r.data),
  deleteSubject: (id: string) => api.delete(`/subjects/${id}`),

  // Categories
  getCategories: (subjectId: string) =>
    api.get<Category[]>('/categories', { params: { subjectId } }).then(r => r.data),
  createCategory: (data: { subjectId: string; name: string; parentCategoryId?: string }) =>
    api.post<Category>('/categories', data).then(r => r.data),

  // Questions
  getQuestions: (params: { categoryId?: string; difficulty?: string; page?: number; size?: number }) =>
    api.get<Page<Question>>('/questions', { params: { size: 20, ...params } }).then(r => r.data),
  createQuestion: (data: object) => api.post<Question>('/questions', data).then(r => r.data),
  updateQuestion: (id: string, data: object) => api.put<Question>(`/questions/${id}`, data).then(r => r.data),
  deleteQuestion: (id: string) => api.delete(`/questions/${id}`),
  importCsv: (file: File, categoryId: string) => {
    const form = new FormData()
    form.append('file', file)
    return api.post('/questions/import/csv', form, {
      params: { categoryId },
      headers: { 'Content-Type': 'multipart/form-data' },
    }).then(r => r.data)
  },
}
