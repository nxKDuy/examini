import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Archive, CheckCircle2, Pencil } from 'lucide-react'
import { bankApi } from '../banks/bankApi'
import { canShare } from '../banks/bankModel'
import ProvenanceBlock from '../banks/ProvenanceBlock'
import { CopySyncEntry } from '../banks/CopySync'
import { SubmitToOrgDialog } from '../banks/ShareDialogs'
import { questionBankApi, type Question } from './questionApi'
import { useAuthStore } from '../../store/authStore'
import { audienceOf } from '../../components/layout/navigation'
import { QuestionStatusBadge } from '../../components/status'
import { QUESTION_STATUS } from '../../components/statusDefs'
import { ConfirmDialog } from '../../components/Dialog'
import { Badge, ErrorState, LoadingState, Notice } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { DIFFICULTY_LABEL, QUESTION_TYPE_LABEL, formatDateTime, shortId } from '../../lib/format'
import { t, useT } from '../../lib/i18n'

export function QuestionBody({ question }: { question: Question }) {
  useT()
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
              {o.correct === true && <Badge tone="success" icon={<CheckCircle2 size={12} aria-hidden />}>{t('Đáp án đúng')}</Badge>}
            </li>
          ))}
        </ol>
      )}
      {question.expectedAnswer && (
        <div className="stack-sm" style={{ gap: 4 }}>
          <span className="field-label">{question.questionType === 'ESSAY' ? t('Hướng dẫn chấm') : t('Đáp án chuẩn')}</span>
          <p className="prewrap small" style={{ padding: '8px 10px', background: 'var(--color-subtle)' }}>{question.expectedAnswer}</p>
        </div>
      )}
      {question.sourceType && question.sourceType !== 'NONE' && (
        <div className="xsmall muted stack-sm" style={{ gap: 2 }}>
          <span>{t('Nguồn:')} {question.sourceLabel || question.sourceType}{question.sourceRefId ? t(' (mã {0})', { 0: question.sourceRefId }) : ''}</span>
          {question.sourceUrl && /^https?:\/\//i.test(question.sourceUrl) && <a href={question.sourceUrl} target="_blank" rel="noreferrer">{question.sourceUrl}</a>}
          {question.sourceExcerpt && <blockquote className="prewrap" style={{ margin: 0, paddingLeft: 10, borderLeft: '3px solid var(--color-border-strong)' }}>{question.sourceExcerpt}</blockquote>}
        </div>
      )}
    </div>
  )
}

type Action = 'activate' | 'archive'

export default function QuestionDetail({ id, onEdit }: { id: string; onEdit: (q: Question) => void }) {
  useT()
  const qc = useQueryClient()
  const user = useAuthStore(s => s.user)
  const audience = audienceOf(user)
  const canReadAnswers = audience !== 'STUDENT'
  const [sharing, setSharing] = useState(false)
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
      setMessage({ tone: 'success', text: { activate: t('Đã kích hoạt câu hỏi. Không cần duyệt.'), archive: t('Đã lưu trữ câu hỏi.') }[kind] })
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
        <div className="row"><QuestionStatusBadge status={q.status} /><Badge>{QUESTION_TYPE_LABEL[q.questionType]}</Badge><Badge>{DIFFICULTY_LABEL[q.difficultyLevel]}</Badge><Badge tone="muted">{t('Phiên bản')} {q.questionVersion}</Badge></div>
        <p className="xsmall muted">
          {q.categoryName} · {owns ? t('Câu hỏi của bạn') : t('Tác giả {0}', { 0: shortId(q.createdBy) })} {t('· Cập nhật')} {formatDateTime(q.updatedAt)}
        </p>
        <p className="small muted">{QUESTION_STATUS[q.status]?.help}</p>
      </div>

      {message && <Notice tone={message.tone} live>{message.text}</Notice>}

      <QuestionBody question={q} />
      {owns && q.explanation?.trim() && <section aria-label={t('Giải thích của người soạn')}><span className="field-label">{t('Giải thích của người soạn')}</span><p className="prewrap small">{q.explanation}</p><p className="xsmall muted">{t('Chỉ được đưa vào kết quả học sinh khi chính sách cho phép và kỳ thi đã đóng.')}</p></section>}

      <ProvenanceBlock questionId={q.id} />
      {owns && personal && q.status !== 'ARCHIVED' && <CopySyncEntry kind="question" id={q.id} />}
      {owns && <div className="stack" style={{ paddingTop: 16, borderTop: '1px solid var(--color-border)' }}>
        <div className="row">
          {q.status === 'DRAFT' && <button type="button" className="btn btn-primary" disabled={action.isPending} onClick={() => setConfirm('activate')}><CheckCircle2 size={16} aria-hidden /> {t('Kích hoạt')}</button>}
          {canEdit && <button type="button" className="btn btn-secondary" onClick={() => onEdit(q)}><Pencil size={16} aria-hidden /> {q.status === 'ACTIVE' ? t('Sửa (tạo phiên bản mới)') : t('Sửa')}</button>}
          {q.status !== 'ARCHIVED' && <button type="button" className="btn btn-danger-ghost" disabled={action.isPending} onClick={() => setConfirm('archive')}><Archive size={16} aria-hidden /> {t('Lưu trữ')}</button>}
          {personal && canShare(q) && <button type="button" className="btn btn-secondary" onClick={() => setSharing(true)}>{t('Gửi vào tổ chức')}</button>}
        </div>
        {personal && <p className="xsmall muted">{t('Muốn đăng câu này lên cộng đồng? Dùng nút + ở Ngân hàng công khai.')}</p>}
      </div>}
      {sharing && <SubmitToOrgDialog questionId={q.id} version={q.questionVersion} onClose={() => setSharing(false)} onDone={text => { setSharing(false); setMessage({ tone: 'success', text }); void qc.invalidateQueries({ queryKey: ['banks'] }) }} />}

      <details>
        <summary className="field-label" style={{ cursor: 'pointer' }}>{t('Lịch sử duyệt cũ (')}{reviews.data?.length ?? 0})</summary>
        <div className="stack-sm" style={{ marginTop: 10 }}>
          {reviews.isError && <p className="field-error">{t('Không tải được lịch sử duyệt.')}</p>}
          {reviews.data?.length === 0 && <p className="small muted">{t('Chưa có quyết định duyệt.')}</p>}
          {reviews.data?.map(r => (
            <div key={r.id} className="small" style={{ padding: '8px 10px', borderLeft: `3px solid ${r.decision === 'APPROVED' ? 'var(--color-success)' : 'var(--color-danger)'}`, background: 'var(--color-subtle)' }}>
              <strong>{r.decision === 'APPROVED' ? t('Phê duyệt') : t('Từ chối')}</strong> {t('phiên bản')} {r.questionVersion} · {formatDateTime(r.reviewedAt)} {t('· người duyệt')} {shortId(r.reviewerId)}
              {r.reviewNote && <p className="prewrap" style={{ marginTop: 4 }}>{r.reviewNote}</p>}
            </div>
          ))}
        </div>
      </details>
      <details>
        <summary className="field-label" style={{ cursor: 'pointer' }}>{t('Lịch sử phiên bản (')}{versions.data?.length ?? 0})</summary>
        <div className="stack-sm" style={{ marginTop: 10 }}>
          {versions.isError && <p className="field-error">{t('Không tải được lịch sử phiên bản.')}</p>}
          {versions.data?.slice().reverse().map(v => (
            <details key={v.versionNumber} style={{ padding: '8px 10px', border: '1px solid var(--color-border)' }}>
              <summary className="small" style={{ cursor: 'pointer' }}>{t('Phiên bản')} {v.versionNumber} · {formatDateTime(v.createdAt)} {t('· bởi')} {shortId(v.changedBy)}</summary>
              <div style={{ marginTop: 10 }}><QuestionBody question={v.question} /></div>
            </details>
          ))}
        </div>
      </details>

      {confirm === 'activate' && <ConfirmDialog title={t('Kích hoạt câu hỏi?')} confirmLabel={t('Kích hoạt')} busy={action.isPending}
        description={t('Phiên bản {0} sẽ được dùng theo quyền ngân hàng, không cần duyệt. Đóng góp vào tổ chức là thao tác riêng.', { 0: q.questionVersion })}
        onConfirm={() => action.mutate('activate')} onCancel={() => setConfirm(null)} />}
      {confirm === 'archive' && <ConfirmDialog title={t('Lưu trữ câu hỏi?')} confirmLabel={t('Lưu trữ')} tone="danger" busy={action.isPending}
        description={t('Câu hỏi sẽ không còn dùng được cho đề thi mới. Đề thi đã xuất bản không bị ảnh hưởng.')} onConfirm={() => action.mutate('archive')} onCancel={() => setConfirm(null)} />}
    </div>
  )
}
