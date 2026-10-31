import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { notificationApi, type AppNotification } from './notificationApi'
import { groupByDay, INBOX_GROUPS, notificationTarget, type InboxGroup } from './notificationModel'
import NotificationItem from './NotificationItem'
import { PushCard } from './PushControls'
import { PreferencesCard } from './PreferencesCard'
import { audienceOf } from '../../components/layout/navigation'
import { EmptyState, ErrorState, LoadingState, PageHeader, Pagination } from '../../components/ui'
import { useAuthStore } from '../../store/authStore'
import { useNow } from '../../lib/hooks'
import { useT } from '../../lib/i18n'

export default function NotificationsPage() {
  const t = useT()
  const user = useAuthStore(s => s.user)
  const student = audienceOf(user) === 'STUDENT'
  const qc = useQueryClient()
  const navigate = useNavigate()
  const [tab, setTab] = useState<'all' | 'unread'>('all')
  const [group, setGroup] = useState<InboxGroup | undefined>()
  const [page, setPage] = useState(0)
  const nowMs = useNow(0, 60_000)
  const list = useQuery({ queryKey: ['notifications', 'page', user?.id, tab, group, page], queryFn: () => notificationApi.list(page, 20, tab === 'unread' ? false : undefined, group), placeholderData: p => p, refetchOnWindowFocus: true })
  const unread = useQuery({ queryKey: ['notifications', 'unread', user?.id], queryFn: notificationApi.unreadCount, refetchInterval: 60_000, refetchOnWindowFocus: true })
  const refresh = () => qc.invalidateQueries({ queryKey: ['notifications'] })
  const read = useMutation({ mutationFn: (id: string) => notificationApi.markRead(id), onSuccess: refresh })
  const readAll = useMutation({ mutationFn: notificationApi.markAllRead, onSuccess: refresh })
  const open = (n: AppNotification) => { if (!n.read) read.mutate(n.id); navigate(notificationTarget(n, student)) }
  const setTabAndReset = (t: 'all' | 'unread') => { setTab(t); setPage(0) }
  const setGroupAndReset = (g: InboxGroup | undefined) => { setGroup(g); setPage(0) }

  return (
    <div className="stack-lg">
      <PageHeader title={t('Thông báo')} description={t('Đặt/đổi/gỡ lịch, mở và đóng kỳ thi, nhắc giờ và các cập nhật khác. Bấm một thông báo để mở nội dung liên quan.')}
        actions={<button type="button" className="btn btn-secondary" disabled={(unread.data ?? 0) === 0 || readAll.isPending} onClick={() => readAll.mutate()}>{t('Đánh dấu tất cả đã đọc')}</button>} />
      <PushCard />
      <PreferencesCard />
      <section className="card" style={{ overflow: 'hidden' }}>
        <div className="tabs" role="tablist" aria-label={t('Lọc thông báo')}>
          <button type="button" role="tab" className="tab" aria-selected={tab === 'all'} onClick={() => setTabAndReset('all')}>{t('Tất cả')}</button>
          <button type="button" role="tab" className="tab" aria-selected={tab === 'unread'} onClick={() => setTabAndReset('unread')}>{t('Chưa đọc')}{unread.data ? <span className="count">{unread.data}</span> : null}</button>
        </div>
        <div className="notif-filter" role="group" aria-label={t('Lọc theo loại')}>
          {INBOX_GROUPS.map(g => (
            <button key={g.label} type="button" className="notif-chip" aria-pressed={group === g.value} onClick={() => setGroupAndReset(g.value)}>{t(g.label)}</button>
          ))}
        </div>
        {list.isLoading ? <LoadingState rows={4} /> : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} />
          : list.data!.content.length === 0 ? <EmptyState title={t(group ? 'Không có thông báo thuộc loại này' : tab === 'unread' ? 'Không còn thông báo chưa đọc' : 'Chưa có thông báo')} description={t('Thông báo mới sẽ hiện ở đây và ở chuông trên thanh đầu trang.')} />
          : groupByDay(list.data!.content, nowMs).map(day => (
            <section key={day.key} aria-label={day.label}>
              <h3 className="notif-day">{day.label}</h3>
              <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>{day.items.map(n => <li key={n.id}><NotificationItem n={n} nowMs={nowMs} onOpen={open} /></li>)}</ul>
            </section>
          ))}
        {list.data && <Pagination label={t('Phân trang thông báo')} page={page} totalPages={list.data.totalPages} onChange={setPage} />}
      </section>
    </div>
  )
}
