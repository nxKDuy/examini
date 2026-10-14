import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Bell } from 'lucide-react'
import './notifications.css'
import { notificationApi, type AppNotification } from './notificationApi'
import { notificationTarget, unreadLabel } from './notificationModel'
import NotificationItem from './NotificationItem'
import { useT } from '../../lib/i18n'
import { PushCard } from './PushControls'
import { audienceOf } from '../../components/layout/navigation'
import { EmptyState, ErrorState, LoadingState } from '../../components/ui'
import { useAuthStore } from '../../store/authStore'
import { useNow } from '../../lib/hooks'

/** Topbar bell: unread badge (refreshed every 60s and when the tab regains focus) and the 10 latest. No toasts. */
export default function NotificationBell() {
  const t = useT()
  const user = useAuthStore(s => s.user)
  const student = audienceOf(user) === 'STUDENT'
  const qc = useQueryClient()
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const wrap = useRef<HTMLDivElement>(null)
  const button = useRef<HTMLButtonElement>(null)
  const pop = useRef<HTMLDivElement>(null)
  const nowMs = useNow(0, 60_000)
  const unread = useQuery({ queryKey: ['notifications', 'unread', user?.id], queryFn: notificationApi.unreadCount, refetchInterval: 60_000, refetchOnWindowFocus: true, staleTime: 15_000 })
  const latest = useQuery({ queryKey: ['notifications', 'latest', user?.id], queryFn: () => notificationApi.list(0, 10), enabled: open, refetchOnWindowFocus: true })
  const count = unread.data ?? 0
  const refresh = () => qc.invalidateQueries({ queryKey: ['notifications'] })
  const read = useMutation({ mutationFn: (id: string) => notificationApi.markRead(id), onSuccess: refresh })
  const readAll = useMutation({ mutationFn: notificationApi.markAllRead, onSuccess: refresh })

  useEffect(() => {
    if (!open) return
    pop.current?.focus()
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') { setOpen(false); button.current?.focus() } }
    const onDown = (e: MouseEvent) => { if (!wrap.current?.contains(e.target as Node)) setOpen(false) }
    document.addEventListener('keydown', onKey); document.addEventListener('mousedown', onDown)
    return () => { document.removeEventListener('keydown', onKey); document.removeEventListener('mousedown', onDown) }
  }, [open])

  function openItem(n: AppNotification) {
    if (!n.read) read.mutate(n.id)
    setOpen(false)
    navigate(notificationTarget(n, student))
  }

  return (
    <div className="bell-wrap" ref={wrap}>
      <button ref={button} type="button" className="btn btn-ghost btn-icon" aria-haspopup="dialog" aria-expanded={open} aria-controls="bell-pop"
        aria-label={count > 0 ? t('Thông báo, {n} chưa đọc', { n: count }) : t('Thông báo')} onClick={() => setOpen(o => !o)}>
        <Bell size={20} />
        {count > 0 && <span className="bell-badge" aria-hidden>{unreadLabel(count)}</span>}
      </button>
      {open && (
        <div id="bell-pop" ref={pop} className="bell-pop" role="dialog" aria-label={t('Thông báo')} tabIndex={-1}>
          <div className="spread" style={{ padding: '12px 16px', borderBottom: '1px solid #000' }}>
            <h2 style={{ fontSize: '1.2rem' }}>{t('Thông báo')}</h2>
            <button type="button" className="btn btn-ghost btn-sm" disabled={count === 0 || readAll.isPending} onClick={() => readAll.mutate()}>{t('Đọc tất cả')}</button>
          </div>
          {latest.isLoading ? <LoadingState rows={3} /> : latest.isError ? <ErrorState error={latest.error} onRetry={() => void latest.refetch()} />
            : latest.data!.content.length === 0 ? <EmptyState title={t('Chưa có thông báo')} description={t('Thông báo đặt/đổi lịch, mở/đóng kỳ thi và nhắc giờ sẽ hiện ở đây.')} />
            : <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>{latest.data!.content.map(n => <li key={n.id}><NotificationItem n={n} nowMs={nowMs} onOpen={openItem} /></li>)}</ul>}
          <div style={{ padding: 12, borderTop: '1px solid #000' }}><PushCard /></div>
          <div style={{ padding: '10px 16px', borderTop: '1px solid #000' }}><Link to="/notifications" onClick={() => setOpen(false)}>{t('Xem tất cả thông báo')}</Link></div>
        </div>
      )}
    </div>
  )
}
