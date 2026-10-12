import type { AnalyticsStatus, ExamSummary, StudentSummary } from './analyticsApi'
import { formatScore } from '../../lib/format'

export const analyticsStatusLabel: Record<AnalyticsStatus | 'ALL', string> = {
  ALL: 'Tất cả trạng thái', PENDING: 'Chờ chấm', NEEDS_REVIEW: 'Cần chấm', GRADED: 'Đã chấm',
}

export const NO_DATA = 'Chưa có dữ liệu'

export function resultPageCount(totalElements: number, size: number) {
  return Math.max(1, Math.ceil(Math.max(0, totalElements) / size))
}

export function percent(rate: number | null | undefined) {
  return rate === null || rate === undefined ? null : `${(rate * 100).toFixed(1)}%`
}

export interface Kpi { key: string; label: string; value: string | null; reason?: string; detail?: string }

const NO_KPI = 'Analytics chưa trả khối chỉ số chính cho kỳ thi này.'

/** Four headline KPIs in the locked order, read only from `summary.kpi`; null fields carry the server reason, never 0. */
export function headlineKpis(s: ExamSummary): Kpi[] {
  const k = s.kpi
  const why = (field: string) => k?.unavailable?.[field] ?? (k ? NO_DATA : NO_KPI)
  return [
    { key: 'students', label: 'Student tham gia', value: k?.participatedStudents == null ? null : String(k.participatedStudents), reason: why('participatedStudents') },
    { key: 'completion', label: 'Tỷ lệ hoàn thành', value: percent(k?.completionRate), reason: why('completionRate'),
      detail: k ? `${k.submittedStudents} đã nộp hợp lệ / ${k.eligibleCount ?? '—'} đủ điều kiện` : undefined },
    { key: 'published', label: 'Điểm TB đã công bố', value: k?.publishedAverageScore == null ? null : formatScore(k.publishedAverageScore), reason: why('publishedAverageScore') },
    { key: 'review', label: 'Bài cần chấm', value: k ? String(k.needsGradingCount) : null, reason: why('needsGradingCount') },
  ]
}

export function attemptMetrics(s: ExamSummary): Kpi[] {
  return [
    { key: 'submissions', label: 'Lượt nộp hợp lệ', value: String(s.submissions) },
    { key: 'graded', label: 'Lượt đã chấm', value: String(s.graded) },
    { key: 'gradingRate', label: 'Tỷ lệ đã chấm xong', value: percent(s.gradingCompletionRate), reason: 'Chưa có lượt nộp nào.' },
    { key: 'invalidated', label: 'Lượt bị vô hiệu hóa', value: String(s.invalidatedCount) },
    { key: 'started', label: 'Lượt bắt đầu / hoàn thành phiên', value: null, reason: 'Chưa có sự kiện bắt đầu và kết thúc phiên làm bài.' },
  ]
}

export const STUDENT_FILTERS = ['', 'PENDING', 'NEEDS_REVIEW', 'GRADED'] as const
export function filterStudents(rows: StudentSummary[], status: AnalyticsStatus | '') {
  return status ? rows.filter(r => r.latestGradingStatus === status) : rows
}
