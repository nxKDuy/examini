import { Link, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { CheckCircle2, ChevronDown, Hourglass, PenLine, PlayCircle } from 'lucide-react'
import { examApi, type ResultItem } from './examApi'
import { attemptsFor } from './attemptHints'
import { useAuthStore } from '../../store/authStore'
import { ResultStatusBadge } from '../../components/status'
import { readableAnswers, studentAnswerText, studentPhase, studentPolls } from '../grading/gradingModel'
import { Badge, ErrorState, LoadingState, Notice, PageHeader, Section } from '../../components/ui'
import { QUESTION_TYPE_LABEL, formatDateTime, formatScore } from '../../lib/format'
import { t, useT } from '../../lib/i18n'

/** Own result. Only what the backend returned is rendered: no invented zero, no guessed key, no explanation toggle. */
export default function ExamResultPage() {
  useT()
  const { id } = useParams()
  const user = useAuthStore(s => s.user)
  const result = useQuery({ queryKey: ['result', id], queryFn: () => examApi.result(id!), refetchInterval: q => studentPolls(q.state.data?.status) })
  const examId = Object.entries(attemptsFor(user?.id)).find(([, sid]) => sid === id)?.[0]
  const exam = useQuery({ queryKey: ['lobby', examId], queryFn: () => examApi.lobby(examId!), enabled: !!examId && result.data?.status === 'GRADED', retry: false })

  if (result.isLoading) return <LoadingState rows={4} />
  if (result.isError || !result.data) return <ErrorState error={result.error} onRetry={() => void result.refetch()} />
  const r = result.data
  const phase = studentPhase(r.status)
  const hasScore = r.score !== undefined && r.totalScore !== undefined
  const passing = exam.data?.passingScore ?? null
  const passed = hasScore && passing !== null ? r.score! >= passing : null
  const questions = [...(r.questions ?? [])].sort((a, b) => a.position - b.position)
  const history = [...(r.history ?? [])].sort((a, b) => b.resultRevision - a.resultRevision)

  return (
    <div className="stack-lg" style={{ maxWidth: 780 }}>
      <PageHeader title={r.title || t('Kết quả bài thi')} crumbs={[{ label: t('Kỳ thi của tôi'), to: '/my-exams' }, { label: t('Kết quả') }]}
        description={<span className="row"><ResultStatusBadge status={r.status} />{phase === 'PUBLISHED' && <Badge tone="success">{t('Đã công bố')}</Badge>}{r.attemptNumber ? <span className="small muted">{t('Lần làm #')}{r.attemptNumber}</span> : null}</span>} />

      {r.invalidated && <Notice tone="danger" title={t('Lượt làm bài này đã bị vô hiệu hóa')} live>{t('Lý do:')} {r.invalidationReason?.trim() || t('không ghi')}{t('. Kết quả này không được tính là kết quả hợp lệ. Nếu cần thi lại, hãy liên hệ giáo viên để được cấp thêm lượt.')}</Notice>}

      {phase === 'PUBLISHED' && (
        <Section title={t('Điểm của bạn')}>
          {hasScore ? (
            <div className="row" style={{ gap: 20, alignItems: 'flex-end' }}>
              <div>
                <div className="stat-value" style={{ fontSize: '2.25rem' }}>{formatScore(r.score)}<span className="muted" style={{ fontSize: '1.125rem' }}> / {formatScore(r.totalScore)}</span></div>
                {r.totalScore ? <div className="small muted">{Math.round((r.score! / r.totalScore) * 1000) / 10}{t('% tổng điểm')}</div> : null}
              </div>
              {passed !== null && (passed
                ? <span className="badge badge-success"><CheckCircle2 size={12} aria-hidden /> {t('Đạt (điểm đạt')} {formatScore(passing)})</span>
                : <span className="badge badge-danger">{t('Chưa đạt (điểm đạt')} {formatScore(passing)})</span>)}
            </div>
          ) : <p className="small muted">{t('Kỳ thi này không hiển thị điểm tổng.')}</p>}
          <p className="xsmall muted" style={{ marginTop: 10 }}>
            {r.releasedAt && <>{t('Công bố')} {formatDateTime(r.releasedAt, '-')}</>}{r.gradedAt && <> {t('· Chấm xong')} {formatDateTime(r.gradedAt, '-')}</>}{r.resultRevision ? <> {t('· Bản #')}{r.resultRevision}</> : null}
          </p>
        </Section>
      )}

      {phase === 'WAITING' && (
        <Notice tone="info" title={t('Bài làm đã được ghi nhận')} live><span className="row" style={{ gap: 6 }}><Hourglass size={14} aria-hidden /> {t('Hệ thống đang chấm bài. Trang tự cập nhật khi có kết quả.')}</span></Notice>
      )}
      {phase === 'REVIEWING' && (
        <Notice tone="warning" title={t('Đang chờ giáo viên chấm')} live><span className="row" style={{ gap: 6 }}><PenLine size={14} aria-hidden /> {t('Bài có câu cần giáo viên chấm. Chưa có điểm; bạn có thể quay lại sau.')}</span></Notice>
      )}
      {phase === 'UNRELEASED' && (
        <Notice tone="info" title={t('Đã chấm, chờ công bố')} live><span className="row" style={{ gap: 6 }}><Hourglass size={14} aria-hidden /> {t('Giáo viên đã chấm xong nhưng chưa công bố kết quả. Bạn sẽ nhận thông báo khi có.')}</span></Notice>
      )}
      {phase === 'IN_PROGRESS' && (
        <Notice tone="info" title={t('Bài chưa được nộp')} action={<Link to={`/exam/take/${id}`} className="btn btn-primary btn-sm"><PlayCircle size={16} aria-hidden /> {t('Tiếp tục làm bài')}</Link>}>{t('Lượt làm bài này vẫn đang diễn ra.')}</Notice>
      )}
      {phase === 'UNKNOWN' && <Notice tone="info" title={t('Trạng thái bài làm')} live>{t('Chưa có kết quả để hiển thị.')}</Notice>}

      {phase === 'PUBLISHED' && r.feedback && (
        <Section title={t('Nhận xét của giáo viên')}><p className="prewrap small">{r.feedback}</p></Section>
      )}

      {phase === 'PUBLISHED' && questions.length > 0 && (
        <Section title={t('Chi tiết từng câu')} bodyClassName="">
          <ol className="stack" style={{ listStyle: 'none', margin: 0, padding: 16 }}>
            {questions.map(q => <QuestionResult key={q.questionId} q={q} />)}
          </ol>
        </Section>
      )}

      {phase === 'PUBLISHED' && history.length > 0 && (
        <details className="card">
          <summary className="card-header" style={{ cursor: 'pointer' }}><h2 className="row" style={{ gap: 6 }}><ChevronDown size={16} aria-hidden /> {t('Lịch sử công bố (')}{history.length})</h2></summary>
          <ul className="stack-sm card-body" style={{ listStyle: 'none', margin: 0 }}>
            {history.map((h, i) => (
              <li key={h.resultRevision} className="small">
                <strong>{t('Bản #')}{h.resultRevision}{i === 0 ? t(' (hiện tại)') : ''}</strong>
                {h.releasedAt && <> · {formatDateTime(h.releasedAt, '-')}</>}
                {h.score !== undefined && <> · {formatScore(h.score)}{h.totalScore !== undefined ? `/${formatScore(h.totalScore)}` : ''}</>}
                {h.publicReason && <div className="muted">{t('Lý do:')} {h.publicReason}</div>}
              </li>
            ))}
          </ul>
        </details>
      )}
      <div><Link to="/my-exams" className="btn btn-secondary">{t('Về Kỳ thi của tôi')}</Link></div>
    </div>
  )
}

function QuestionResult({ q }: { q: ResultItem }) {
  useT()
  const mine = studentAnswerText(q.questionType, q.answer, q.answerDisplay)
  const correct = readableAnswers(q.correctAnswers)
  return (
    <li className="card card-pad stack-sm" style={{ boxShadow: 'none' }}>
      <div className="spread">
        <span className="small">{t('Câu')} {q.position} · {QUESTION_TYPE_LABEL[q.questionType] ?? q.questionType}</span>
        {q.awardedPoints !== undefined && <span className="mono small">{formatScore(q.awardedPoints)}{q.pointValue !== undefined ? `/${formatScore(q.pointValue)}` : ''}</span>}
      </div>
      <p className="prewrap small" style={{ color: 'var(--color-ink)' }}>{q.questionContent}</p>
      <div className="small" style={{ padding: '8px 10px', background: 'var(--color-subtle)' }}>
        <span className="xsmall muted">{t('Bài làm của bạn')}</span>
        <p className="prewrap">{mine ? (mine.unresolved ? <em>{mine.text}</em> : mine.text) : <em className="muted">{t('Không trả lời')}</em>}</p>
      </div>
      {correct.length > 0 && <div className="small"><span className="xsmall muted">{t('Đáp án đúng')}</span><p className="prewrap">{correct.join(' · ')}</p></div>}
      {q.explanation?.trim() && <div className="small" aria-label={t('Giải thích')}><span className="xsmall muted">{t('Giải thích')}</span><p className="prewrap">{q.explanation}</p></div>}
      {q.feedback && <div className="small"><span className="xsmall muted">{t('Nhận xét')}</span><p className="prewrap">{q.feedback}</p></div>}
    </li>
  )
}
