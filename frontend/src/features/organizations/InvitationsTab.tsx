import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Mail, UserPlus } from 'lucide-react'
import { orgApi, type Invitation, type Organization } from './orgApi'
import { INVITATION_STATUS_LABEL, ROLE_LABEL, canInvite, expiresText, recipientName } from './orgModel'
import InviteDialog from './InviteDialog'
import { ConfirmDialog } from '../../components/Dialog'
import { Badge, EmptyState, ErrorState, LoadingState, Notice, Pagination } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { formatDateTime } from '../../lib/format'
import { useNow } from '../../lib/hooks'
import { t, useT, i18nMap } from '../../lib/i18n'

const FILTERS = i18nMap([{ value: 'PENDING', label: 'Đang chờ' }, { value: '', label: 'Tất cả' }, { value: 'ACCEPTED', label: 'Đã chấp nhận' }, { value: 'DECLINED', label: 'Đã từ chối' }, { value: 'REVOKED', label: 'Đã thu hồi' }, { value: 'EXPIRED', label: 'Hết hạn' }])
const TONE: Record<string, 'info' | 'success' | 'muted' | 'warning'> = { PENDING: 'info', ACCEPTED: 'success', DECLINED: 'muted', REVOKED: 'muted', EXPIRED: 'warning' }

/** Invitations sent by this organization (OWNER/ADMIN only). ADMIN may only revoke MEMBER invites; the server enforces it too. */
export default function InvitationsTab({ org }: { org: Organization }) {
  useT()
  const qc = useQueryClient()
  const nowMs = useNow(0, 60_000)
  const [status, setStatus] = useState('PENDING')
  const [page, setPage] = useState(0)
  const [inviting, setInviting] = useState(false)
  const [revoking, setRevoking] = useState<Invitation | null>(null)
  const [flash, setFlash] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null)
  const list = useQuery({ queryKey: ['organizations', 'invitations', org.id, status, page], queryFn: () => orgApi.invitations(org.id, { status: status || undefined, page }), placeholderData: prev => prev })
  const revoke = useMutation({
    mutationFn: (i: Invitation) => orgApi.revokeInvitation(org.id, i.id),
    onSuccess: async () => { setRevoking(null); setFlash({ tone: 'success', text: t('Đã thu hồi lời mời.') }); await qc.invalidateQueries({ queryKey: ['organizations', 'invitations', org.id] }) },
    onError: async e => { setRevoking(null); setFlash({ tone: 'danger', text: errorText(e) }); await qc.invalidateQueries({ queryKey: ['organizations', 'invitations', org.id] }) },
  })
  const archived = org.status === 'ARCHIVED'
  const mayRevoke = (i: Invitation) => !archived && i.status === 'PENDING' && (org.me?.role === 'OWNER' || i.role === 'MEMBER')

  return (
    <div className="stack">
      {flash && <Notice tone={flash.tone} live>{flash.text}</Notice>}
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <label className="row small" style={{ gap: 6 }}>{t('Trạng thái')}
          <select className="input" value={status} onChange={e => { setStatus(e.target.value); setPage(0) }} style={{ width: 'auto' }}>
            {FILTERS.map(f => <option key={f.value} value={f.value}>{f.label}</option>)}
          </select>
        </label>
        {canInvite(org) && <button type="button" className="btn btn-primary" onClick={() => { setFlash(null); setInviting(true) }}><UserPlus size={16} aria-hidden /> {t('Mời thành viên')}</button>}
      </div>
      <section className="card" aria-label={t('Danh sách lời mời')} style={{ overflow: 'hidden' }}>
        {list.isLoading ? <LoadingState rows={3} />
          : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} />
          : (list.data?.items.length ?? 0) === 0 ? <EmptyState icon={<Mail size={28} />} title={t('Không có lời mời')} description={status === 'PENDING' ? t('Không có lời mời nào đang chờ.') : t('Không có lời mời khớp bộ lọc.')} />
          : list.data!.items.map(i => {
            const n = recipientName(i)
            return (
              <div key={i.id} className="org-invite">
                <div className="stack-sm">
                  <strong>{n.text}</strong>
                  <span className="row small muted" style={{ gap: 8 }}>
                    <Badge tone="info">{ROLE_LABEL[i.role]}</Badge>
                    <Badge tone={TONE[i.status] ?? 'muted'}>{INVITATION_STATUS_LABEL[i.status] ?? i.status}</Badge>
                    <span>{t('Gửi')} {formatDateTime(i.createdAt, '-')}</span>
                    {i.status === 'PENDING' && <span>{expiresText(i.expiresAt, nowMs)}</span>}
                    {i.respondedAt && <span>{t('Phản hồi')} {formatDateTime(i.respondedAt, '-')}</span>}
                  </span>
                </div>
                {mayRevoke(i) && <div className="org-invite-actions"><button type="button" className="btn btn-secondary" onClick={() => { setFlash(null); setRevoking(i) }}>{t('Thu hồi')}</button></div>}
              </div>
            )
          })}
        {list.data && <Pagination label={t('Phân trang lời mời')} page={page} totalPages={list.data.totalPages} onChange={setPage} />}
      </section>
      {inviting && <InviteDialog org={org} onClose={() => setInviting(false)} onDone={created => { setInviting(false); setFlash({ tone: 'success', text: created ? t('Đã gửi lời mời. Lời mời hết hạn sau 7 ngày.') : t('Người này đã có lời mời đang chờ với cùng vai trò; không gửi thêm.') }) }} />}
      {revoking && <ConfirmDialog title={t('Thu hồi lời mời?')} confirmLabel={t('Thu hồi')} tone="danger" busy={revoke.isPending} onConfirm={() => revoke.mutate(revoking)} onCancel={() => setRevoking(null)}
        description={t('Lời mời cho {0} sẽ không còn dùng được. Bạn có thể mời lại sau.', { 0: recipientName(revoking).text })} />}
    </div>
  )
}
