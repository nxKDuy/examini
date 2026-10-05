import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Archive, CheckCircle2, Pencil, Send, XCircle } from 'lucide-react'
import { questionBankApi, type Question } from './questionApi'
import { useAuthStore } from '../../store/authStore'
import { audienceOf } from '../../components/layout/navigation'
import { QuestionStatusBadge } from '../../components/status'
import { QUESTION_STATUS } from '../../components/statusDefs'
import { ConfirmDialog } from '../../components/Dialog'
import { Badge, ErrorState, Field, LoadingState, Notice } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { DIFFICULTY_LABEL, QUESTION_TYPE_LABEL, formatDateTime, shortId } from '../../lib/format'

export function QuestionBody({ question }: { question: Question }) {
  return (
    <div className="stack">
      <p className="question-text">{question.content}</p>
      {question.options.length > 0 && (
        <ol className="stack-sm" style={{ listStyle: 'none', padding: 0, margin: 0 }}>
          {question.options.map((o, i) => (
            <li key={o.id ?? i} className="row" style={{ flexWrap: 'nowrap', alignItems: 'flex-start', padding: '8px 10px', borderRadius: 8,
              background: o.correct ? 'var(--color-success-soft)' : 'var(--color-subtle)' }}>
              <span className="option-key" aria-hidden>{String.fromCharCode(65 + i)}</span>
              <span className="prewrap" style={{ flex: 1 }}>{o.content}</span>
              {o.correct === true && <Badge tone="success" icon={<CheckCircle2 size={12} aria-hidden />}>Đáp án đúng</Badge>}
            </li>
          ))}
        </ol>
      )}
      {question.expectedAnswer && (
        <div className="stack-sm" style={{ gap: 4 }}>
          <span className="field-label">{question.questionType === 'ESSAY' ? 'Hướng dẫn chấm' : 'Đáp án chuẩn'}</span>
          <p className="prewrap small" style={{ padding: '8px 10px', background: 'var(--color-subtle)', borderRadius: 8 }}>{question.expectedAnswer}</p>
        </div>
      )}
      {question.sourceType && question.sourceType !== 'NONE' && (
        <div className="xsmall muted stack-sm" style={{ gap: 2 }}>
          <span>Nguồn: {question.sourceLabel || question.sourceType}{question.sourceRefId ? ` (mã ${question.sourceRefId})` : ''}</span>
          {question.sourceUrl && /^https?:\/\//i.test(question.sourceUrl) && <a href={question.sourceUrl} target="_blank" rel="noreferrer">{question.sourceUrl}</a>}
          {question.sourceExcerpt && <blockquote className="prewrap" style={{ margin: 0, paddingLeft: 10, borderLeft: '3px solid var(--color-border-strong)' }}>{question.sourceExcerpt}</blockquote>}
        </div>
      )}
    </div>
  )
}

type Action = 'submit' | 'approve' | 'reject' | 'archive'

export default function QuestionDetail({ id, onEdit }: { id: string; onEdit: (q: Question) => void }) {
  const qc = useQueryClient()
  const user = useAuthStore(s => s.user)
  const audience = audienceOf(user)
  const canReadAnswers = audience !== 'STUDENT'
  const [note, setNote] = useState('')
  const [confirm, setConfirm] = useState<Action | null>(null)
  const [message, setMessage] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null)
  const detail = useQuery({ queryKey: ['question', id, canReadAnswers], queryFn: () => questionBankApi.getQuestion(id, canReadAnswers) })
  const q = detail.data
  const reviews = useQuery({ queryKey: ['reviews', id, q?.status, q?.questionVersion], queryFn: () => questionBankApi.getReviews(id), enabled: !!q })
  const versions = useQuery({ queryKey: ['versions', id, q?.questionVersion], queryFn: () => questionBankApi.getVersions(id, canReadAnswers), enabled: !!q })

  const action = useMutation({
    mutationFn: async (kind: Action) => {
      if (!q) return
      if (kind === 'submit') return questionBankApi.submitReview(q)
      if (kind === 'archive') { await questionBankApi.archive(q); return }
      return questionBankApi.review(q, kind === 'approve' ? 'APPROVED' : 'REJECTED', note.trim())
    },
    onSuccess: async (_r, kind) => {
      setConfirm(null); setNote('')
      setMessage({ tone: 'success', text: { submit: 'Đã gửi duyệt.', approve: 'Đã phê duyệt câu hỏi.', reject: 'Đã từ chối câu hỏi.', archive: 'Đã lưu trữ câu hỏi.' }[kind] })
      await Promise.all([qc.invalidateQueries({ queryKey: ['questions'] }), qc.invalidateQueries({ queryKey: ['question', id] }), qc.invalidateQueries({ queryKey: ['reviews', id] })])
    },
    onError: e => { setConfirm(null); setMessage({ tone: 'danger', text: errorText(e) }) },
  })

  if (detail.isLoading) return <LoadingState rows={5} />
  if (detail.isError || !q) return <ErrorState error={detail.error} onRetry={() => void detail.refetch()} />

  const owns = canReadAnswers && q.createdBy === user?.id
  const reviewer = audience === 'ADMIN'
  const latestReview = reviews.data?.slice().sort((a, b) => b.reviewedAt.localeCompare(a.reviewedAt))[0]
  const canEdit = owns && ['DRAFT', 'REJECTED', 'APPROVED'].includes(q.status)

  return (
    <div className="stack-lg">
      <div className="stack-sm">
        <div className="row"><QuestionStatusBadge status={q.status} /><Badge>{QUESTION_TYPE_LABEL[q.questionType]}</Badge><Badge>{DIFFICULTY_LABEL[q.difficultyLevel]}</Badge><Badge tone="muted">Phiên bản {q.questionVersion}</Badge></div>
        <p className="xsmall muted">
          {q.categoryName} · {owns ? 'Câu hỏi của bạn' : `Tác giả ${shortId(q.createdBy)}`} · Cập nhật {formatDateTime(q.updatedAt)}
        </p>
        <p className="small muted">{QUESTION_STATUS[q.status]?.help}</p>
      </div>

      {q.status === 'REJECTED' && latestReview?.decision === 'REJECTED' && (
        <Notice tone="danger" title="Lý do từ chối">{latestReview.reviewNote || 'Không có ghi chú.'}</Notice>
      )}
      {message && <Notice tone={message.tone} live>{message.text}</Notice>}

      <QuestionBody question={q} />

      {(canEdit || owns || (reviewer && q.status === 'PENDING_REVIEW')) && (
        <div className="stack" style={{ paddingTop: 16, borderTop: '1px solid var(--color-border)' }}>
          {reviewer && q.status === 'PENDING_REVIEW' && (
            <div className="stack">
              <Field label="Ghi chú duyệt" htmlFor="review-note" hint="Bắt buộc khi từ chối; tác giả sẽ thấy ghi chú này.">
                <textarea id="review-note" className="input" rows={3} maxLength={5000} value={note} onChange={e => setNote(e.target.value)} />
              </Field>
              <div className="row">
                <button type="button" className="btn btn-primary" disabled={action.isPending} onClick={() => setConfirm('approve')}><CheckCircle2 size={16} aria-hidden /> Phê duyệt</button>
                <button type="button" className="btn btn-danger" disabled={action.isPending || !note.trim()} onClick={() => setConfirm('reject')}><XCircle size={16} aria-hidden /> Từ chối</button>
              </div>
            </div>
          )}
          <div className="row">
            {owns && q.status === 'DRAFT' && <button type="button" className="btn btn-primary" disabled={action.isPending} onClick={() => setConfirm('submit')}><Send size={16} aria-hidden /> Gửi duyệt</button>}
            {canEdit && <button type="button" className="btn btn-secondary" disabled={action.isPending} onClick={() => onEdit(q)}><Pencil size={16} aria-hidden /> {q.status === 'APPROVED' ? 'Sửa (tạo phiên bản mới)' : 'Sửa'}</button>}
            {owns && q.status !== 'ARCHIVED' && <button type="button" className="btn btn-danger-ghost" disabled={action.isPending} onClick={() => setConfirm('archive')}><Archive size={16} aria-hidden /> Lưu trữ</button>}
          </div>
          {owns && q.status === 'PENDING_REVIEW' && <p className="small muted">Đang chờ quản trị viên duyệt. Không thể sửa trong lúc chờ.</p>}
        </div>
      )}

      <details>
        <summary className="field-label" style={{ cursor: 'pointer' }}>Lịch sử duyệt ({reviews.data?.length ?? 0})</summary>
        <div className="stack-sm" style={{ marginTop: 10 }}>
          {reviews.isError && <p className="field-error">Không tải được lịch sử duyệt.</p>}
          {reviews.data?.length === 0 && <p className="small muted">Chưa có quyết định duyệt.</p>}
          {reviews.data?.map(r => (
            <div key={r.id} className="small" style={{ padding: '8px 10px', borderLeft: `3px solid ${r.decision === 'APPROVED' ? 'var(--color-success)' : 'var(--color-danger)'}`, background: 'var(--color-subtle)', borderRadius: 4 }}>
              <strong>{r.decision === 'APPROVED' ? 'Phê duyệt' : 'Từ chối'}</strong> phiên bản {r.questionVersion} · {formatDateTime(r.reviewedAt)} · người duyệt {shortId(r.reviewerId)}
              {r.reviewNote && <p className="prewrap" style={{ marginTop: 4 }}>{r.reviewNote}</p>}
            </div>
          ))}
        </div>
      </details>
      <details>
        <summary className="field-label" style={{ cursor: 'pointer' }}>Lịch sử phiên bản ({versions.data?.length ?? 0})</summary>
        <div className="stack-sm" style={{ marginTop: 10 }}>
          {versions.isError && <p className="field-error">Không tải được lịch sử phiên bản.</p>}
          {versions.data?.slice().reverse().map(v => (
            <details key={v.versionNumber} style={{ padding: '8px 10px', border: '1px solid var(--color-border)', borderRadius: 8 }}>
              <summary className="small" style={{ cursor: 'pointer' }}>Phiên bản {v.versionNumber} · {formatDateTime(v.createdAt)} · bởi {shortId(v.changedBy)}</summary>
              <div style={{ marginTop: 10 }}><QuestionBody question={v.question} /></div>
            </details>
          ))}
        </div>
      </details>

      {confirm === 'submit' && <ConfirmDialog title="Gửi câu hỏi đi duyệt?" confirmLabel="Gửi duyệt" busy={action.isPending}
        description="Trong lúc chờ duyệt, câu hỏi không thể sửa. Quản trị viên sẽ phê duyệt hoặc từ chối kèm lý do."
        onConfirm={() => action.mutate('submit')} onCancel={() => setConfirm(null)} />}
      {confirm === 'approve' && <ConfirmDialog title="Phê duyệt câu hỏi?" confirmLabel="Phê duyệt" busy={action.isPending}
        description={`Phiên bản ${q.questionVersion} sẽ được phép dùng trong đề thi.`} onConfirm={() => action.mutate('approve')} onCancel={() => setConfirm(null)} />}
      {confirm === 'reject' && <ConfirmDialog title="Từ chối câu hỏi?" confirmLabel="Từ chối" tone="danger" busy={action.isPending}
        description="Tác giả sẽ thấy ghi chú duyệt và cần sửa trước khi gửi lại." onConfirm={() => action.mutate('reject')} onCancel={() => setConfirm(null)} />}
      {confirm === 'archive' && <ConfirmDialog title="Lưu trữ câu hỏi?" confirmLabel="Lưu trữ" tone="danger" busy={action.isPending}
        description="Câu hỏi sẽ không còn dùng được cho đề thi mới. Đề thi đã xuất bản không bị ảnh hưởng." onConfirm={() => action.mutate('archive')} onCancel={() => setConfirm(null)} />}
    </div>
  )
}
