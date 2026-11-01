import api from '../../lib/api'

/** The signed-in student's own results (grading-service, filtered by each exam's result policy). */
export interface ProgressItem {
  sessionId: string; examId: string; title: string; attemptNumber: number
  phase: 'PUBLISHED' | 'REVIEWING' | 'WAITING' | 'UNRELEASED'; submittedAt: string | null; releasedAt: string | null
  score: number | null; maxScore: number | null; percent: number | null; invalidated: boolean
}
export interface TypeStat { questionType: string; questions: number; earned: number; possible: number; percent: number }
export interface Progress {
  overview: { exams: number; attempts: number; published: number; averagePercent: number | null; bestPercent: number | null; latestPercent: number | null; trend: number | null; byType: TypeStat[] }
  items: ProgressItem[]
}
export interface StudyInsights { source: 'RULES' | 'AI'; summary: string; tips: { kind: 'STRENGTH' | 'FOCUS' | 'HABIT'; text: string }[]; aiAvailable: boolean }

export const progressApi = {
  mine: () => api.get<Progress>('/grades/my').then(r => r.data),
  insights: (ai: boolean, lang: string) => api.get<StudyInsights>('/grades/my/insights', { params: { ai, lang }, timeout: 60_000 }).then(r => r.data),
}
