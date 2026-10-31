import { useState } from 'react'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Copy, KeyRound, Lock, LockOpen, Search } from 'lucide-react'
import { accountApi, type AdminUser, type ResetLink } from './accountApi'
import { Badge, EmptyState, ErrorState, LoadingState, Notice, PageHeader, Pagination } from '../../components/ui'
import { Dialog } from '../../components/Dialog'
import { useAuthStore } from '../../store/authStore'
import { errorText } from '../../lib/errors'
import { formatDateTime } from '../../lib/format'
import { useDebouncedValue } from '../../lib/hooks'
import { t, useT } from '../../lib/i18n'

const personaLabel = (u: AdminUser) => u.systemPrivilege === 'ADMIN' ? t('Quản trị') : u.persona === 'TEACHER' ? t('Giáo viên') : t('Học sinh / Sinh viên')

/** System admin: find accounts, lock/unlock them and hand out password reset links. */
export default function AdminUsersPage() {
  useT()
  const me = useAuthStore(s => s.user?.id)
  const qc = useQueryClient()
  const [q, setQ] = useState('')
  const [page, setPage] = useState(0)
  const query = useDebouncedValue(q.trim(), 300)
  const [link, setLink] = useState<{ user: AdminUser; link: ResetLink } | null>(null)
  const [error, setError] = useState('')
  const list = useQuery({ queryKey: ['admin-users', query, page], queryFn: () => accountApi.adminUsers(query, page), placeholderData: keepPreviousData })
  const lock = useMutation({
    mutationFn: ({ id, locked }: { id: string; locked: boolean }) => accountApi.adminLock(id, locked),
    onSuccess: () => { setError(''); void qc.invalidateQueries({ queryKey: ['admin-users'] }) }, onError: e => setError(errorText(e)),
  })
  const reset = useMutation({
    mutationFn: (u: AdminUser) => accountApi.adminResetLink(u.id).then(l => ({ user: u, link: l })),
    onSuccess: r => { setError(''); setLink(r) }, onError: e => setError(errorText(e)),
  })
  return (
    <div className="stack-lg">
      <PageHeader title={t('Người dùng')} description={t('Tìm tài khoản, khóa/mở khóa và tạo liên kết đặt lại mật khẩu cho người dùng quên mật khẩu.')} />
      <section className="card" style={{ overflow: 'hidden' }}>
        <div className="card-body stack">
          <label className="search-field">
            <Search size={16} aria-hidden />
            <input className="input" type="search" placeholder={t('Tìm theo email hoặc họ tên')} aria-label={t('Tìm người dùng')} value={q}
              onChange={e => { setQ(e.target.value); setPage(0) }} />
          </label>
          {error && <Notice tone="danger" live>{error}</Notice>}
        </div>
        {list.isLoading ? <div className="card-body"><LoadingState rows={4} /></div>
          : list.isError ? <div className="card-body"><ErrorState error={list.error} onRetry={() => void list.refetch()} /></div>
          : !list.data?.content.length ? <div className="card-body"><EmptyState title={t('Không có người dùng phù hợp')} /></div>
          : <div className="table-wrap"><table className="pt-table table-stack">
              <thead><tr><th scope="col">{t('Người dùng')}</th><th scope="col">{t('Vai trò')}</th><th scope="col">{t('Đăng nhập')}</th><th scope="col">{t('Gần nhất')}</th><th scope="col">{t('Trạng thái')}</th><th scope="col"><span className="sr-only">{t('Thao tác')}</span></th></tr></thead>
              <tbody>{list.data.content.map(u => <tr key={u.id}>
                <td className="stack-title"><div className="stack-sm" style={{ gap: 2 }}><strong className="small">{u.fullName}</strong><span className="xsmall muted">{u.email}</span></div></td>
                <td className="small" data-label={t('Vai trò')}>{personaLabel(u)}</td>
                <td className="xsmall" data-label={t('Đăng nhập')}>{[u.passwordSet ? t('Mật khẩu') : null, u.googleLinked ? 'Google' : null].filter(Boolean).join(' · ') || '–'}</td>
                <td className="xsmall muted" data-label={t('Gần nhất')}>{u.lastLoginAt ? formatDateTime(u.lastLoginAt) : t('Chưa đăng nhập')}</td>
                <td data-label={t('Trạng thái')}>{u.locked ? <Badge tone="danger">{t('Đã khóa')}</Badge> : <Badge tone="success">{t('Hoạt động')}</Badge>}</td>
                <td className="stack-action"><div className="row" style={{ gap: 6, justifyContent: 'flex-end', flexWrap: 'wrap' }}>
                  {u.id !== me && !(u.systemPrivilege === 'ADMIN' && !u.locked) && <button type="button" className="btn btn-ghost btn-sm" disabled={lock.isPending}
                    onClick={() => lock.mutate({ id: u.id, locked: !u.locked })}>
                    {u.locked ? <><LockOpen size={14} aria-hidden /> {t('Mở khóa')}</> : <><Lock size={14} aria-hidden /> {t('Khóa')}</>}</button>}
                  {!u.locked && <button type="button" className="btn btn-ghost btn-sm" disabled={reset.isPending} onClick={() => reset.mutate(u)}>
                    <KeyRound size={14} aria-hidden /> {t('Đặt lại mật khẩu')}</button>}
                </div></td>
              </tr>)}</tbody>
            </table></div>}
        {list.data && list.data.totalPages > 1 && <div className="card-body"><Pagination page={page} totalPages={list.data.totalPages} onChange={setPage} label={t('Trang người dùng')} /></div>}
      </section>
      {link && <ResetLinkDialog user={link.user} link={link.link} onClose={() => setLink(null)} />}
    </div>
  )
}

function ResetLinkDialog({ user, link, onClose }: { user: AdminUser; link: ResetLink; onClose: () => void }) {
  useT()
  const [copied, setCopied] = useState(false)
  async function copy() { try { await navigator.clipboard.writeText(link.url); setCopied(true) } catch { setCopied(false) } }
  return (
    <Dialog title={t('Liên kết đặt lại mật khẩu')} description={`${user.fullName} · ${user.email}`} onClose={onClose}
      footer={<button type="button" className="btn btn-secondary" onClick={onClose}>{t('Đóng')}</button>}>
      <div className="stack">
        <Notice tone={link.emailed ? 'success' : 'info'}>{link.emailed ? t('Đã gửi liên kết tới email của người dùng.') : t('Chưa cấu hình gửi email: hãy gửi liên kết này cho người dùng qua kênh tin cậy.')}</Notice>
        <input className="input mono xsmall" readOnly value={link.url} onFocus={e => e.target.select()} aria-label={t('Liên kết đặt lại mật khẩu')} />
        <div className="spread" style={{ gap: 12, flexWrap: 'wrap' }}>
          <span className="xsmall muted">{t('Hết hạn lúc {0}. Chỉ dùng được một lần.', { 0: formatDateTime(link.expiresAt) })}</span>
          <button type="button" className="btn btn-primary btn-sm" onClick={() => void copy()}><Copy size={14} aria-hidden /> {copied ? t('Đã sao chép') : t('Sao chép')}</button>
        </div>
      </div>
    </Dialog>
  )
}
