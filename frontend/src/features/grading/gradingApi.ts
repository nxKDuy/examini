import api from '../../lib/api'

export type GradingStatus = 'PENDING' | 'NEEDS_REVIEW' | 'GRADED'

/** Grading SummaryResponse. studentName/studentCode currently carry the user ID (no profile lookup in Phase 1). */
export interface Submission {
  id: string; examId: string; examTitle: string; studentId: string; studentName: string; studentCode: string
  status: GradingStatus; score: number | null; totalScore: number | null; submittedAt: string | null
  durationSeconds: number | null; feedback: string | null
}
export interface SubmissionAnswer {
  questionId: string; questionContent: string; questionType: 'MULTIPLE_CHOICE' | 'TRUE_FALSE' | 'SHORT_ANSWER' | 'ESSAY'
  pointValue: number | null; selectedOptionId: string | null; selectedOptionContent: string | null; textAnswer: string | null
  isCorrect: boolean | null; earnedPoints: number | null
}
export interface SubmissionDetail extends Submission { answers: SubmissionAnswer[] }
export interface Page<T> { content: T[]; totalElements: number; totalPages: number; number: number }

export const gradingApi = {
  getSubmissions: (params: { examId: string; status?: string; page?: number; size?: number }) =>
    api.get<Page<Submission>>('/submissions', { params: { size: 30, ...params } }).then(r => r.data),
  getSubmissionDetail: (id: string) => api.get<SubmissionDetail>(`/submissions/${id}`).then(r => r.data),
  gradeSubmission: (id: string, data: { score: number; feedback?: string }) =>
    api.put<Submission>(`/submissions/${id}/grade`, data).then(r => r.data),
}
