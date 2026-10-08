import { useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import './organizations.css'
import OrgBankTab from '../banks/OrgBankTab'
import AuditTab from './AuditTab'
import InvitationsTab from './InvitationsTab'
import MembersTab from './MembersTab'
import SettingsTab from './SettingsTab'
import { orgApi } from './orgApi'
import { ROLE_LABEL, TAB_LABEL, isArchived, isOwner, parseTab, tabsForOrg } from './orgModel'
import { ConfirmDialog } from '../../components/Dialog'
import { Badge, ErrorState, LoadingState, Notice, PageHeader } from '../../components/ui'
import { errorInfo, errorText } from '../../lib/errors'

/** One organization. Tabs and buttons come from the caller's real membership (`me`), not from persona or system roles. */
export default function OrganizationDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const [params, setParams] = useSearchParams()
  const [leaving, setLeaving] = useState(false)
  const [flash, setFlash] = useState<string | null>(null)
  const org = useQuery({ queryKey: ['organizations', 'detail', id], queryFn: () => orgApi.get(id!), retry: (n, e) => errorInfo(e).status >= 500 && n < 1 })
  const leave = useMutation({
    mutationFn: () => orgApi.leave(id!),
    onSuccess: async () => { await qc.invalidateQueries({ queryKey: ['organizations'] }); navigate('/organizations', { replace: true }) },
    onError: e => { setLeaving(false); setFlash(errorText(e)) },
  })

  if (org.isLoading) return <LoadingState rows={4} />
  if (org.isError || !org.data) {
    const status = errorInfo(org.error).status
    return (
      <div className="stack">
        <PageHeader title="Tổ chức" crumbs={[{ label: 'Tổ chức', to: '/organizations' }, { label: 'Chi tiết' }]} />
        {status === 403 || status === 404
          ? <Notice tone="warning" title="Không mở được tổ chức này" action={<Link to="/organizations" className="btn btn-secondary btn-sm">Về danh sách</Link>}>Tổ chức không tồn tại hoặc bạn không (còn) là thành viên.</Notice>
          : <section className="card"><ErrorState error={org.error} onRetry={() => void org.refetch()} /></section>}
      </div>
    )
  }
  const o = org.data
  const role = o.me?.role
  const tabs = tabsForOrg(o)
  const tab = parseTab(params.get('tab'), tabs)
  const setTab = (t: string) => { const next = new URLSearchParams(params); if (t === 'members') next.delete('tab'); else next.set('tab', t); setParams(next, { replace: true }) }

  return (
    <div className="stack">
      <PageHeader title={o.name} crumbs={[{ label: 'Tổ chức', to: '/organizations' }, { label: o.name }]}
        description={<span className="row" style={{ gap: 6 }}>{role && <Badge tone="info">{ROLE_LABEL[role]}</Badge>}{isArchived(o) ? <Badge tone="muted">Đã lưu trữ</Badge> : <Badge tone="success">Đang hoạt động</Badge>}{typeof o.activeMemberCount === 'number' && <span className="small muted">{o.activeMemberCount} thành viên</span>}</span>}
        actions={role && (isOwner(role)
          ? !isArchived(o) && <button type="button" className="btn btn-secondary" onClick={() => setTab('settings')}>Chuyển quyền sở hữu</button>
          : <button type="button" className="btn btn-secondary" onClick={() => { setFlash(null); setLeaving(true) }}><ArrowLeft size={16} aria-hidden /> Rời tổ chức</button>)} />
      {o.description && <p className="small prewrap" style={{ maxWidth: '70ch' }}>{o.description}</p>}
      {isArchived(o) && <Notice tone="info" title="Tổ chức đã lưu trữ">Chỉ xem lịch sử: không mời, không đổi quyền. Không có thao tác khôi phục.</Notice>}
      {flash && <Notice tone="danger" live>{flash}</Notice>}
      <div className="tabs" role="tablist" aria-label="Mục của tổ chức" style={{ border: '1px solid #000' }}>
        {tabs.map(t => <button key={t} type="button" role="tab" id={`org-tab-${t}`} aria-selected={tab === t} aria-controls="org-tabpanel" className="tab" onClick={() => setTab(t)}>{TAB_LABEL[t]}</button>)}
      </div>
      <div role="tabpanel" id="org-tabpanel" aria-labelledby={`org-tab-${tab}`}>
        {tab === 'bank' && <OrgBankTab key={o.id} org={o} />}
        {tab === 'members' && <MembersTab org={o} />}
        {tab === 'invitations' && <InvitationsTab org={o} />}
        {tab === 'audit' && <AuditTab org={o} />}
        {tab === 'settings' && <SettingsTab org={o} onLeft={() => void org.refetch()} />}
      </div>
      {leaving && <ConfirmDialog title="Rời khỏi tổ chức?" tone="danger" confirmLabel="Rời tổ chức" busy={leave.isPending} onConfirm={() => leave.mutate()} onCancel={() => setLeaving(false)}
        description={`Bạn sẽ mất quyền trong ${o.name} ngay lập tức. Đóng góp đã duyệt được giữ lại. Muốn quay lại cần được mời lại.`} />}
    </div>
  )
}
