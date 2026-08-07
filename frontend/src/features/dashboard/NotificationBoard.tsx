import { useState } from 'react'
import { Bell, BookOpen, Clock, CheckCircle, Info, X } from 'lucide-react'

// ─── Types ────────────────────────────────────────────────────────────────────
type NotifType = 'exam_new' | 'exam_result' | 'exam_reminder' | 'info'

interface Notification {
  id: string
  type: NotifType
  title: string
  message: string
  time: string
  read: boolean
}

// ─── Mock data – sẽ được thay bằng API call sau ──────────────────────────────
const MOCK_NOTIFICATIONS: Notification[] = [
  {
    id: 'n1',
    type: 'exam_new',
    title: 'Kỳ thi mới được tạo',
    message: 'Giáo viên vừa tạo kỳ thi "Kiểm tra giữa kỳ Toán 12" dành cho bạn. Kỳ thi sẽ bắt đầu vào ngày 10/08/2026.',
    time: '5 phút trước',
    read: false,
  },
  {
    id: 'n2',
    type: 'exam_reminder',
    title: 'Nhắc nhở: Kỳ thi sắp bắt đầu',
    message: '"Đề khảo sát năng lực Tiếng Anh" sẽ mở trong 2 ngày nữa. Hãy ôn tập kỹ!',
    time: '1 giờ trước',
    read: false,
  },
  {
    id: 'n3',
    type: 'exam_result',
    title: 'Điểm thi đã được công bố',
    message: 'Bạn đạt 8.5/10 trong kỳ thi "Bài tập cuối khóa Vật Lý". Xem lại bài làm của bạn ngay.',
    time: 'Hôm qua',
    read: true,
  },
  {
    id: 'n4',
    type: 'info',
    title: 'Thông báo từ hệ thống',
    message: 'Hệ thống sẽ bảo trì vào 23:00 tối nay trong 30 phút. Vui lòng không làm bài thi trong thời gian này.',
    time: '2 ngày trước',
    read: true,
  },
]

// ─── Icon theo loại thông báo ─────────────────────────────────────────────────
function NotifIcon({ type }: { type: NotifType }) {
  const cfg = {
    exam_new:      { icon: BookOpen,      bg: 'rgba(240,78,56,0.12)',  color: 'var(--color-primary-500)' },
    exam_reminder: { icon: Clock,         bg: 'rgba(251,191,36,0.12)', color: '#fbbf24'                 },
    exam_result:   { icon: CheckCircle,   bg: 'rgba(74,222,128,0.12)', color: '#4ade80'                 },
    info:          { icon: Info,          bg: 'rgba(56,189,248,0.12)', color: '#38bdf8'                 },
  }[type]
  const Icon = cfg.icon
  return (
    <div style={{ width: 40, height: 40, borderRadius: 10, background: cfg.bg,
      display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
      <Icon size={20} color={cfg.color} />
    </div>
  )
}

// ─── Main Component ───────────────────────────────────────────────────────────
export default function NotificationBoard() {
  const [notifications, setNotifications] = useState<Notification[]>(MOCK_NOTIFICATIONS)
  const [showAll, setShowAll] = useState(false)

  const unreadCount = notifications.filter(n => !n.read).length
  const visible = showAll ? notifications : notifications.slice(0, 3)

  const markRead = (id: string) => {
    setNotifications(prev => prev.map(n => n.id === id ? { ...n, read: true } : n))
  }

  const dismiss = (id: string) => {
    setNotifications(prev => prev.filter(n => n.id !== id))
  }

  const markAllRead = () => {
    setNotifications(prev => prev.map(n => ({ ...n, read: true })))
  }

  return (
    <div className="clean-card" style={{ overflow: 'hidden' }}>
      {/* Header */}
      <div style={{
        padding: '16px 20px',
        borderBottom: '1px solid var(--color-surface-700)',
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <Bell size={20} color="var(--color-primary-500)" />
          <span style={{ fontWeight: 600, color: 'var(--color-surface-50)', fontSize: '1rem' }}>
            Thông báo
          </span>
          {unreadCount > 0 && (
            <span style={{
              background: 'var(--color-primary-500)', color: 'white',
              fontSize: '0.75rem', fontWeight: 700,
              borderRadius: 999, padding: '1px 7px', lineHeight: '18px',
            }}>
              {unreadCount}
            </span>
          )}
        </div>
        {unreadCount > 0 && (
          <button onClick={markAllRead} style={{
            fontSize: '0.8rem', color: 'var(--color-surface-400)',
            background: 'none', border: 'none', cursor: 'pointer',
            transition: 'color 0.15s',
          }}
            onMouseEnter={e => (e.currentTarget.style.color = 'var(--color-surface-50)')}
            onMouseLeave={e => (e.currentTarget.style.color = 'var(--color-surface-400)')}>
            Đánh dấu đã đọc tất cả
          </button>
        )}
      </div>

      {/* List */}
      <div>
        {notifications.length === 0 ? (
          <div style={{ padding: '40px 20px', textAlign: 'center', color: 'var(--color-surface-500)' }}>
            <Bell size={36} style={{ marginBottom: 12, opacity: 0.4 }} />
            <p style={{ margin: 0, fontSize: '0.9rem' }}>Không có thông báo mới</p>
          </div>
        ) : (
          visible.map(notif => (
            <div
              key={notif.id}
              onClick={() => markRead(notif.id)}
              style={{
                padding: '16px 20px',
                borderBottom: '1px solid var(--color-surface-800)',
                display: 'flex', gap: 14, alignItems: 'flex-start',
                background: notif.read ? 'transparent' : 'rgba(240,78,56,0.04)',
                cursor: 'pointer',
                transition: 'background 0.15s',
              }}
              onMouseEnter={e => (e.currentTarget.style.background = 'rgba(240, 78, 56, 0.07)')}
              onMouseLeave={e => (e.currentTarget.style.background = notif.read ? 'transparent' : 'rgba(240,78,56,0.04)')}>

              <NotifIcon type={notif.type} />

              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 4 }}>
                  <span style={{
                    fontWeight: notif.read ? 500 : 700,
                    color: notif.read ? 'var(--color-surface-200)' : 'var(--color-surface-50)',
                    fontSize: '0.9rem',
                  }}>
                    {notif.title}
                  </span>
                  {!notif.read && (
                    <span style={{
                      width: 8, height: 8, borderRadius: '50%',
                      background: 'var(--color-primary-500)', flexShrink: 0, marginTop: 4,
                    }} />
                  )}
                </div>
                <p style={{
                  margin: 0, fontSize: '0.825rem',
                  color: 'var(--color-surface-400)', lineHeight: 1.55,
                  overflow: 'hidden', display: '-webkit-box',
                  WebkitLineClamp: 2, WebkitBoxOrient: 'vertical',
                }}>
                  {notif.message}
                </p>
                <span style={{ fontSize: '0.775rem', color: 'var(--color-surface-500)', marginTop: 6, display: 'block' }}>
                  {notif.time}
                </span>
              </div>

              <button
                onClick={e => { e.stopPropagation(); dismiss(notif.id) }}
                style={{
                  background: 'none', border: 'none', cursor: 'pointer',
                  color: 'var(--color-surface-600)', padding: 4, borderRadius: 4,
                  display: 'flex', flexShrink: 0, transition: 'color 0.15s',
                }}
                onMouseEnter={e => (e.currentTarget.style.color = 'var(--color-surface-300)')}
                onMouseLeave={e => (e.currentTarget.style.color = 'var(--color-surface-600)')}>
                <X size={14} />
              </button>
            </div>
          ))
        )}
      </div>

      {/* Footer – Show more */}
      {notifications.length > 3 && (
        <div style={{ padding: '12px 20px', textAlign: 'center', borderTop: '1px solid var(--color-surface-800)' }}>
          <button
            onClick={() => setShowAll(v => !v)}
            style={{
              background: 'none', border: 'none', cursor: 'pointer',
              fontSize: '0.85rem', color: 'var(--color-primary-400)', fontWeight: 600,
              transition: 'color 0.15s',
            }}
            onMouseEnter={e => (e.currentTarget.style.color = 'var(--color-primary-300)')}
            onMouseLeave={e => (e.currentTarget.style.color = 'var(--color-primary-400)')}>
            {showAll ? 'Thu gọn ▲' : `Xem thêm ${notifications.length - 3} thông báo ▼`}
          </button>
        </div>
      )}
    </div>
  )
}
