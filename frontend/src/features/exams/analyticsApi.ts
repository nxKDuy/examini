import api from '../../lib/api'

export interface ScoreBucket { fromPercent: number; toPercent: number; count: number }
export interface ExamSummary {
  examId: string; candidates: number; submissions: number; graded: number; needsReview: number; pending: number
  maxPossibleScore: number | null; averageScore: number | null; minScore: number | null; maxScore: number | null
  averagePercent: number | null; scoreDistribution: ScoreBucket[]; registeredCount: number | null; eligibleCount: number | null
  invalidatedCount: number; submissionRate: number | null; gradingCompletionRate: number | null
  kpi?: SummaryKpi
  /** Latest released valid attempt per Student; `distribution` is in percent buckets. */
  published?: PublishedStats
}
export interface PublishedStats {
  students: number; averageScore: number | null; highScore: number | null; lowScore: number | null
  averagePercent: number | null; highPercent: number | null; lowPercent: number | null; distribution: ScoreBucket[]
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
  /** Upper-lower 27 % discrimination index, -1..1; null with fewer than 10 graded attempts. */
  discrimination?: number | null
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

export type InsightIssue = 'TOO_HARD' | 'TOO_EASY' | 'OFTEN_SKIPPED' | 'NEGATIVE_DISCRIMINATION' | 'LOW_DISCRIMINATION'
export interface QuestionFlag { position: number; questionType: string; issue: InsightIssue | string; detail: string }
export interface ExamInsights {
  examId: string; source: 'RULES' | 'AI'; model: string | null; level: 'GOOD' | 'FAIR' | 'AVERAGE' | 'WEAK' | 'UNKNOWN'
  summary: string; strengths: string[]; concerns: string[]; actions: string[]; flags: QuestionFlag[]; aiAvailable: boolean; generatedAt: string
}
export interface AdminOverview {
  days: number; attempts: number; submissions: number; graded: number; needsReview: number; activeExams: number; activeStudents: number
  invalidated: number; publishedAveragePercent: number | null; publishedResults: number
  perDay: { day: string; attempts: number; submissions: number }[]
  topExams: { examId: string; attempts: number; students: number; averagePercent: number | null }[]
  publishedDistribution: { fromPercent: number; toPercent: number; count: number }[]
}

export const analyticsApi = {
  summary: (examId: string) => api.get<ExamSummary>(`/analytics/exams/${examId}/summary`).then(r => r.data),
  questions: (examId: string) => api.get<QuestionStats[]>(`/analytics/exams/${examId}/questions`).then(r => r.data),
  results: (examId: string, candidateId: string, page = 0, size = 50) => api.get<AnalyticsPage<CandidateResult>>(
    `/analytics/exams/${examId}/results`, { params: { candidateId, page, size } },
  ).then(r => r.data),
  allResults: (examId: string, page: number, size = 100) => api.get<AnalyticsPage<CandidateResult>>(
    `/analytics/exams/${examId}/results`, { params: { page, size } },
  ).then(r => r.data),
  insights: (examId: string, ai: boolean, lang: string) => api.get<ExamInsights>(`/analytics/exams/${examId}/insights`, { params: { ai, lang }, timeout: 60_000 }).then(r => r.data),
  adminOverview: (days = 30) => api.get<AdminOverview>('/analytics/admin/overview', { params: { days } }).then(r => r.data),
  students: (examId: string, page: number, size = 20) => api.get<AnalyticsPage<StudentSummary>>(
    `/analytics/exams/${examId}/students`, { params: { page, size } },
  ).then(r => r.data),
}

/** Every attempt of the exam (all pages of /results). */
export async function allResults(examId: string): Promise<CandidateResult[]> {
  const out: CandidateResult[] = []
  for (let page = 0; page < 200; page++) {
    const r = await analyticsApi.allResults(examId, page, 100)
    out.push(...r.content)
    if (page + 1 >= r.totalPages) break
  }
  return out
}
