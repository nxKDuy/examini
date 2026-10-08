import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Building2, Plus } from 'lucide-react'
import './organizations.css'
import InvitationBlock from './MyInvitations'
import { orgApi } from './orgApi'
import { ROLE_LABEL, isArchived } from './orgModel'
import { Dialog } from '../../components/Dialog'
import { Badge, EmptyState, ErrorState, Field, LoadingState, Notice, PageHeader, Pagination } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { hasPersona } from '../../store/authStore'
import { useAuthStore } from '../../store/authStore'

const STATUS_TABS = [{ value: 'ACTIVE', label: 'Đang hoạt động' }, { value: 'ARCHIVED', label: 'Đã lưu trữ' }]

/** My organizations. Creating one depends on the canonical Teacher persona only; everything inside an organization depends on membership. */
export default function OrganizationsPage() {
  const user = useAuthStore(s => s.user)
  const canCreate = hasPersona(user, 'TEACHER')
  const [params, setParams] = useSearchParams()
  const status = params.get('status') === 'ARCHIVED' ? 'ARCHIVED' : 'ACTIVE'
  const q = params.get('q') ?? ''
  const [search, setSearch] = useState(q)
  const [page, setPage] = useState(0)
  const [creating, setCreating] = useState(false)

  useEffect(() => { const t = setTimeout(() => { if (search !== q) { const next = new URLSearchParams(params); if (search.trim()) next.set('q', search.trim()); else next.delete('q'); setParams(next, { replace: true }); setPage(0) } }, 300); return () => clearTimeout(t) }, [search, q, params, setParams])
  const list = useQuery({ queryKey: ['organizations', 'list', status, q, page], queryFn: () => orgApi.list({ status, q: q || undefined, page }), placeholderData: prev => prev })
  const setStatus = (value: string) => { const next = new URLSearchParams(params); if (value === 'ACTIVE') next.delete('status'); else next.set('status', value); setParams(next, { replace: true }); setPage(0) }

  return (
    <div className="stack">
      <PageHeader title="Tổ chức" description="Nhóm giáo viên và người học làm việc chung. Quyền trong tổ chức là quyền riêng, khác quyền quản trị hệ thống."
        actions={canCreate && <button type="button" className="btn btn-primary" onClick={() => setCreating(true)}><Plus size={16} aria-hidden /> Tạo tổ chức</button>} />
      <InvitationBlock focusOnMount={params.get('tab') === 'invitations'} />
      <section className="card" aria-label="Danh sách tổ chức">
        <div className="tabs" role="tablist" aria-label="Trạng thái tổ chức">
          {STATUS_TABS.map(t => <button key={t.value} type="button" role="tab" className="tab" aria-selected={status === t.value} onClick={() => setStatus(t.value)}>{t.label}</button>)}
        </div>
        <div className="card-body" style={{ paddingBottom: 12 }}>
          <Field label="Tìm tổ chức theo tên" htmlFor="org-search"><input id="org-search" className="input" type="search" value={search} onChange={e => setSearch(e.target.value)} maxLength={120} /></Field>
        </div>
        {list.isLoading ? <LoadingState rows={3} />
          : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} />
          : (list.data?.items.length ?? 0) === 0
            ? <EmptyState icon={<Building2 size={30} />} title={q ? 'Không có tổ chức khớp' : status === 'ARCHIVED' ? 'Chưa có tổ chức nào đã lưu trữ' : 'Bạn chưa thuộc tổ chức nào'}
                description={q ? 'Thử từ khóa khác.' : canCreate ? 'Tạo tổ chức mới hoặc chờ lời mời từ chủ sở hữu.' : 'Khi được mời vào tổ chức, lời mời sẽ hiện ở đầu trang này.'} />
            : <div className="org-grid">
                {list.data!.items.map(o => (
                  <Link key={o.id} to={`/organizations/${o.id}`} className="org-card">
                    <h3>{o.name}</h3>
                    {o.description && <p>{o.description}</p>}
                    <span className="row" style={{ gap: 6 }}>
                      {o.me && <Badge tone="info">{ROLE_LABEL[o.me.role]}</Badge>}
                      {isArchived(o) ? <Badge tone="muted">Đã lưu trữ</Badge> : <Badge tone="success">Đang hoạt động</Badge>}
                      {typeof o.activeMemberCount === 'number' && <span className="xsmall muted">{o.activeMemberCount} thành viên</span>}
                    </span>
                  </Link>
                ))}
              </div>}
        {list.data && <Pagination label="Phân trang tổ chức" page={page} totalPages={list.data.totalPages} onChange={setPage} />}
      </section>
      {creating && <CreateDialog onClose={() => setCreating(false)} />}
    </div>
  )
}

function CreateDialog({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const create = useMutation({
    mutationFn: () => orgApi.create({ name: name.trim(), ...(description.trim() ? { description: description.trim() } : {}) }),
    onSuccess: async o => { await qc.invalidateQueries({ queryKey: ['organizations'] }); navigate(`/organizations/${o.id}`) },
  })
  const invalid = name.trim() === '' || name.trim().length > 120 || description.length > 1000
  function submit(e: FormEvent) { e.preventDefault(); if (!invalid && !create.isPending) create.mutate() }
  return (
    <Dialog title="Tạo tổ chức" description="Bạn sẽ là Chủ sở hữu của tổ chức này." onClose={onClose} busy={create.isPending}
      footer={<>
        <button type="button" className="btn btn-secondary" onClick={onClose} disabled={create.isPending}>Hủy</button>
        <button type="submit" form="org-create" className="btn btn-primary" disabled={invalid || create.isPending}>{create.isPending ? 'Đang tạo…' : 'Tạo tổ chức'}</button>
      </>}>
      <form id="org-create" onSubmit={submit} className="stack">
        {create.isError && <Notice tone="danger" live>{errorText(create.error)}</Notice>}
        <Field label="Tên tổ chức" htmlFor="org-name" required hint="Tối đa 120 ký tự."><input id="org-name" className="input" value={name} maxLength={120} onChange={e => setName(e.target.value)} data-autofocus /></Field>
        <Field label="Mô tả (tùy chọn)" htmlFor="org-desc" hint="Tối đa 1000 ký tự."><textarea id="org-desc" className="input" rows={3} value={description} maxLength={1000} onChange={e => setDescription(e.target.value)} /></Field>
      </form>
    </Dialog>
  )
}
