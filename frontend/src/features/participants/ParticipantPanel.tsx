import { useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Copy, X } from 'lucide-react'
import { participantApi, type ParticipantRow, type StaffAttempt } from './participantApi'
import { ATTEMPT_STATUS_LABEL, SOURCE_LABEL, STATUS_LABEL, attemptSummary, participantName, publicReason, rowAbilities } from './participantModel'
import { GrantDialog, InvalidateDialog, RevokeDialog } from './ParticipantDialogs'
import { useModalFocus } from '../../components/useModalFocus'
import { Badge, ErrorState, LoadingState, Notice } from '../../components/ui'
import { formatDateTime } from '../../lib/format'

/** Side drawer on desktop, bottom sheet on narrow screens. All writes are backend calls; this panel only offers what the viewer's role allows. */
export default function ParticipantPanel({ examId, row, manage, narrow, onClose, onChanged }: {
  examId: string; row: ParticipantRow; manage: boolean; narrow: boolean; onClose: () => void; onChanged: () => void
}) {
  const qc = useQueryClient()
  const panelRef = useRef<HTMLElement>(null)
  const headingRef = useRef<HTMLHeadingElement>(null)
  const name = participantName(row)
  const ability = rowAbilities(manage, row)
  const [dialog, setDialog] = useState<'revoke' | 'grant' | null>(null)
  const [invalidating, setInvalidating] = useState<StaffAttempt | null>(null)
  const [flash, setFlash] = useState<string | null>(null)
  useModalFocus(panelRef, { trap: narrow, escape: true, onEscape: onClose, initialFocus: () => headingRef.current })

  const attempts = useQuery({ queryKey: ['participants', 'attempts', examId, row.userId], queryFn: () => participantApi.attempts(examId, row.userId) })
  const grants = useQuery({ queryKey: ['participants', 'grants', examId, row.userId], queryFn: () => participantApi.grants(examId, row.userId), enabled: manage })
  const refresh = async () => { await qc.invalidateQueries({ queryKey: ['participants'] }); onChanged() }

  return (
    <>
      {narrow && <button type="button" className="pt-sheet-backdrop" aria-label="Đóng chi tiết thí sinh" onClick={onClose} tabIndex={-1} />}
      <aside ref={panelRef} className="pt-panel" role={narrow ? 'dialog' : 'complementary'} aria-modal={narrow || undefined} aria-label="Chi tiết thí sinh">
        <div className="pt-panel-head">
          <div style={{ minWidth: 0 }}>
            <h2 ref={headingRef} tabIndex={-1} style={{ fontSize: '1.2rem', overflowWrap: 'anywhere' }}>{name.text}</h2>
            <p className="xsmall muted">{name.fallback ? 'Chưa có tên hiển thị. ' : ''}Mã <span className="pt-id">{row.userId}</span>{' '}
              <button type="button" className="btn btn-ghost btn-sm" aria-label="Sao chép mã người dùng" onClick={() => { void navigator.clipboard?.writeText(row.userId).catch(() => undefined) }}><Copy size={12} aria-hidden /></button></p>
          </div>
          <button type="button" className="btn btn-ghost btn-icon" aria-label="Đóng" onClick={onClose}><X size={18} /></button>
        </div>
        <div className="pt-panel-body">
          {flash && <Notice tone="success" live>{flash}</Notice>}
          <div className="row" style={{ gap: 6 }}>
            <Badge tone={row.status === 'ACTIVE' ? 'success' : 'muted'}>{STATUS_LABEL[row.status] ?? row.status}</Badge>
            <Badge tone="info">{SOURCE_LABEL[row.source] ?? row.source}</Badge>
          </div>
          {row.status === 'REVOKED' && <Notice tone="info">Đã gỡ {formatDateTime(row.statusChangedAt, '-')}{row.revocationReason ? `. Lý do: ${row.revocationReason}` : '.'} Không thể Join hoặc Start mới.</Notice>}
          <dl className="small stack-sm" style={{ margin: 0 }}>
            <div><dt className="xsmall muted">Tham gia</dt><dd style={{ margin: 0 }}>{formatDateTime(row.joinedAt, '-')}</dd></div>
            <div><dt className="xsmall muted">Lượt làm bài</dt><dd style={{ margin: 0 }}>Đã dùng {attemptSummary(row)} · còn {row.attempts?.remaining ?? row.effectiveAttemptLimit}{row.attempts?.active ? ' · đang làm một lượt' : ''}</dd></div>
            <div><dt className="xsmall muted">Giới hạn</dt><dd style={{ margin: 0 }}>{row.attemptLimit} của kỳ thi{row.extraAttempts > 0 ? ` + ${row.extraAttempts} cấp thêm = ${row.effectiveAttemptLimit}` : ''}</dd></div>
          </dl>

          <div className="stack-sm">
            <span className="field-label">Các lượt làm bài</span>
            {attempts.isLoading ? <LoadingState rows={2} /> : attempts.isError ? <ErrorState error={attempts.error} onRetry={() => void attempts.refetch()} />
              : (attempts.data?.length ?? 0) === 0 ? <p className="small muted">Chưa có lượt nào.</p>
              : <ul className="pt-list">{attempts.data!.map(a => (
                  <li key={a.sessionId} className="pt-attempt" data-invalid={a.invalidated}>
                    <span className="spread"><strong>Lượt #{a.attemptNumber}</strong>
                      <span className="row" style={{ gap: 6 }}><Badge tone={a.status === 'IN_PROGRESS' ? 'info' : 'neutral'}>{ATTEMPT_STATUS_LABEL[a.status] ?? a.status}</Badge>{a.invalidated && <Badge tone="danger">Đã vô hiệu</Badge>}</span></span>
                    <span className="xsmall muted">Bắt đầu {formatDateTime(a.startedAt, '-')}{a.endedAt ? ` · kết thúc ${formatDateTime(a.endedAt, '-')}` : ''}</span>
                    {a.invalidated && <span className="small">Lý do công khai: {publicReason(a) ?? 'không ghi'}{a.invalidatedAt ? ` (${formatDateTime(a.invalidatedAt, '-')})` : ''}</span>}
                    <span className="row" style={{ gap: 8 }}>
                      {a.status !== 'IN_PROGRESS' && <Link className="btn btn-secondary btn-sm" to={`/grading?examId=${examId}`}>Mở hàng chờ chấm</Link>}
                      {ability.invalidate && !a.invalidated && <button type="button" className="btn btn-danger-ghost btn-sm" onClick={() => { setFlash(null); setInvalidating(a) }}>Vô hiệu hóa lượt</button>}
                    </span>
                  </li>
                ))}</ul>}
          </div>

          {manage && (
            <div className="stack-sm">
              <span className="field-label">Lịch sử cấp thêm lượt</span>
              {grants.isLoading ? <LoadingState rows={1} /> : grants.isError ? <ErrorState error={grants.error} onRetry={() => void grants.refetch()} />
                : (grants.data?.length ?? 0) === 0 ? <p className="small muted">Chưa cấp thêm lượt nào.</p>
                : <ul className="pt-list">{grants.data!.map(g => <li key={g.id} className="small">+{g.quantity} lượt · {formatDateTime(g.createdAt, '-')}{g.reason ? ` · ${g.reason}` : ''}</li>)}</ul>}
            </div>
          )}

          {(ability.grant || ability.revoke) && (
            <div className="stack-sm">
              {ability.grant && <button type="button" className="btn btn-primary" onClick={() => { setFlash(null); setDialog('grant') }}>Cấp thêm lượt</button>}
              {ability.revoke && <button type="button" className="btn btn-danger-ghost" onClick={() => { setFlash(null); setDialog('revoke') }}>Gỡ khỏi kỳ thi</button>}
            </div>
          )}
          {!manage && <p className="xsmall muted">Giám thị chỉ xem danh sách và trạng thái; không thể thêm, gỡ, cấp lượt hay vô hiệu hóa.</p>}
        </div>
      </aside>
      {dialog === 'grant' && <GrantDialog examId={examId} userId={row.userId} name={name.text} onClose={() => setDialog(null)} onDone={() => { setDialog(null); setFlash('Đã cấp thêm lượt.'); void refresh() }} />}
      {dialog === 'revoke' && <RevokeDialog examId={examId} userId={row.userId} name={name.text} onClose={() => setDialog(null)} onDone={() => { setDialog(null); setFlash('Đã gỡ khỏi kỳ thi.'); void refresh() }} />}
      {invalidating && <InvalidateDialog attempt={invalidating} name={name.text} onClose={() => setInvalidating(null)} onDone={() => { setInvalidating(null); setFlash('Đã vô hiệu hóa lượt làm bài.'); void refresh() }} />}
    </>
  )
}
