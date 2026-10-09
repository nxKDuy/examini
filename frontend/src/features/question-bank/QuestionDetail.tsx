import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Archive, CheckCircle2, Pencil } from 'lucide-react'
import { bankApi } from '../banks/bankApi'
import { canShare } from '../banks/bankModel'
import ProvenanceBlock from '../banks/ProvenanceBlock'
import { SubmitToOrgDialog, PublishPublicDialog } from '../banks/ShareDialogs'
import { questionBankApi, type Question } from './questionApi'
import { useAuthStore } from '../../store/authStore'
import { audienceOf } from '../../components/layout/navigation'
import { QuestionStatusBadge } from '../../components/status'
import { QUESTION_STATUS } from '../../components/statusDefs'
import { ConfirmDialog } from '../../components/Dialog'
import { Badge, ErrorState, LoadingState, Notice } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { DIFFICULTY_LABEL, QUESTION_TYPE_LABEL, formatDateTime, shortId } from '../../lib/format'

export function QuestionBody({ question }: { question: Question }) {
  return (
    <div className="stack">
      <p className="question-text">{question.content}</p>
      {question.options.length > 0 && (
        <ol className="stack-sm" style={{ listStyle: 'none', padding: 0, margin: 0 }}>
          {question.options.map((o, i) => (
            <li key={o.id ?? i} className="row" style={{ flexWrap: 'nowrap', alignItems: 'flex-start', padding: '8px 10px',
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
          <p className="prewrap small" style={{ padding: '8px 10px', background: 'var(--color-subtle)' }}>{question.expectedAnswer}</p>
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

type Action = 'activate' | 'archive'

export default function QuestionDetail({ id, onEdit }: { id: string; onEdit: (q: Question) => void }) {
  const qc = useQueryClient()
  const user = useAuthStore(s => s.user)
  const audience = audienceOf(user)
  const canReadAnswers = audience !== 'STUDENT'
  const [sharing, setSharing] = useState<'org' | 'public' | null>(null)
  const [confirm, setConfirm] = useState<Action | null>(null)
  const [message, setMessage] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null)
  const detail = useQuery({ queryKey: ['question', id, canReadAnswers], queryFn: () => questionBankApi.getQuestion(id, canReadAnswers) })
  const q = detail.data
  const reviews = useQuery({ queryKey: ['reviews', id, q?.status, q?.questionVersion], queryFn: () => questionBankApi.getReviews(id), enabled: !!q })
  const versions = useQuery({ queryKey: ['versions', id, q?.questionVersion], queryFn: () => questionBankApi.getVersions(id, canReadAnswers), enabled: !!q })

  const action = useMutation({
    mutationFn: async (kind: Action) => {
      if (!q) return
      if (kind === 'activate') return bankApi.activate(q.id, q.questionVersion)
      if (kind === 'archive') { await questionBankApi.archive(q); return }
    },
    onSuccess: async (_r, kind) => {
      setConfirm(null)
      setMessage({ tone: 'success', text: { activate: 'Đã kích hoạt câu hỏi. Không cần duyệt.', archive: 'Đã lưu trữ câu hỏi.' }[kind] })
      await Promise.all([qc.invalidateQueries({ queryKey: ['questions'] }), qc.invalidateQueries({ queryKey: ['question', id] }), qc.invalidateQueries({ queryKey: ['reviews', id] })])
    },
    onError: async e => { setConfirm(null); setMessage({ tone: 'danger', text: errorText(e) }); await detail.refetch() },
  })

  if (detail.isLoading) return <LoadingState rows={5} />
  if (detail.isError || !q) return <ErrorState error={detail.error} onRetry={() => void detail.refetch()} />

  const owns = canReadAnswers && q.createdBy === user?.id
  const personal = q.bankType === 'PERSONAL' || (!q.bankType && q.scope === 'PERSONAL')
  const canEdit = owns && ['DRAFT', 'ACTIVE'].includes(q.status)

  return (
    <div className="stack-lg">
      <div className="stack-sm">
        <div className="row"><QuestionStatusBadge status={q.status} /><Badge>{QUESTION_TYPE_LABEL[q.questionType]}</Badge><Badge>{DIFFICULTY_LABEL[q.difficultyLevel]}</Badge><Badge tone="muted">Phiên bản {q.questionVersion}</Badge></div>
        <p className="xsmall muted">
          {q.categoryName} · {owns ? 'Câu hỏi của bạn' : `Tác giả ${shortId(q.createdBy)}`} · Cập nhật {formatDateTime(q.updatedAt)}
        </p>
        <p className="small muted">{QUESTION_STATUS[q.status]?.help}</p>
      </div>

      {message && <Notice tone={message.tone} live>{message.text}</Notice>}

      <QuestionBody question={q} />
      {owns && q.explanation?.trim() && <section aria-label="Giải thích của người soạn"><span className="field-label">Giải thích của người soạn</span><p className="prewrap small">{q.explanation}</p><p className="xsmall muted">Chỉ được đưa vào kết quả học sinh khi chính sách cho phép và kỳ thi đã đóng.</p></section>}

      <ProvenanceBlock questionId={q.id} />
      {owns && <div className="stack" style={{ paddingTop: 16, borderTop: '1px solid var(--color-border)' }}>
        <div className="row">
          {q.status === 'DRAFT' && <button type="button" className="btn btn-primary" disabled={action.isPending} onClick={() => setConfirm('activate')}><CheckCircle2 size={16} aria-hidden /> Kích hoạt</button>}
          {canEdit && <button type="button" className="btn btn-secondary" onClick={() => onEdit(q)}><Pencil size={16} aria-hidden /> {q.status === 'ACTIVE' ? 'Sửa (tạo phiên bản mới)' : 'Sửa'}</button>}
          {q.status !== 'ARCHIVED' && <button type="button" className="btn btn-danger-ghost" disabled={action.isPending} onClick={() => setConfirm('archive')}><Archive size={16} aria-hidden /> Lưu trữ</button>}
          {personal && canShare(q) && <>
            <button type="button" className="btn btn-secondary" onClick={() => setSharing('org')}>Gửi vào tổ chức</button>
            <button type="button" className="btn btn-secondary" onClick={() => setSharing('public')}>Xuất bản công khai</button>
          </>}
        </div>
      </div>}
      {sharing === 'org' && <SubmitToOrgDialog questionId={q.id} version={q.questionVersion} onClose={() => setSharing(null)} onDone={text => { setSharing(null); setMessage({ tone: 'success', text }); void qc.invalidateQueries({ queryKey: ['banks'] }) }} />}
      {sharing === 'public' && <PublishPublicDialog questionId={q.id} version={q.questionVersion} onClose={() => setSharing(null)} onDone={text => { setSharing(null); setMessage({ tone: 'success', text }); void qc.invalidateQueries({ queryKey: ['banks'] }) }} />}

      <details>
        <summary className="field-label" style={{ cursor: 'pointer' }}>Lịch sử duyệt cũ ({reviews.data?.length ?? 0})</summary>
        <div className="stack-sm" style={{ marginTop: 10 }}>
          {reviews.isError && <p className="field-error">Không tải được lịch sử duyệt.</p>}
          {reviews.data?.length === 0 && <p className="small muted">Chưa có quyết định duyệt.</p>}
          {reviews.data?.map(r => (
            <div key={r.id} className="small" style={{ padding: '8px 10px', borderLeft: `3px solid ${r.decision === 'APPROVED' ? 'var(--color-success)' : 'var(--color-danger)'}`, background: 'var(--color-subtle)' }}>
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
            <details key={v.versionNumber} style={{ padding: '8px 10px', border: '1px solid var(--color-border)' }}>
              <summary className="small" style={{ cursor: 'pointer' }}>Phiên bản {v.versionNumber} · {formatDateTime(v.createdAt)} · bởi {shortId(v.changedBy)}</summary>
              <div style={{ marginTop: 10 }}><QuestionBody question={v.question} /></div>
            </details>
          ))}
        </div>
      </details>

      {confirm === 'activate' && <ConfirmDialog title="Kích hoạt câu hỏi?" confirmLabel="Kích hoạt" busy={action.isPending}
        description={`Phiên bản ${q.questionVersion} sẽ được dùng theo quyền ngân hàng, không cần duyệt. Đóng góp vào tổ chức là thao tác riêng.`}
        onConfirm={() => action.mutate('activate')} onCancel={() => setConfirm(null)} />}
      {confirm === 'archive' && <ConfirmDialog title="Lưu trữ câu hỏi?" confirmLabel="Lưu trữ" tone="danger" busy={action.isPending}
        description="Câu hỏi sẽ không còn dùng được cho đề thi mới. Đề thi đã xuất bản không bị ảnh hưởng." onConfirm={() => action.mutate('archive')} onCancel={() => setConfirm(null)} />}
    </div>
  )
}
