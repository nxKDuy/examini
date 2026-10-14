import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Dialog } from '../../components/Dialog'
import { Field, Notice } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { participantApi, type AddResponse, type StaffAttempt } from './participantApi'
import { MAX_BATCH, grantQuantityError, invalidateReady, newRequestId, outcomeSummary, parseBatch, retryInput, OUTCOME_LABEL } from './participantModel'
import { Badge } from '../../components/ui'
import { t, useT } from '../../lib/i18n'

/** Paste many user ids OR e-mails; the per-line result stays on screen and failed lines stay in the box to fix and resend. */
export function AddParticipantsDialog({ examId, onClose, onChanged }: { examId: string; onClose: () => void; onChanged: () => void }) {
  useT()
  const [text, setText] = useState('')
  const [touched, setTouched] = useState(false)
  const [response, setResponse] = useState<AddResponse | null>(null)
  const parsed = parseBatch(text)
  const add = useMutation({
    mutationFn: () => participantApi.add(examId, (parsed as Extract<typeof parsed, { ok: true }>).request),
    onSuccess: r => { setResponse(r); setText(retryInput(r.results)); setTouched(false); onChanged() },
  })
  const summary = response ? outcomeSummary(response.results) : null
  function submit(e: React.FormEvent) { e.preventDefault(); setTouched(true); if (parsed.ok && !add.isPending) add.mutate() }
  return (
    <Dialog title={t('Thêm thí sinh')} size="lg" onClose={onClose} busy={add.isPending}
      description={t('Dán mã người dùng hoặc email của học sinh, mỗi dòng một giá trị, tối đa {0}. Mỗi lần chỉ dùng một loại. Tài khoản phải đã có trên EXAMINI.', { 0: MAX_BATCH })}
      footer={<>
        <button type="button" className="btn btn-secondary" onClick={onClose} disabled={add.isPending}>{response ? t('Đóng') : t('Hủy')}</button>
        <button type="submit" form="pt-add-form" className="btn btn-primary" disabled={add.isPending || text.trim() === ''}>{add.isPending ? t('Đang thêm…') : response ? t('Thêm lại các dòng còn lại') : t('Thêm thí sinh')}</button>
      </>}>
      <form id="pt-add-form" onSubmit={submit} className="stack">
        {add.isError && <Notice tone="danger" live title={t('Chưa thêm được')}>{errorText(add.error)} {t('Danh sách bạn nhập vẫn được giữ lại; thử lại khi sẵn sàng.')}</Notice>}
        <Field label={t('Danh sách')} htmlFor="pt-add-text" hint={parsed.ok ? `${t('{0} dòng hợp lệ ({1})', { 0: parsed.count, 1: parsed.kind === 'ids' ? t('mã người dùng') : 'email' })}${parsed.duplicates ? t(', bỏ {0} dòng trùng', { 0: parsed.duplicates }) : ''}.` : text.trim() ? undefined : t('Ví dụ: một mã UUID hoặc một email trên mỗi dòng.')}
          error={touched && !parsed.ok ? parsed.error : undefined}>
          <textarea id="pt-add-text" className="input mono" rows={8} value={text} spellCheck={false} aria-invalid={touched && !parsed.ok} onChange={e => setText(e.target.value)} data-autofocus />
        </Field>
        {!parsed.ok && text.trim() !== '' && !touched && <p className="xsmall muted">{parsed.error}</p>}
        {response && summary && (
          <div className="stack-sm" role="status">
            <strong>{t('Kết quả:')} {summary.added} {t('thêm/khôi phục,')} {summary.existing} {t('đã có,')} {summary.failed} {t('chưa thêm được.')}</strong>
            <div className="table-wrap"><table className="table">
              <thead><tr><th scope="col">{t('Dòng nhập')}</th><th scope="col">{t('Tài khoản')}</th><th scope="col">{t('Kết quả')}</th></tr></thead>
              <tbody>{response.results.map((r, i) => (
                <tr key={`${r.input}-${i}`}>
                  <td className="mono xsmall" style={{ overflowWrap: 'anywhere' }}>{r.input}</td>
                  <td className="small">{r.displayName ?? (r.userId ? r.userId.slice(0, 8) : '-')}</td>
                  <td><Badge tone={OUTCOME_LABEL[r.outcome].tone}>{OUTCOME_LABEL[r.outcome].text}</Badge></td>
                </tr>
              ))}</tbody>
            </table></div>
            {summary.failed > 0 && <p className="xsmall muted">{t('Các dòng chưa thêm được đã được giữ lại trong ô nhập để bạn sửa và gửi lại.')}</p>}
          </div>
        )}
      </form>
    </Dialog>
  )
}

export function RevokeDialog({ examId, userId, name, onClose, onDone }: { examId: string; userId: string; name: string; onClose: () => void; onDone: () => void }) {
  useT()
  const [reason, setReason] = useState('')
  const revoke = useMutation({ mutationFn: () => participantApi.revoke(examId, userId, reason.trim() || undefined), onSuccess: onDone })
  return (
    <Dialog title={t('Gỡ {0} khỏi kỳ thi?', { 0: name })} onClose={onClose} busy={revoke.isPending}
      description={t('Chặn Join và Start mới. Lịch sử được giữ lại; bài đang làm vẫn được tiếp tục và nộp theo hạn đã cấp. Có thể thêm lại sau.')}
      footer={<>
        <button type="button" className="btn btn-secondary" onClick={onClose} disabled={revoke.isPending}>{t('Hủy')}</button>
        <button type="button" className="btn btn-danger" onClick={() => revoke.mutate()} disabled={revoke.isPending}>{revoke.isPending ? t('Đang gỡ…') : t('Gỡ khỏi kỳ thi')}</button>
      </>}>
      <div className="stack">
        {revoke.isError && <Notice tone="danger" live>{errorText(revoke.error)}</Notice>}
        <Field label={t('Lý do (tùy chọn)')} htmlFor="pt-revoke-reason" hint={t('Học sinh có thể thấy lý do này.')}><textarea id="pt-revoke-reason" className="input" rows={3} value={reason} onChange={e => setReason(e.target.value)} data-autofocus /></Field>
      </div>
    </Dialog>
  )
}

export function GrantDialog({ examId, userId, name, onClose, onDone }: { examId: string; userId: string; name: string; onClose: () => void; onDone: () => void }) {
  useT()
  const qc = useQueryClient()
  const [quantity, setQuantity] = useState('1')
  const [reason, setReason] = useState('')
  const [requestId] = useState(newRequestId) // fixed for this dialog opening: a double click or a retry replays instead of granting twice
  const qError = grantQuantityError(quantity)
  const grant = useMutation({
    mutationFn: () => participantApi.grant(examId, userId, { quantity: Number(quantity), reason: reason.trim(), requestId }),
    onSuccess: async () => { await qc.invalidateQueries({ queryKey: ['participants'] }); onDone() },
  })
  const ready = !qError && reason.trim() !== ''
  return (
    <Dialog title={t('Cấp thêm lượt cho {0}', { 0: name })} onClose={onClose} busy={grant.isPending}
      description={t('Cộng thêm lượt làm bài cho riêng học sinh này. Không đổi giới hạn chung của kỳ thi và không vượt qua lịch hay sức chứa.')}
      footer={<>
        <button type="button" className="btn btn-secondary" onClick={onClose} disabled={grant.isPending}>{t('Hủy')}</button>
        <button type="button" className="btn btn-primary" onClick={() => grant.mutate()} disabled={!ready || grant.isPending}>{grant.isPending ? t('Đang cấp…') : t('Cấp thêm lượt')}</button>
      </>}>
      <div className="stack">
        {grant.isError && <Notice tone="danger" live>{errorText(grant.error)}</Notice>}
        <Field label={t('Số lượt cấp thêm')} htmlFor="pt-grant-qty" required error={qError ?? undefined}><input id="pt-grant-qty" type="number" min={1} max={100} className="input" style={{ maxWidth: 140 }} value={quantity} onChange={e => setQuantity(e.target.value)} data-autofocus /></Field>
        <Field label={t('Lý do')} htmlFor="pt-grant-reason" required hint={t('Học sinh thấy lý do này.')}><textarea id="pt-grant-reason" className="input" rows={3} value={reason} onChange={e => setReason(e.target.value)} /></Field>
      </div>
    </Dialog>
  )
}

export function InvalidateDialog({ attempt, name, onClose, onDone }: { attempt: StaffAttempt; name: string; onClose: () => void; onDone: () => void }) {
  useT()
  const [reason, setReason] = useState('')
  const [typed, setTyped] = useState('')
  const invalidate = useMutation({ mutationFn: () => participantApi.invalidate(attempt.sessionId, reason.trim()), onSuccess: onDone })
  const ready = invalidateReady(reason, typed, attempt.attemptNumber, name)
  return (
    <Dialog title={t('Vô hiệu hóa lượt #{0} của {1}?', { 0: attempt.attemptNumber, 1: name })} onClose={onClose} busy={invalidate.isPending}
      description={t('Hành động này không hoàn tác được.')}
      footer={<>
        <button type="button" className="btn btn-secondary" onClick={onClose} disabled={invalidate.isPending}>{t('Hủy')}</button>
        <button type="button" className="btn btn-danger" onClick={() => invalidate.mutate()} disabled={!ready || invalidate.isPending}>{invalidate.isPending ? t('Đang xử lý…') : t('Vô hiệu hóa lượt')}</button>
      </>}>
      <div className="stack">
        {invalidate.isError && <Notice tone="danger" live>{errorText(invalidate.error)}</Notice>}
        <Notice tone="warning">
          {attempt.status === 'IN_PROGRESS' ? t('Bài đang làm sẽ dừng ngay. ') : t('Bài đã nộp và kết quả chấm được giữ lại nhưng đánh dấu vô hiệu và loại khỏi kết quả hợp lệ. ')}
          {t('Lượt vẫn tính vào số lượt đã dùng và không tự hoàn lại; muốn cho thi lại hãy cấp thêm lượt riêng.')}
        </Notice>
        <Field label={t('Lý do công khai')} htmlFor="pt-inv-reason" required hint={t('Học sinh thấy lý do này. Không nhập ghi chú riêng tư.')}><textarea id="pt-inv-reason" className="input" rows={3} value={reason} onChange={e => setReason(e.target.value)} data-autofocus /></Field>
        <Field label={<>{t('Gõ lại số lượt')} <strong>{attempt.attemptNumber}</strong> {t('hoặc tên')} <strong>{name}</strong> {t('để xác nhận')}</>} htmlFor="pt-inv-typed">
          <input id="pt-inv-typed" className="input" value={typed} autoComplete="off" onChange={e => setTyped(e.target.value)} />
        </Field>
      </div>
    </Dialog>
  )
}
