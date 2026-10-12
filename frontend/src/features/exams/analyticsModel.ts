import type { AnalyticsStatus, ExamSummary, StudentSummary } from './analyticsApi'

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

/** Four headline KPIs in the locked order; null fields carry a reason instead of a number. */
export function headlineKpis(s: ExamSummary): Kpi[] {
  return [
    { key: 'students', label: 'Student tham gia', value: String(s.candidates), detail: s.eligibleCount === null ? undefined : `/ ${s.eligibleCount} đủ điều kiện` },
    { key: 'completion', label: 'Tỷ lệ hoàn thành', value: percent(s.submissionRate),
      reason: s.eligibleCount === null ? 'Chưa nhận danh sách Student đủ điều kiện của kỳ thi.' : 'Không có Student đủ điều kiện để tính tỷ lệ.',
      detail: 'đã nộp bài hợp lệ / đủ điều kiện' },
    // Published average needs a Grading publication snapshot event the API does not expose yet.
    { key: 'published', label: 'Điểm TB đã công bố', value: null, reason: 'Analytics chưa nhận điểm đã công bố của từng Student; điểm chấm hiện tại chưa phải điểm công bố.' },
    { key: 'review', label: 'Bài cần chấm', value: String(s.needsReview), detail: s.pending > 0 ? `+ ${s.pending} chờ xử lý` : undefined },
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
