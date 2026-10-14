import { Bell, CalendarClock, CalendarPlus, CalendarX, CheckCircle2, ClipboardList, FileCheck2, Lock, PlayCircle, Send, UserPlus, XCircle } from 'lucide-react'
import './notifications.css'
import type { AppNotification } from './notificationApi'
import { localizedNotice, notificationIcon, relativeTime, type NotificationIcon } from './notificationModel'
import { t, useLangStore, useT } from '../../lib/i18n'

const ICONS: Record<NotificationIcon, typeof Bell> = {
  'calendar-plus': CalendarPlus, 'calendar-clock': CalendarClock, 'calendar-x': CalendarX, play: PlayCircle, lock: Lock, bell: Bell,
  result: FileCheck2, send: Send, approved: CheckCircle2, rejected: XCircle, member: UserPlus, assigned: ClipboardList,
}

export default function NotificationItem({ n, nowMs, onOpen }: { n: AppNotification; nowMs: number; onOpen: (n: AppNotification) => void }) {
  useT()
  const lang = useLangStore(s => s.lang)
  const text = localizedNotice(n, lang)
  const Icon = ICONS[notificationIcon(n.type)]
  return (
    <button type="button" className="list-item notif-item" data-unread={!n.read} onClick={() => onOpen(n)}>
      <span className="notif-icon" aria-hidden><Icon size={18} /></span>
      <span style={{ minWidth: 0 }}>
        <span className="notif-title">{!n.read && <span className="sr-only">{t('Chưa đọc:')} </span>}{text.title}</span>
        <span className="notif-msg">{text.message}</span>
        <span className="xsmall muted">{relativeTime(n.createdAt, nowMs)}</span>
      </span>
      {!n.read && <span className="notif-dot" aria-hidden />}
    </button>
  )
}
