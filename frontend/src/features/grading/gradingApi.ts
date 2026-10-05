import api from '../../lib/api'

export interface Submission {
  id: string
  examId: string
  examTitle: string
  studentId: string
  studentName: string
  studentCode: string
  status: 'PENDING' | 'NEEDS_REVIEW' | 'GRADED'
  score: number | null
  totalScore: number
  submittedAt: string
  durationSeconds: number
  feedback?: string
}

export interface SubmissionDetail extends Submission {
  answers: SubmissionAnswer[]
}

export interface SubmissionAnswer {
  questionId: string
  questionContent: string
  questionType: 'MULTIPLE_CHOICE' | 'TRUE_FALSE' | 'SHORT_ANSWER' | 'ESSAY'
  pointValue: number
  selectedOptionId?: string
  selectedOptionContent?: string
  textAnswer?: string
  isCorrect?: boolean
  earnedPoints: number
}

export interface Page<T> { content: T[]; totalElements: number; totalPages: number; number: number }

export const gradingApi = {
  getSubmissions: (params: { examId?: string; status?: string; page?: number; size?: number }) =>
    api.get<Page<Submission>>('/submissions', { params: { size: 30, ...params } }).then(r => r.data),

  getSubmissionDetail: (id: string) =>
    api.get<SubmissionDetail>(`/submissions/${id}`).then(r => r.data),

  gradeSubmission: (id: string, data: { score: number; feedback?: string }) =>
    api.put<Submission>(`/submissions/${id}/grade`, data).then(r => r.data),
}
