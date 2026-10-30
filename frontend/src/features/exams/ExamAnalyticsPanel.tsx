import { Fragment, useState } from 'react'
import { Download } from 'lucide-react'
import { useParticipantNames } from '../participants/useParticipantNames'
import { PersonCell } from '../participants/PersonCell'
import { download, fileSlug, scoreCsv } from './scoreExport'
import { allResults } from './analyticsApi'
import { difficultyOf, discriminationOf, type Difficulty, type Discrimination } from './analyticsModel'
import ExamInsightsCard from './ExamInsightsCard'
import type { PublishedStats } from './analyticsApi'
import { errorText } from '../../lib/errors'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { analyticsApi, type AnalyticsStatus } from './analyticsApi'
import { NO_DATA, STUDENT_FILTERS, analyticsStatusLabel, attemptMetrics, filterStudents, headlineKpis, resultPageCount, type Kpi } from './analyticsModel'
import { Badge, EmptyState, ErrorState, LoadingState, Section } from '../../components/ui'
import { QUESTION_TYPE_LABEL, formatDateTime, formatScore, shortId } from '../../lib/format'
import './analytics.css'
import { t, useT } from '../../lib/i18n'
import Select from '../../components/Select'

const PAGE_SIZE = 20

export default function ExamAnalyticsPanel({ examId, actorId, examTitle = '' }: { examId: string; actorId: string; examTitle?: string }) {
  useT()
  const [status, setStatus] = useState<AnalyticsStatus | ''>('')
  const [page, setPage] = useState(0)
  const [open, setOpen] = useState<string | null>(null)
  const on = Boolean(actorId)
  const summary = useQuery({ queryKey: ['analytics', actorId, examId, 'summary'], queryFn: () => analyticsApi.summary(examId), enabled: on, retry: false })
  const questions = useQuery({ queryKey: ['analytics', actorId, examId, 'questions'], queryFn: () => analyticsApi.questions(examId), enabled: on, retry: false })
  const students = useQuery({ queryKey: ['analytics', actorId, examId, 'students', page], queryFn: () => analyticsApi.students(examId, page, PAGE_SIZE), enabled: on, retry: false })
  const names = useParticipantNames(on ? examId : undefined)
  const [exporting, setExporting] = useState(false)
  const [exportError, setExportError] = useState('')
  async function exportScores() {
    setExporting(true); setExportError('')
    try { download(`bang-diem-${fileSlug(examTitle)}.csv`, scoreCsv(await allResults(examId), names.data)) }
    catch (e) { setExportError(errorText(e)) }
    finally { setExporting(false) }
  }
  const attempts = useQuery({ queryKey: ['analytics', actorId, examId, 'results', open], queryFn: () => analyticsApi.results(examId, open!), enabled: on && Boolean(open), retry: false })

  if (!on) return <EmptyState title={t('Không xác định được tài khoản')} description={t('Đăng nhập lại để tải phân tích của kỳ thi.')} />
  const s = summary.data
  const rows = filterStudents(students.data?.content ?? [], status)

  return <div className="stack-lg analytics-panel">
    <ExamInsightsCard examId={examId} actorId={actorId} />
    <Section title={t('Tổng quan kết quả')} description={t('Số liệu từ Analytics; ô chưa có dữ liệu nêu rõ lý do thay vì hiện số ước đoán.')}>
      {summary.isLoading ? <LoadingState rows={2} /> : summary.isError ? <ErrorState error={summary.error} onRetry={() => void summary.refetch()} /> : s ? <>
        <div className="analytics-kpis" aria-label={t('Chỉ số chính')}>{headlineKpis(s).map(k => <Metric key={k.key} kpi={k} />)}</div>
        <h3 className="analytics-sub">{t('Lượt làm bài')}</h3>
        <div className="analytics-kpis analytics-kpis-sm" aria-label={t('Chỉ số lượt làm bài')}>{attemptMetrics(s).map(k => <Metric key={k.key} kpi={k} />)}</div>
        <h3 className="analytics-sub">{t('Phổ điểm')}</h3>
        <ScoreChart published={s.published} />
      </> : <EmptyState title={t('Chưa có dữ liệu tổng hợp')} description={t('Analytics chưa nhận được kết quả cho kỳ thi này.')} />}
    </Section>

    <Section title={t('Hiệu quả câu hỏi')} description={t('Theo câu hỏi và phiên bản đã chụp trong đề, không truy vấn ngân hàng câu hỏi.')}>
      {questions.isLoading ? <LoadingState rows={3} /> : questions.isError ? <ErrorState error={questions.error} onRetry={() => void questions.refetch()} /> : !questions.data?.length ? <EmptyState title={t('Chưa có thống kê câu hỏi')} description={t('Số liệu xuất hiện sau khi Analytics nhận kết quả.')} /> : <div className="table-wrap"><table className="table analytics-table">
        <thead><tr><th scope="col">{t('Câu')}</th><th scope="col">{t('Loại')}</th><th scope="col">{t('Lượt')}</th><th scope="col">{t('Đúng')}</th><th scope="col">{t('Một phần')}</th><th scope="col">{t('Sai')}</th><th scope="col">{t('Bỏ trống')}</th><th scope="col">{t('Chờ chấm')}</th><th scope="col">{t('Tỷ lệ đúng')}</th><th scope="col">{t('Độ khó')}</th><th scope="col" title={t('Chênh lệch tỷ lệ làm đúng giữa 27% thí sinh điểm cao nhất và 27% thấp nhất. Từ 0,3 là tốt; dưới 0,2 phân loại kém; âm thường do sai đáp án.')}>{t('Độ phân biệt')}</th><th scope="col">{t('Điểm TB')}</th></tr></thead>
        <tbody>{questions.data.map(q => <tr key={`${q.questionId}:${q.questionVersion}`}>
          <td><strong>{t('Câu')} {q.position + 1}</strong><div className="small muted">v{q.questionVersion} · {shortId(q.questionId)}</div></td><td>{questionType(q.questionType)}</td><td>{q.attempts}</td><td>{q.correct}</td><td>{q.partial}</td><td>{q.incorrect}</td><td>{q.unanswered}</td><td>{q.pendingReview}</td>
          <td>{q.correctRate === null ? t(NO_DATA) : `${(q.correctRate * 100).toFixed(1)}%`}</td><td><DifficultyBadge value={difficultyOf(q.correctRate, q.attempts - q.pendingReview)} /></td><td><DiscriminationBadge value={q.discrimination} /></td><td>{q.averageAwardedPoints === null ? t(NO_DATA) : `${formatScore(q.averageAwardedPoints)} / ${formatScore(q.pointValue)}`}</td>
        </tr>)}</tbody>
      </table></div>}
    </Section>

    <Section title={t('Kết quả thí sinh')} description={t('Mỗi thí sinh một dòng (chỉ lượt hợp lệ); mở dòng để xem từng lượt. Thí sinh không có trong danh sách dự thi hiện bằng mã rút gọn.')}
      actions={<button type="button" className="btn btn-secondary btn-sm" disabled={exporting || !students.data?.totalElements} aria-busy={exporting} onClick={() => void exportScores()}>
        <Download size={14} aria-hidden /> {exporting ? t('Đang xuất…') : t('Xuất bảng điểm (Excel)')}</button>}>
      {exportError && <p className="field-error">{exportError}</p>}
      <div className="analytics-result-filters"><label htmlFor="analytics-status">{t('Trạng thái mới nhất')}</label><Select id="analytics-status" className="input" value={status} onChange={e => setStatus(e.target.value as AnalyticsStatus | '')}>
        {STUDENT_FILTERS.map(v => <option key={v || 'ALL'} value={v}>{analyticsStatusLabel[v || 'ALL']}</option>)}
      </Select>{students.data && <span className="small muted">{t('{0} thí sinh · bộ lọc áp dụng cho trang đang xem', { 0: students.data.totalElements })}</span>}</div>
      {students.isLoading ? <LoadingState rows={4} /> : students.isError ? <ErrorState error={students.error} onRetry={() => void students.refetch()} /> : !rows.length ? <EmptyState title={t('Không có thí sinh phù hợp')} description={t('Thử trạng thái khác hoặc quay lại khi có bài nộp.')} /> : <>
        <div className="table-wrap"><table className="table analytics-table"><thead><tr><th scope="col">{t('Thí sinh')}</th><th scope="col">{t('Đã nộp')}</th><th scope="col">{t('Đã chấm')}</th><th scope="col">{t('Cần chấm')}</th><th scope="col">{t('Chờ')}</th><th scope="col">{t('Mới nhất')}</th><th scope="col">{t('Nộp lúc')}</th><th scope="col">{t('Chi tiết')}</th></tr></thead>
          <tbody>{rows.map(r => <Fragment key={r.candidateId}>
            <tr><td><PersonCell names={names.data} userId={r.candidateId} /></td><td>{r.submittedAttempts}</td><td>{r.gradedAttempts}</td><td>{r.needsReviewAttempts}</td><td>{r.pendingAttempts}</td>
              <td>{analyticsStatusLabel[r.latestGradingStatus as AnalyticsStatus] ?? r.latestGradingStatus}</td><td>{formatDateTime(r.latestSubmittedAt, '—')}</td>
              <td><button type="button" className="btn btn-secondary btn-sm" aria-expanded={open === r.candidateId} onClick={() => setOpen(open === r.candidateId ? null : r.candidateId)}>{open === r.candidateId ? t('Ẩn') : t('Xem lượt')}</button></td></tr>
            {open === r.candidateId && <tr className="analytics-drill"><td colSpan={8}>
              {attempts.isLoading ? <LoadingState rows={1} /> : attempts.isError ? <ErrorState error={attempts.error} onRetry={() => void attempts.refetch()} /> : !attempts.data?.content.length ? <p className="small muted">{t('Không có lượt nào.')}</p> : <table className="table"><thead><tr><th scope="col">{t('Lượt')}</th><th scope="col">{t('Trạng thái')}</th><th scope="col">{t('Điểm')}</th><th scope="col">{t('Nộp lúc')}</th><th scope="col">{t('Chấm lúc')}</th><th scope="col">{t('Mở')}</th></tr></thead>
                <tbody>{attempts.data.content.map(a => <tr key={a.sessionId} className={a.invalidated ? 'analytics-invalidated' : undefined}>
                  <td>{a.attemptNumber ?? '—'}{a.invalidated && <> <Badge tone="warning">{t('Vô hiệu hóa')}</Badge></>}</td><td>{analyticsStatusLabel[a.status as AnalyticsStatus] ?? a.status}</td>
                  <td>{a.score === null ? t('Chưa có điểm') : `${formatScore(a.score)} / ${formatScore(a.maxScore)}`}</td><td>{formatDateTime(a.submittedAt, '—')}</td><td>{formatDateTime(a.gradedAt, '—')}</td>
                  <td>{a.invalidated ? '—' : <Link to={`/grading?examId=${encodeURIComponent(examId)}&r=${encodeURIComponent(a.resultId)}`}>{t('Mở chấm bài')}</Link>}</td></tr>)}</tbody></table>}
            </td></tr>}
          </Fragment>)}</tbody></table></div>
        <nav className="analytics-pagination" aria-label={t('Phân trang thí sinh')}><button className="btn btn-secondary btn-sm" type="button" disabled={page === 0 || students.isFetching} onClick={() => { setPage(p => Math.max(0, p - 1)); setOpen(null) }}>{t('Trước')}</button><span className="small">{t('Trang {0} / {1}', { 0: page + 1, 1: resultPageCount(students.data?.totalElements ?? 0, PAGE_SIZE) })}</span><button className="btn btn-secondary btn-sm" type="button" disabled={page + 1 >= (students.data?.totalPages ?? 1) || students.isFetching} onClick={() => { setPage(p => p + 1); setOpen(null) }}>{t('Sau')}</button></nav>
      </>}
    </Section>
  </div>
}

/** Bar chart of the latest published score per student, 10 percent buckets. */
function ScoreChart({ published }: { published?: PublishedStats }) {
  useT()
  if (!published || !published.students) return <p className="analytics-nodata"><strong>{t(NO_DATA)}.</strong> {t('Phổ điểm vẽ theo điểm đã công bố mới nhất của mỗi thí sinh; chưa có kết quả nào được công bố.')}</p>
  const max = Math.max(1, ...published.distribution.map(b => b.count))
  const pct = (n: number | null) => n === null ? '–' : `${Math.round(n * 10) / 10}%`
  return <figure className="score-chart" aria-label={t('Phổ điểm')}>
    <div className="score-bars" role="list">{published.distribution.map(b => <div key={b.fromPercent} className="score-bar" role="listitem"
      aria-label={t('{0}–{1}%: {2} thí sinh', { 0: b.fromPercent, 1: b.toPercent, 2: b.count })}>
      <span className="score-bar-count">{b.count || ''}</span>
      <span className="score-bar-fill" style={{ height: `${(b.count / max) * 100}%` }} />
      <span className="score-bar-label">{b.fromPercent}</span>
    </div>)}</div>
    <figcaption className="small muted">{t('{0} thí sinh đã công bố · trung bình {1} · cao nhất {2} · thấp nhất {3} (theo % điểm tối đa)',
      { 0: published.students, 1: pct(published.averagePercent), 2: pct(published.highPercent), 3: pct(published.lowPercent) })}</figcaption>
  </figure>
}

const DIFFICULTY: Record<Difficulty, { label: () => string; tone: 'success' | 'warning' | 'danger' | 'muted' }> = {
  EASY: { label: () => t('Dễ'), tone: 'success' }, MEDIUM: { label: () => t('Trung bình'), tone: 'warning' },
  HARD: { label: () => t('Khó'), tone: 'danger' }, UNKNOWN: { label: () => t('Chưa đủ dữ liệu'), tone: 'muted' },
}
function DifficultyBadge({ value }: { value: Difficulty }) { return <Badge tone={DIFFICULTY[value].tone}>{DIFFICULTY[value].label()}</Badge> }

const DISCRIMINATION: Record<Discrimination, { label: () => string; tone: 'success' | 'warning' | 'danger' | 'muted' | 'info' }> = {
  GOOD: { label: () => t('Tốt'), tone: 'success' }, FAIR: { label: () => t('Khá'), tone: 'info' }, POOR: { label: () => t('Kém'), tone: 'warning' },
  NEGATIVE: { label: () => t('Âm – xem lại đáp án'), tone: 'danger' }, UNKNOWN: { label: () => t('Chưa đủ 10 bài'), tone: 'muted' },
}
function DiscriminationBadge({ value }: { value?: number | null }) {
  const k = discriminationOf(value)
  return <span className="row" style={{ gap: 6, flexWrap: 'nowrap' }}><Badge tone={DISCRIMINATION[k].tone}>{DISCRIMINATION[k].label()}</Badge>{value !== null && value !== undefined && <span className="xsmall muted mono">{value.toFixed(2)}</span>}</span>
}

function Metric({ kpi }: { kpi: Kpi }) {
  return <div className="analytics-metric"><span className="small muted">{kpi.label}</span>
    {kpi.value === null ? <><strong className="analytics-na">{t(NO_DATA)}</strong><small className="muted">{kpi.reason}</small></> : <><strong>{kpi.value}</strong>{kpi.detail && <small className="muted">{kpi.detail}</small>}</>}</div>
}

function questionType(value: string) {
  return QUESTION_TYPE_LABEL[value] ?? value
}
