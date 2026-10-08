import api from '../../lib/api'

export type GradingStatus = 'PENDING' | 'NEEDS_REVIEW' | 'GRADED'
export type QuestionType = 'MULTIPLE_CHOICE' | 'TRUE_FALSE' | 'SHORT_ANSWER' | 'ESSAY'

/** Contract: manual-grading-api.md (MAIN lock 0d6090e). Scores are the draft/current result; publishedScore is what the Student sees. */
export interface Submission {
  id: string; examId: string; examTitle: string; studentId: string; attemptNumber: number
  status: GradingStatus; score: number | null; totalScore: number | null; publishedScore: number | null
  pendingManualItemCount: number; submittedAt: string | null; gradedAt: string | null; durationSeconds: number | null
  feedback: string | null; resultRevision: number; publishedRevision: number | null
}
export interface GradeItem {
  questionId: string; questionVersion: number; position: number; questionContent: string; questionType: QuestionType
  pointValue: number | null; answerContent: string | null; selectedOptionContent: string | null
  autoScore: number | null; manualScore: number | null; awardedPoints: number | null; needsReview: boolean
  feedback: string | null; manualGradedBy: string | null; manualGradedAt: string | null
}
export interface SubmissionDetail extends Submission { items: GradeItem[] }
export interface Overview { totalSubmissions: number; needsReview: number; pending: number; graded: number; released: number; pendingManualItems: number }
export interface Page<T> { content: T[]; totalElements: number; totalPages: number; number: number; size?: number }

export interface ResultPolicy {
  autoRelease: boolean; showScore: boolean; showQuestionBreakdown: boolean; showFeedback: boolean; showCorrectAnswers: boolean; showExplanation: boolean
}
export interface DraftItem { questionId: string; score: number | null; feedback: string | null; overrideReason: string | null }
export interface DraftRequest { expectedRevision: number; feedback: string | null; items: DraftItem[] }

export const gradingApi = {
  overview: (examId: string) => api.get<Overview>('/submissions/overview', { params: { examId } }).then(r => r.data),
  list: (params: { examId: string; status?: string; released?: boolean; page?: number; size?: number }) =>
    api.get<Page<Submission>>('/submissions', { params: { size: 20, ...params } }).then(r => r.data),
  detail: (id: string) => api.get<SubmissionDetail>(`/submissions/${id}`).then(r => r.data),
  saveDraft: (id: string, body: DraftRequest) => api.put<SubmissionDetail>(`/submissions/${id}/draft`, body).then(r => r.data),
  finalize: (id: string, expectedRevision: number) => api.post<SubmissionDetail>(`/submissions/${id}/finalize`, { expectedRevision }).then(r => r.data),
  release: (id: string, expectedRevision: number) => api.post<SubmissionDetail>(`/submissions/${id}/release`, { expectedRevision }).then(r => r.data),
  regrade: (id: string, body: { expectedRevision: number; publicReason: string; privateAuditNote?: string }) =>
    api.post<SubmissionDetail>(`/submissions/${id}/regrade`, body).then(r => r.data),
  policy: (examId: string) => api.get<ResultPolicy>(`/exams/${examId}/result-policy`).then(r => r.data),
  savePolicy: (examId: string, body: ResultPolicy) => api.put<ResultPolicy>(`/exams/${examId}/result-policy`, body).then(r => r.data),
}
