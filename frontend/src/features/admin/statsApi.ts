import api from '../../lib/api'

/** System statistics for administrators, one call per owning service (all counts, no personal data). */
export interface UserStats { total: number; students: number; teachers: number; admins: number; locked: number; googleLinked: number; activeLast7Days: number; activeLast30Days: number; newInPeriod: number; days: number; newPerDay: { day: string; count: number }[] }
export interface ExamStats { total: number; byStatus: Record<string, number>; byType: Record<string, number>; withCamera: number; createdInPeriod: number; days: number; createdPerDay: { day: string; count: number }[]; titles: Record<string, string> }
export interface QuestionStats {
  total: number; byStatus: Record<string, number>; byType: Record<string, number>; byDifficulty: Record<string, number>; byCreationMethod: Record<string, number>
  withMedia: number; publicPosts: number; importJobs: number
  ai: { jobs: number; jobsFailed: number; draftsGenerated: number; draftsImported: number; byModel: Record<string, number> }
  days: number; perDay: { day: string; created: number; aiGenerated: number }[]
}

export const statsApi = {
  users: (days: number) => api.get<UserStats>('/auth/admin/stats', { params: { days } }).then(r => r.data),
  exams: (days: number, ids: string[] = []) => api.get<ExamStats>('/exams/admin/stats', { params: { days, ...(ids.length ? { ids: ids.join(',') } : {}) } }).then(r => r.data),
  questions: (days: number) => api.get<QuestionStats>('/questions/admin/stats', { params: { days } }).then(r => r.data),
}
