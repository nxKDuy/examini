import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Check, X } from 'lucide-react'
import './organizations.css'
import { orgApi, type Invitation } from './orgApi'
import { MY_INVITATIONS_KEY, useMyInvitations } from './useMyInvitations'
import { ROLE_LABEL, expiresText } from './orgModel'
import { Badge, ErrorState, Notice } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { useNow } from '../../lib/hooks'
import { t, useT } from '../../lib/i18n'

/** Dashboard notice: a count and a link, nothing to decide here. */
export function InvitationNotice() {
  useT()
  const q = useMyInvitations()
  const n = q.data?.totalElements ?? 0
  if (n === 0) return null
  return (
    <Notice tone="info" title={t('Bạn có {0} lời mời tham gia tổ chức', { 0: n })} action={<Link to="/organizations?tab=invitations" className="btn btn-secondary btn-sm">{t('Xem lời mời')}</Link>}>
      {t('Chấp nhận hoặc từ chối ở mục Tổ chức. Lời mời hết hạn sau 7 ngày.')}
    </Notice>
  )
}

/** The "pending invitations" block at the top of /organizations, with large Accept / Decline buttons. */
export default function InvitationBlock({ focusOnMount }: { focusOnMount?: boolean }) {
  useT()
  const q = useMyInvitations()
  const qc = useQueryClient()
  const navigate = useNavigate()
  const nowMs = useNow(0, 60_000)
  const headingRef = useRef<HTMLHeadingElement>(null)
  const [flash, setFlash] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null)
  useEffect(() => { if (focusOnMount && q.data) headingRef.current?.focus() }, [focusOnMount, q.data])

  const refresh = () => Promise.all([qc.invalidateQueries({ queryKey: [MY_INVITATIONS_KEY] }), qc.invalidateQueries({ queryKey: ['organizations'] })])
  const accept = useMutation({
    mutationFn: (i: Invitation) => orgApi.accept(i.id).then(() => i),
    onSuccess: async i => { await refresh(); navigate(`/organizations/${i.organizationId}`) },
    onError: async e => { setFlash({ tone: 'danger', text: errorText(e) }); await refresh() },
  })
  const decline = useMutation({
    mutationFn: (i: Invitation) => orgApi.decline(i.id).then(() => i),
    onSuccess: async i => { setFlash({ tone: 'success', text: t('Đã từ chối lời mời vào {0}.', { 0: i.organizationName ?? 'tổ chức' }) }); await refresh() },
    onError: async e => { setFlash({ tone: 'danger', text: errorText(e) }); await refresh() },
  })
  const busy = accept.isPending || decline.isPending
  const items = q.data?.items ?? []

  if (q.isError) return <section className="card"><ErrorState error={q.error} onRetry={() => void q.refetch()} /></section>
  if (!flash && items.length === 0) return null
  return (
    <section className="card" id="org-invitations" aria-labelledby="org-invitations-h">
      <div className="card-header"><h2 id="org-invitations-h" ref={headingRef} tabIndex={-1} style={{ outline: 'none' }}>{t('Lời mời đang chờ')}{items.length ? ` (${items.length})` : ''}</h2></div>
      {flash && <div className="card-body" style={{ paddingBottom: 0 }}><Notice tone={flash.tone} live>{flash.text}</Notice></div>}
      {items.map(i => (
        <div key={i.id} className="org-invite">
          <div className="stack-sm">
            <strong style={{ fontSize: '1.1rem' }}>{i.organizationName ?? t('Tổ chức')}</strong>
            <span className="row small muted" style={{ gap: 8 }}>
              <Badge tone="info">{t('Vai trò')} {ROLE_LABEL[i.role]}</Badge>
              <span>{expiresText(i.expiresAt, nowMs)}</span>
            </span>
          </div>
          <div className="org-invite-actions">
            <button type="button" className="btn btn-primary" disabled={busy} onClick={() => { setFlash(null); accept.mutate(i) }}><Check size={16} aria-hidden /> {t('Chấp nhận')}</button>
            <button type="button" className="btn btn-secondary" disabled={busy} onClick={() => { setFlash(null); decline.mutate(i) }}><X size={16} aria-hidden /> {t('Từ chối')}</button>
          </div>
        </div>
      ))}
    </section>
  )
}
