import { Fragment, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { analyticsApi, type AnalyticsStatus } from './analyticsApi'
import { NO_DATA, STUDENT_FILTERS, analyticsStatusLabel, attemptMetrics, filterStudents, headlineKpis, resultPageCount, type Kpi } from './analyticsModel'
import { Badge, EmptyState, ErrorState, LoadingState, Section } from '../../components/ui'
import { formatDateTime, formatScore, shortId } from '../../lib/format'
import './analytics.css'

const PAGE_SIZE = 20

export default function ExamAnalyticsPanel({ examId, actorId }: { examId: string; actorId: string }) {
  const [status, setStatus] = useState<AnalyticsStatus | ''>('')
  const [page, setPage] = useState(0)
  const [open, setOpen] = useState<string | null>(null)
  const on = Boolean(actorId)
  const summary = useQuery({ queryKey: ['analytics', actorId, examId, 'summary'], queryFn: () => analyticsApi.summary(examId), enabled: on, retry: false })
  const questions = useQuery({ queryKey: ['analytics', actorId, examId, 'questions'], queryFn: () => analyticsApi.questions(examId), enabled: on, retry: false })
  const students = useQuery({ queryKey: ['analytics', actorId, examId, 'students', page], queryFn: () => analyticsApi.students(examId, page, PAGE_SIZE), enabled: on, retry: false })
  const attempts = useQuery({ queryKey: ['analytics', actorId, examId, 'results', open], queryFn: () => analyticsApi.results(examId, open!), enabled: on && Boolean(open), retry: false })

  if (!on) return <EmptyState title="Không xác định được tài khoản" description="Đăng nhập lại để tải phân tích của kỳ thi." />
  const s = summary.data
  const rows = filterStudents(students.data?.content ?? [], status)

  return <div className="stack-lg analytics-panel">
    <Section title="Tổng quan kết quả" description="Số liệu từ Analytics; ô chưa có dữ liệu nêu rõ lý do thay vì hiện số ước đoán.">
      {summary.isLoading ? <LoadingState rows={2} /> : summary.isError ? <ErrorState error={summary.error} onRetry={() => void summary.refetch()} /> : s ? <>
        <div className="analytics-kpis" aria-label="Chỉ số chính">{headlineKpis(s).map(k => <Metric key={k.key} kpi={k} />)}</div>
        <h3 className="analytics-sub">Lượt làm bài</h3>
        <div className="analytics-kpis analytics-kpis-sm" aria-label="Chỉ số lượt làm bài">{attemptMetrics(s).map(k => <Metric key={k.key} kpi={k} />)}</div>
        <h3 className="analytics-sub">Phổ điểm</h3>
        <p className="analytics-nodata"><strong>{NO_DATA}.</strong> Phổ điểm chỉ vẽ theo điểm đã công bố mới nhất của mỗi Student; Analytics chưa nhận sự kiện công bố từ Grading nên không dựng biểu đồ từ điểm chấm tạm thời.</p>
      </> : <EmptyState title="Chưa có dữ liệu tổng hợp" description="Analytics chưa nhận được kết quả cho kỳ thi này." />}
    </Section>

    <Section title="Hiệu quả câu hỏi" description="Theo câu hỏi và phiên bản đã chụp trong đề, không truy vấn ngân hàng câu hỏi.">
      {questions.isLoading ? <LoadingState rows={3} /> : questions.isError ? <ErrorState error={questions.error} onRetry={() => void questions.refetch()} /> : !questions.data?.length ? <EmptyState title="Chưa có thống kê câu hỏi" description="Số liệu xuất hiện sau khi Analytics nhận kết quả." /> : <div className="table-wrap"><table className="table analytics-table">
        <thead><tr><th scope="col">Câu</th><th scope="col">Loại</th><th scope="col">Lượt</th><th scope="col">Đúng</th><th scope="col">Một phần</th><th scope="col">Sai</th><th scope="col">Bỏ trống</th><th scope="col">Chờ chấm</th><th scope="col">Tỷ lệ đúng</th><th scope="col">Điểm TB</th></tr></thead>
        <tbody>{questions.data.map(q => <tr key={`${q.questionId}:${q.questionVersion}`}>
          <td><strong>Câu {q.position}</strong><div className="small muted">v{q.questionVersion} · {shortId(q.questionId)}</div></td><td>{questionType(q.questionType)}</td><td>{q.attempts}</td><td>{q.correct}</td><td>{q.partial}</td><td>{q.incorrect}</td><td>{q.unanswered}</td><td>{q.pendingReview}</td>
          <td>{q.correctRate === null ? NO_DATA : `${(q.correctRate * 100).toFixed(1)}%`}</td><td>{q.averageAwardedPoints === null ? NO_DATA : `${formatScore(q.averageAwardedPoints)} / ${formatScore(q.pointValue)}`}</td>
        </tr>)}</tbody>
      </table></div>}
    </Section>

    <Section title="Kết quả Student" description="Mỗi Student một dòng (chỉ lượt hợp lệ); mở dòng để xem từng lượt. Chưa có tên hiển thị nên dùng mã rút gọn.">
      <div className="analytics-result-filters"><label htmlFor="analytics-status">Trạng thái mới nhất</label><select id="analytics-status" className="input" value={status} onChange={e => setStatus(e.target.value as AnalyticsStatus | '')}>
        {STUDENT_FILTERS.map(v => <option key={v || 'ALL'} value={v}>{analyticsStatusLabel[v || 'ALL']}</option>)}
      </select>{students.data && <span className="small muted">{students.data.totalElements} Student · lọc áp dụng cho trang đang xem</span>}</div>
      {students.isLoading ? <LoadingState rows={4} /> : students.isError ? <ErrorState error={students.error} onRetry={() => void students.refetch()} /> : !rows.length ? <EmptyState title="Không có Student phù hợp" description="Thử trạng thái khác hoặc quay lại khi có bài nộp." /> : <>
        <div className="table-wrap"><table className="table analytics-table"><thead><tr><th scope="col">Student</th><th scope="col">Đã nộp</th><th scope="col">Đã chấm</th><th scope="col">Cần chấm</th><th scope="col">Chờ</th><th scope="col">Mới nhất</th><th scope="col">Nộp lúc</th><th scope="col">Chi tiết</th></tr></thead>
          <tbody>{rows.map(r => <Fragment key={r.candidateId}>
            <tr><td><span className="mono" title={r.candidateId}>{shortId(r.candidateId)}</span></td><td>{r.submittedAttempts}</td><td>{r.gradedAttempts}</td><td>{r.needsReviewAttempts}</td><td>{r.pendingAttempts}</td>
              <td>{analyticsStatusLabel[r.latestGradingStatus as AnalyticsStatus] ?? r.latestGradingStatus}</td><td>{formatDateTime(r.latestSubmittedAt, '—')}</td>
              <td><button type="button" className="btn btn-secondary btn-sm" aria-expanded={open === r.candidateId} onClick={() => setOpen(open === r.candidateId ? null : r.candidateId)}>{open === r.candidateId ? 'Ẩn' : 'Xem lượt'}</button></td></tr>
            {open === r.candidateId && <tr className="analytics-drill"><td colSpan={8}>
              {attempts.isLoading ? <LoadingState rows={1} /> : attempts.isError ? <ErrorState error={attempts.error} onRetry={() => void attempts.refetch()} /> : !attempts.data?.content.length ? <p className="small muted">Không có lượt nào.</p> : <table className="table"><thead><tr><th scope="col">Lượt</th><th scope="col">Trạng thái</th><th scope="col">Điểm</th><th scope="col">Nộp lúc</th><th scope="col">Chấm lúc</th><th scope="col">Mở</th></tr></thead>
                <tbody>{attempts.data.content.map(a => <tr key={a.sessionId} className={a.invalidated ? 'analytics-invalidated' : undefined}>
                  <td>{a.attemptNumber ?? '—'}{a.invalidated && <> <Badge tone="warning">Vô hiệu hóa</Badge></>}</td><td>{analyticsStatusLabel[a.status as AnalyticsStatus] ?? a.status}</td>
                  <td>{a.score === null ? 'Chưa có điểm' : `${formatScore(a.score)} / ${formatScore(a.maxScore)}`}</td><td>{formatDateTime(a.submittedAt, '—')}</td><td>{formatDateTime(a.gradedAt, '—')}</td>
                  <td>{a.invalidated ? '—' : <Link to={`/grading?examId=${encodeURIComponent(examId)}&r=${encodeURIComponent(a.resultId)}`}>Mở chấm bài</Link>}</td></tr>)}</tbody></table>}
            </td></tr>}
          </Fragment>)}</tbody></table></div>
        <nav className="analytics-pagination" aria-label="Phân trang Student"><button className="btn btn-secondary btn-sm" type="button" disabled={page === 0 || students.isFetching} onClick={() => { setPage(p => Math.max(0, p - 1)); setOpen(null) }}>Trước</button><span className="small">Trang {page + 1} / {resultPageCount(students.data?.totalElements ?? 0, PAGE_SIZE)}</span><button className="btn btn-secondary btn-sm" type="button" disabled={page + 1 >= (students.data?.totalPages ?? 1) || students.isFetching} onClick={() => { setPage(p => p + 1); setOpen(null) }}>Sau</button></nav>
      </>}
    </Section>
  </div>
}

function Metric({ kpi }: { kpi: Kpi }) {
  return <div className="analytics-metric"><span className="small muted">{kpi.label}</span>
    {kpi.value === null ? <><strong className="analytics-na">{NO_DATA}</strong><small className="muted">{kpi.reason}</small></> : <><strong>{kpi.value}</strong>{kpi.detail && <small className="muted">{kpi.detail}</small>}</>}</div>
}

function questionType(value: string) {
  return ({ MULTIPLE_CHOICE: 'Trắc nghiệm', TRUE_FALSE: 'Đúng / Sai', SHORT_ANSWER: 'Trả lời ngắn', ESSAY: 'Tự luận' } as Record<string, string>)[value] ?? value
}
