import api from '../../lib/api'

export interface Exam {
  id: string
  code: string
  title: string
  description: string
  type: 'PRACTICE' | 'OFFICIAL'
  durationMinutes: number
  subjectId: string
  categoryId: string
  totalScore: number
  passingScore: number
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
  createdAt: string
  
  // Fake stats for UI
  studentCount?: number
  avgScore?: number
}

export interface Page<T> { content: T[]; totalElements: number; totalPages: number; number: number }

export interface CreateExamPayload {
  title: string
  description?: string
  type: 'PRACTICE' | 'OFFICIAL'
  durationMinutes: number
  passingScore: number
  shuffleQuestions: boolean
  shuffleOptions: boolean
  requireCam: boolean
  requireMic: boolean
  blockTabSwitch: boolean
  requireFullscreen: boolean
  questions: { questionId: string; weight: number }[]
}

export const examApi = {
  getExams: (params: { page?: number; size?: number; status?: string }) =>
    api.get<Page<Exam>>('/exams', { params: { size: 12, ...params } }).then(r => r.data),
    
  createExam: (data: CreateExamPayload) => api.post<Exam>('/exams', data).then(r => r.data),
  updateExam: (id: string, data: Partial<CreateExamPayload>) => api.put<Exam>(`/exams/${id}`, data).then(r => r.data),
  deleteExam: (id: string) => api.delete(`/exams/${id}`),
  publishExam: (id: string) => api.post(`/exams/${id}/publish`),
}
