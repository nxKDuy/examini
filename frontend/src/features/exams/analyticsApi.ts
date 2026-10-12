import api from '../../lib/api'

export interface ScoreBucket { fromPercent: number; toPercent: number; count: number }
export interface ExamSummary {
  examId: string; candidates: number; submissions: number; graded: number; needsReview: number; pending: number
  maxPossibleScore: number | null; averageScore: number | null; minScore: number | null; maxScore: number | null
  averagePercent: number | null; scoreDistribution: ScoreBucket[]; registeredCount: number | null; eligibleCount: number | null
  invalidatedCount: number; submissionRate: number | null; gradingCompletionRate: number | null
  kpi?: SummaryKpi
}
/** Header KPI block; a null value means the source data is missing (reason in `unavailable`), never 0. */
export interface SummaryKpi {
  participatedStudents: number | null; submittedStudents: number; registeredCount: number | null; eligibleCount: number | null
  completionRate: number | null; completionRateDenominator: string | null; publishedAverageScore: number | null
  needsGradingCount: number; unavailable: Record<string, string>
}
export interface QuestionStats {
  questionId: string; questionVersion: number; questionType: string; position: number; pointValue: number
  attempts: number; answered: number; correct: number; partial: number; incorrect: number; unanswered: number; pendingReview: number
  correctRate: number | null; averageAwardedPoints: number | null
}
export interface CandidateResult {
  sessionId: string; resultId: string; candidateId: string; attemptNumber: number | null; status: string
  score: number | null; maxScore: number; submittedAt: string | null; gradedAt: string | null; invalidated: boolean
}
export interface StudentSummary {
  candidateId: string; submittedAttempts: number; gradedAttempts: number; needsReviewAttempts: number; pendingAttempts: number
  latestSessionId: string; latestGradingStatus: string; latestSubmittedAt: string | null
}
export interface AnalyticsPage<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number }
export type AnalyticsStatus = 'PENDING' | 'NEEDS_REVIEW' | 'GRADED'

export const analyticsApi = {
  summary: (examId: string) => api.get<ExamSummary>(`/analytics/exams/${examId}/summary`).then(r => r.data),
  questions: (examId: string) => api.get<QuestionStats[]>(`/analytics/exams/${examId}/questions`).then(r => r.data),
  results: (examId: string, candidateId: string, page = 0, size = 50) => api.get<AnalyticsPage<CandidateResult>>(
    `/analytics/exams/${examId}/results`, { params: { candidateId, page, size } },
  ).then(r => r.data),
  students: (examId: string, page: number, size = 20) => api.get<AnalyticsPage<StudentSummary>>(
    `/analytics/exams/${examId}/students`, { params: { page, size } },
  ).then(r => r.data),
}
