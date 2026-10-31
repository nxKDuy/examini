import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { notificationApi, type AppNotification } from './notificationApi'
import { notificationTarget } from './notificationModel'
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
  const [page, setPage] = useState(0)
  const nowMs = useNow(0, 60_000)
  const list = useQuery({ queryKey: ['notifications', 'page', user?.id, tab, page], queryFn: () => notificationApi.list(page, 20, tab === 'unread' ? false : undefined), placeholderData: p => p, refetchOnWindowFocus: true })
  const unread = useQuery({ queryKey: ['notifications', 'unread', user?.id], queryFn: notificationApi.unreadCount, refetchInterval: 60_000, refetchOnWindowFocus: true })
  const refresh = () => qc.invalidateQueries({ queryKey: ['notifications'] })
  const read = useMutation({ mutationFn: (id: string) => notificationApi.markRead(id), onSuccess: refresh })
  const readAll = useMutation({ mutationFn: notificationApi.markAllRead, onSuccess: refresh })
  const open = (n: AppNotification) => { if (!n.read) read.mutate(n.id); navigate(notificationTarget(n, student)) }
  const setTabAndReset = (t: 'all' | 'unread') => { setTab(t); setPage(0) }

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
        {list.isLoading ? <LoadingState rows={4} /> : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} />
          : list.data!.content.length === 0 ? <EmptyState title={t(tab === 'unread' ? 'Không còn thông báo chưa đọc' : 'Chưa có thông báo')} description={t('Thông báo mới sẽ hiện ở đây và ở chuông trên thanh đầu trang.')} />
          : <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>{list.data!.content.map(n => <li key={n.id}><NotificationItem n={n} nowMs={nowMs} onOpen={open} /></li>)}</ul>}
        {list.data && <Pagination label={t('Phân trang thông báo')} page={page} totalPages={list.data.totalPages} onChange={setPage} />}
      </section>
    </div>
  )
}
