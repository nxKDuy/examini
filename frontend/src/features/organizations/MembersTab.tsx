import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { UserPlus, Users } from 'lucide-react'
import { orgApi, type Member, type Organization } from './orgApi'
import { MEMBER_STATUS_LABEL, ROLE_LABEL, canInvite, isManager, memberName } from './orgModel'
import InviteDialog from './InviteDialog'
import MemberPanel from './MemberPanel'
import { Badge, EmptyState, ErrorState, LoadingState, Notice, Pagination } from '../../components/ui'
import { formatDateTime } from '../../lib/format'
import { useIsNarrow } from '../../lib/hooks'
import { t, useT, i18nMap } from '../../lib/i18n'

const STATUS_OPTIONS = i18nMap([{ value: 'ACTIVE', label: 'Đang hoạt động' }, { value: 'REVOKED', label: 'Lịch sử: đã bị gỡ' }, { value: 'LEFT', label: 'Lịch sử: đã rời' }, { value: 'ALL', label: 'Lịch sử: tất cả' }])

/** Roster: server-side role/status filters and pages (the API has no name search). Table on desktop, cards on phones. */
export default function MembersTab({ org }: { org: Organization }) {
  useT()
  const manager = isManager(org.me?.role)
  const [status, setStatus] = useState('ACTIVE')
  const [role, setRole] = useState('')
  const [page, setPage] = useState(0)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [inviting, setInviting] = useState(false)
  const [flash, setFlash] = useState<string | null>(null)
  const narrowList = useIsNarrow(899), narrowPanel = useIsNarrow(1100)

  const list = useQuery({ queryKey: ['organizations', 'members', org.id, status, role, page], queryFn: () => orgApi.members(org.id, { status, role: role || undefined, page }), placeholderData: prev => prev })
  const items = list.data?.items ?? []
  const selected = items.find(m => m.membershipId === selectedId) ?? null
  const reload = async () => (await list.refetch()).data?.items.find(m => m.membershipId === selectedId)
  const changeFilter = (fn: () => void) => { fn(); setPage(0); setSelectedId(null) }

  return (
    <div className="stack">
      {flash && <Notice tone="success" live>{flash}</Notice>}
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <div className="row">
          <label className="row small" style={{ gap: 6 }}>{t('Vai trò')}
            <select className="input" value={role} onChange={e => changeFilter(() => setRole(e.target.value))} style={{ width: 'auto' }}>
              <option value="">{t('Tất cả')}</option><option value="OWNER">{ROLE_LABEL.OWNER}</option><option value="ADMIN">{ROLE_LABEL.ADMIN}</option><option value="MEMBER">{ROLE_LABEL.MEMBER}</option>
            </select>
          </label>
          {manager && (
            <label className="row small" style={{ gap: 6 }}>{t('Trạng thái')}
              <select className="input" value={status} onChange={e => changeFilter(() => setStatus(e.target.value))} style={{ width: 'auto' }}>
                {STATUS_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
            </label>
          )}
        </div>
        {canInvite(org) && <button type="button" className="btn btn-primary" onClick={() => { setFlash(null); setInviting(true) }}><UserPlus size={16} aria-hidden /> {t('Mời thành viên')}</button>}
      </div>

      <div className="org-layout" data-panel={selected ? 'open' : 'closed'}>
        <section className="card" aria-label={t('Danh sách thành viên')} style={{ overflow: 'hidden', minWidth: 0 }}>
          {list.isLoading ? <LoadingState rows={4} />
            : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} />
            : items.length === 0 ? <EmptyState icon={<Users size={28} />} title={t('Không có thành viên')} description={role || status !== 'ACTIVE' ? t('Không có ai khớp bộ lọc này.') : t('Mời thành viên để bắt đầu.')} />
            : narrowList ? items.map(m => <MemberCard key={m.membershipId} m={m} active={selectedId === m.membershipId} onOpen={() => setSelectedId(m.membershipId)} />)
            : <table className="org-table">
                <thead><tr><th scope="col">{t('Thành viên')}</th><th scope="col">{t('Vai trò')}</th><th scope="col">{t('Quyền duyệt')}</th><th scope="col">Tham gia</th><th scope="col">{t('Trạng thái')}</th></tr></thead>
                <tbody>
                  {items.map(m => {
                    const n = memberName(m)
                    return (
                      <tr key={m.membershipId} aria-selected={selectedId === m.membershipId}>
                        <td><button type="button" className="org-row-btn" onClick={() => setSelectedId(m.membershipId)} aria-label={t('Xem chi tiết {0}', { 0: n.text })}>{n.text}</button>{n.fallback && <span className="xsmall muted"> {t('(chưa có tên)')}</span>}{org.me?.membershipId === m.membershipId && <span className="xsmall muted"> {t('· bạn')}</span>}</td>
                        <td>{ROLE_LABEL[m.role]}</td>
                        <td>{m.role !== 'MEMBER' ? t('Có (theo vai trò)') : m.canReview ? t('Có') : t('Không')}</td>
                        <td>{formatDateTime(m.joinedAt, '-')}</td>
                        <td><Badge tone={m.status === 'ACTIVE' ? 'success' : 'muted'}>{MEMBER_STATUS_LABEL[m.status] ?? m.status}</Badge></td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>}
          {list.data && <Pagination label={t('Phân trang thành viên')} page={page} totalPages={list.data.totalPages} onChange={p => { setPage(p); setSelectedId(null) }} />}
        </section>
        {selected && <MemberPanel key={selected.membershipId} org={org} member={selected} narrow={narrowPanel} onClose={() => setSelectedId(null)} onReload={reload} />}
      </div>
      {inviting && <InviteDialog org={org} onClose={() => setInviting(false)} onDone={created => { setInviting(false); setFlash(created ? t('Đã gửi lời mời. Lời mời hết hạn sau 7 ngày.') : t('Người này đã có lời mời đang chờ với cùng vai trò; không gửi thêm.')) }} />}
    </div>
  )
}

function MemberCard({ m, active, onOpen }: { m: Member; active: boolean; onOpen: () => void }) {
  useT()
  const n = memberName(m)
  return (
    <button type="button" className="org-member-card" aria-current={active} onClick={onOpen}>
      <span className="spread"><strong>{n.text}</strong><Badge tone="info">{ROLE_LABEL[m.role]}</Badge></span>
      <span className="row xsmall muted" style={{ gap: 8 }}>
        <span>{MEMBER_STATUS_LABEL[m.status] ?? m.status}</span>
        <span>{m.role !== 'MEMBER' ? t('Duyệt: theo vai trò') : m.canReview ? t('Được duyệt') : t('Không duyệt')}</span>
        <span>Tham gia {formatDateTime(m.joinedAt, '-')}</span>
      </span>
    </button>
  )
}
