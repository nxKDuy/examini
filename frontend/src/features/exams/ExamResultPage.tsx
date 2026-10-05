import { Link, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { CheckCircle2, Hourglass, PenLine, PlayCircle } from 'lucide-react'
import { examApi } from './examApi'
import { attemptsFor } from './attemptHints'
import { useAuthStore } from '../../store/authStore'
import { ResultStatusBadge } from '../../components/status'
import { ErrorState, LoadingState, Notice, PageHeader, Section } from '../../components/ui'
import { formatScore } from '../../lib/format'

const PENDING = new Set(['SUBMITTED', 'GRADING', 'PENDING'])

export default function ExamResultPage() {
  const { id } = useParams()
  const user = useAuthStore(s => s.user)
  const result = useQuery({
    queryKey: ['result', id], queryFn: () => examApi.result(id!),
    refetchInterval: q => { const s = q.state.data?.status; return s === 'GRADED' ? false : s === 'NEEDS_REVIEW' ? 30_000 : 3000 },
  })
  const examId = Object.entries(attemptsFor(user?.id)).find(([, sid]) => sid === id)?.[0]
  const exam = useQuery({ queryKey: ['lobby', examId], queryFn: () => examApi.lobby(examId!), enabled: !!examId && result.data?.status === 'GRADED', retry: false })

  if (result.isLoading) return <LoadingState rows={4} />
  if (result.isError || !result.data) return <ErrorState error={result.error} onRetry={() => void result.refetch()} />
  const r = result.data
  const passing = exam.data?.passingScore ?? null
  const passed = r.status === 'GRADED' && passing !== null && r.score !== null ? r.score >= passing : null

  return (
    <div className="stack-lg" style={{ maxWidth: 760 }}>
      <PageHeader title={r.title || 'Kết quả bài thi'} crumbs={[{ label: 'Kỳ thi của tôi', to: '/my-exams' }, { label: 'Kết quả' }]}
        description={<ResultStatusBadge status={r.status} />} />

      {r.status === 'GRADED' && (
        <Section title="Điểm của bạn">
          <div className="row" style={{ gap: 20, alignItems: 'flex-end' }}>
            <div>
              <div className="stat-value" style={{ fontSize: '2.25rem' }}>{formatScore(r.score)}<span className="muted" style={{ fontSize: '1.125rem', fontWeight: 500 }}> / {formatScore(r.totalScore)}</span></div>
              {r.score !== null && r.totalScore ? <div className="small muted">{Math.round((r.score / r.totalScore) * 1000) / 10}% tổng điểm</div> : null}
            </div>
            {passed !== null && (passed
              ? <span className="badge badge-success"><CheckCircle2 size={12} aria-hidden /> Đạt (điểm đạt {formatScore(passing)})</span>
              : <span className="badge badge-danger">Chưa đạt (điểm đạt {formatScore(passing)})</span>)}
          </div>
          {r.feedback && <div className="stack-sm" style={{ marginTop: 16 }}><span className="field-label">Nhận xét</span><p className="prewrap small">{r.feedback}</p></div>}
        </Section>
      )}

      {PENDING.has(r.status) && (
        <Notice tone="info" title="Bài làm đã được ghi nhận" live>
          <span className="row" style={{ gap: 6 }}><Hourglass size={14} aria-hidden /> Hệ thống đang chấm bài. Trang sẽ tự cập nhật khi có kết quả.</span>
        </Notice>
      )}
      {r.status === 'NEEDS_REVIEW' && (
        <Notice tone="warning" title="Đang chờ giáo viên chấm" live>
          <span className="row" style={{ gap: 6 }}><PenLine size={14} aria-hidden /> Bài có câu tự luận cần giáo viên chấm. Điểm sẽ được công bố sau khi chấm xong; bạn có thể quay lại trang này sau.</span>
        </Notice>
      )}
      {r.status === 'IN_PROGRESS' && (
        <Notice tone="info" title="Bài chưa được nộp" action={<Link to={`/exam/take/${id}`} className="btn btn-primary btn-sm"><PlayCircle size={16} aria-hidden /> Tiếp tục làm bài</Link>}>
          Lượt làm bài này vẫn đang diễn ra.
        </Notice>
      )}
      <div><Link to="/my-exams" className="btn btn-secondary">Về Kỳ thi của tôi</Link></div>
    </div>
  )
}
