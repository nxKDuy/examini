import type { AnalyticsStatus, ExamSummary, StudentSummary } from './analyticsApi'
import { formatScore } from '../../lib/format'
import { t, i18nMap } from '../../lib/i18n'

export const analyticsStatusLabel: Record<AnalyticsStatus | 'ALL', string> = i18nMap({
  ALL: 'Tất cả trạng thái', PENDING: 'Chờ chấm', NEEDS_REVIEW: 'Cần chấm', GRADED: 'Đã chấm',
})

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
    { key: 'completion', label: t('Tỷ lệ hoàn thành'), value: percent(k?.completionRate), reason: why('completionRate'),
      detail: k ? t('{0} đã nộp hợp lệ / {1} đủ điều kiện', { 0: k.submittedStudents, 1: k.eligibleCount ?? '—' }) : undefined },
    { key: 'published', label: t('Điểm TB đã công bố'), value: k?.publishedAverageScore == null ? null : formatScore(k.publishedAverageScore), reason: why('publishedAverageScore') },
    { key: 'review', label: t('Bài cần chấm'), value: k ? String(k.needsGradingCount) : null, reason: why('needsGradingCount') },
  ]
}

export function attemptMetrics(s: ExamSummary): Kpi[] {
  return [
    { key: 'submissions', label: t('Lượt nộp hợp lệ'), value: String(s.submissions) },
    { key: 'graded', label: t('Lượt đã chấm'), value: String(s.graded) },
    { key: 'gradingRate', label: t('Tỷ lệ đã chấm xong'), value: percent(s.gradingCompletionRate), reason: t('Chưa có lượt nộp nào.') },
    { key: 'invalidated', label: t('Lượt bị vô hiệu hóa'), value: String(s.invalidatedCount) },
    { key: 'started', label: t('Lượt bắt đầu / hoàn thành phiên'), value: null, reason: t('Chưa có sự kiện bắt đầu và kết thúc phiên làm bài.') },
  ]
}

export const STUDENT_FILTERS = ['', 'PENDING', 'NEEDS_REVIEW', 'GRADED'] as const
export function filterStudents(rows: StudentSummary[], status: AnalyticsStatus | '') {
  return status ? rows.filter(r => r.latestGradingStatus === status) : rows
}
