import { useEffect, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, ClipboardCheck, PenLine, XCircle } from 'lucide-react'
import { gradingApi, type Submission, type SubmissionAnswer } from './gradingApi'
import { examApi } from '../exams/examApi'
import { ResultStatusBadge } from '../../components/status'
import { ConfirmDialog } from '../../components/Dialog'
import { Badge, EmptyState, ErrorState, Field, LoadingState, Notice, PageHeader, Pagination } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { QUESTION_TYPE_LABEL, formatDateTime, formatDuration, formatScore, shortId } from '../../lib/format'

const TABS = [{ value: '', label: 'Tất cả' }, { value: 'NEEDS_REVIEW', label: 'Cần chấm tay' }, { value: 'PENDING', label: 'Chờ chấm' }, { value: 'GRADED', label: 'Đã chấm' }]

export default function GradingPage() {
  const [params, setParams] = useSearchParams()
  const examId = params.get('examId') ?? ''
  const status = params.get('status') ?? ''
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<string | null>(null)
  const exams = useQuery({ queryKey: ['exams', 'all'], queryFn: examApi.listAll })
  const gradable = (exams.data ?? []).filter(e => e.status !== 'DRAFT')
  const subs = useQuery({
    queryKey: ['submissions', examId, status, page], enabled: !!examId, placeholderData: prev => prev,
    queryFn: () => gradingApi.getSubmissions({ examId, status: status || undefined, page }),
  })
  const update = (key: string, value: string) => {
    const next = new URLSearchParams(params)
    if (value) next.set(key, value); else next.delete(key)
    if (key === 'examId') next.delete('status')
    setParams(next, { replace: true }); setPage(0); setSelected(null)
  }

  return (
    <div className="stack">
      <PageHeader title="Chấm bài" description="Câu trắc nghiệm, Đúng/Sai và trả lời ngắn được chấm tự động. Bài có câu tự luận ở trạng thái Cần chấm tay cho đến khi bạn lưu điểm cuối." />
      <section className="card">
        <div className="card-body" style={{ display: 'grid', gap: 12, gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 260px), 1fr))', paddingBottom: 16 }}>
          <Field label="Kỳ thi" htmlFor="grading-exam" hint="Chỉ Chủ sở hữu và Quản lý của kỳ thi được chấm bài.">
            <select id="grading-exam" className="input" value={examId} onChange={e => update('examId', e.target.value)}>
              <option value="">Chọn kỳ thi</option>
              {gradable.map(e => <option key={e.id} value={e.id}>{e.title}</option>)}
            </select>
          </Field>
        </div>
        {examId && <div className="tabs" role="tablist" aria-label="Trạng thái chấm">
          {TABS.map(t => <button key={t.value} type="button" role="tab" className="tab" aria-selected={status === t.value} onClick={() => update('status', t.value)}>{t.label}</button>)}
        </div>}
      </section>

      {!examId ? (
        <section className="card">{exams.isError ? <ErrorState error={exams.error} onRetry={() => void exams.refetch()} />
          : <EmptyState icon={<ClipboardCheck size={30} />} title="Chọn một kỳ thi" description={exams.data && gradable.length === 0 ? 'Bạn chưa có kỳ thi đã xuất bản. Bài nộp xuất hiện sau khi kỳ thi được mở và thí sinh nộp bài.' : 'Chọn kỳ thi để xem danh sách bài nộp.'} />}</section>
      ) : (
        <div className="split">
          <section className="card" aria-label="Danh sách bài nộp" style={{ overflow: 'hidden' }}>
            <div className="card-header"><h2 style={{ fontSize: '0.9375rem' }}>{subs.data ? `${subs.data.totalElements} bài nộp` : 'Bài nộp'}</h2></div>
            {subs.isLoading ? <LoadingState rows={4} /> : subs.isError ? <ErrorState error={subs.error} onRetry={() => void subs.refetch()} />
              : subs.data!.content.length === 0 ? <EmptyState title="Không có bài nộp" description={status ? 'Không có bài ở trạng thái này.' : 'Bài nộp xuất hiện khi thí sinh nộp bài hoặc hết giờ.'} />
              : subs.data!.content.map(s => <SubmissionRow key={s.id} s={s} active={selected === s.id} onSelect={() => setSelected(s.id)} />)}
            {subs.data && <Pagination label="Phân trang bài nộp" page={page} totalPages={subs.data.totalPages} onChange={setPage} />}
          </section>
          <section className="card card-body sticky-pane" aria-label="Chi tiết bài nộp">
            {selected ? <SubmissionDetail key={selected} id={selected} /> : <EmptyState title="Chọn một bài nộp" description="Xem câu trả lời, kết quả chấm tự động và lưu điểm cuối." />}
          </section>
        </div>
      )}
    </div>
  )
}

function SubmissionRow({ s, active, onSelect }: { s: Submission; active: boolean; onSelect: () => void }) {
  return (
    <button type="button" className="list-item" aria-current={active} onClick={onSelect}>
      <div className="spread">
        <span className="mono small" style={{ color: 'var(--color-ink)' }} title={s.studentId}>Thí sinh {shortId(s.studentId)}</span>
        <ResultStatusBadge status={s.status} />
      </div>
      <div className="spread xsmall muted" style={{ marginTop: 4 }}>
        <span>Nộp {formatDateTime(s.submittedAt, '-')}</span>
        <span className="mono">{s.status === 'GRADED' ? `${formatScore(s.score)}/${formatScore(s.totalScore)}` : `-/${formatScore(s.totalScore)}`}</span>
      </div>
    </button>
  )
}

function SubmissionDetail({ id }: { id: string }) {
  const qc = useQueryClient()
  const detail = useQuery({ queryKey: ['submission', id], queryFn: () => gradingApi.getSubmissionDetail(id) })
  const [score, setScore] = useState('')
  const [feedback, setFeedback] = useState('')
  const [confirm, setConfirm] = useState(false)
  const [message, setMessage] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null)
  const d = detail.data
  useEffect(() => {
    if (!d) return
    const auto = d.answers.reduce((sum, a) => sum + (a.earnedPoints ?? 0), 0)
    setScore(String(d.score ?? auto)); setFeedback(d.feedback ?? '')
  }, [d])
  const grade = useMutation({
    mutationFn: () => gradingApi.gradeSubmission(id, { score: Number(score), feedback: feedback.trim() || undefined }),
    onSuccess: async () => {
      setConfirm(false); setMessage({ tone: 'success', text: 'Đã lưu điểm cuối. Thí sinh có thể xem kết quả.' })
      await Promise.all([qc.invalidateQueries({ queryKey: ['submission', id] }), qc.invalidateQueries({ queryKey: ['submissions'] })])
    },
    onError: e => { setConfirm(false); setMessage({ tone: 'danger', text: errorText(e) }) },
  })
  if (detail.isLoading) return <LoadingState rows={5} />
  if (detail.isError || !d) return <ErrorState error={detail.error} onRetry={() => void detail.refetch()} />

  const max = d.totalScore ?? 0
  const value = Number(score)
  const invalid = score.trim() === '' || !Number.isFinite(value) || value < 0 || value > max
  const auto = d.answers.reduce((sum, a) => sum + (a.earnedPoints ?? 0), 0)
  const manual = d.answers.filter(a => a.isCorrect === null).length

  function submit(e: FormEvent) { e.preventDefault(); if (!invalid) setConfirm(true) }

  return (
    <div className="stack-lg">
      <div className="stack-sm">
        <div className="spread"><h2>Thí sinh <span className="mono">{shortId(d.studentId)}</span></h2><ResultStatusBadge status={d.status} /></div>
        <p className="xsmall muted">Mã người dùng {d.studentId} · Nộp {formatDateTime(d.submittedAt, '-')}{d.durationSeconds ? ` · Làm trong ${formatDuration(d.durationSeconds)}` : ''}</p>
      </div>
      {message && <Notice tone={message.tone} live>{message.text}</Notice>}
      {d.status === 'NEEDS_REVIEW' && <Notice tone="warning">Có {manual} câu cần chấm tay. Điểm tự động hiện tại: {formatScore(auto)}/{formatScore(max)}.</Notice>}

      <ol className="stack" style={{ listStyle: 'none', padding: 0, margin: 0 }}>
        {d.answers.map((a, i) => <AnswerCard key={a.questionId} a={a} n={i + 1} />)}
      </ol>

      <form onSubmit={submit} className="stack" style={{ borderTop: '1px solid var(--color-border)', paddingTop: 16 }}>
        <h3>Điểm cuối</h3>
        <Field label={`Tổng điểm (0 đến ${formatScore(max)})`} htmlFor="final-score" error={score.trim() !== '' && invalid ? `Nhập điểm từ 0 đến ${formatScore(max)}.` : undefined}
          hint={`Gợi ý: ${formatScore(auto)} điểm từ phần chấm tự động, cộng điểm bạn chấm cho phần tự luận.`}>
          <input id="final-score" type="number" min={0} max={max} step={0.25} className="input" style={{ maxWidth: 180 }} value={score} aria-invalid={invalid}
            onChange={e => setScore(e.target.value)} />
        </Field>
        <Field label="Nhận xét cho thí sinh (tùy chọn)" htmlFor="final-feedback">
          <textarea id="final-feedback" className="input" rows={3} value={feedback} onChange={e => setFeedback(e.target.value)} />
        </Field>
        <div><button type="submit" className="btn btn-primary" disabled={invalid || grade.isPending}>{d.status === 'GRADED' ? 'Cập nhật điểm' : 'Lưu điểm cuối'}</button></div>
      </form>
      {confirm && <ConfirmDialog title="Lưu điểm cuối?" confirmLabel="Lưu điểm" busy={grade.isPending}
        description={`Bài sẽ chuyển sang Đã chấm với ${formatScore(value)}/${formatScore(max)} điểm và thí sinh sẽ thấy kết quả.`}
        onConfirm={() => grade.mutate()} onCancel={() => setConfirm(false)} />}
    </div>
  )
}

function AnswerCard({ a, n }: { a: SubmissionAnswer; n: number }) {
  const objective = a.questionType === 'MULTIPLE_CHOICE' || a.questionType === 'TRUE_FALSE'
  const response = objective ? a.selectedOptionContent : a.textAnswer
  return (
    <li className="card card-pad stack-sm" style={{ boxShadow: 'none' }}>
      <div className="spread">
        <span className="small">Câu {n} · {QUESTION_TYPE_LABEL[a.questionType]}</span>
        <span className="row">
          {a.isCorrect === true && <Badge tone="success" icon={<CheckCircle2 size={12} aria-hidden />}>Đúng</Badge>}
          {a.isCorrect === false && <Badge tone="danger" icon={<XCircle size={12} aria-hidden />}>Sai</Badge>}
          {a.isCorrect === null && <Badge tone="warning" icon={<PenLine size={12} aria-hidden />}>Cần chấm tay</Badge>}
          <span className="mono small">{formatScore(a.earnedPoints)}/{formatScore(a.pointValue)}</span>
        </span>
      </div>
      <p className="prewrap small" style={{ color: 'var(--color-ink)' }}>{a.questionContent}</p>
      <div className="small" style={{ padding: '8px 10px', background: 'var(--color-subtle)' }}>
        <span className="xsmall muted">Bài làm của thí sinh</span>
        <p className="prewrap">{response?.trim() ? response : <em className="muted">Không trả lời</em>}</p>
      </div>
    </li>
  )
}
