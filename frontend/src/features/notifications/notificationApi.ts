import api from '../../lib/api'

export interface AppNotification {
  id: string; type: string; title: string; message: string
  entityType: 'QUESTION' | 'EXAM' | 'EXAM_SESSION' | string; entityId: string
  metadata: Record<string, string> | null; createdAt: string; readAt: string | null; read: boolean
}
export interface NotificationPage { content: AppNotification[]; totalPages: number; totalElements: number; number: number; last: boolean }
export interface PushKey { enabled: boolean; publicKey: string | null }
/** PushSubscription.toJSON() */
export interface PushSubscriptionJson { endpoint?: string; expirationTime?: number | null; keys?: { p256dh?: string; auth?: string } }

export const notificationApi = {
  list: (page = 0, size = 20, read?: boolean) => api.get<NotificationPage>('/notifications', { params: { page, size, ...(read === undefined ? {} : { read }) } }).then(r => r.data),
  unreadCount: () => api.get<{ count: number }>('/notifications/unread-count').then(r => r.data.count),
  markRead: (id: string) => api.patch<AppNotification>(`/notifications/${id}/read`).then(r => r.data),
  markAllRead: () => api.patch<{ updated: number }>('/notifications/read-all').then(r => r.data),
  pushKey: () => api.get<PushKey>('/notifications/push/public-key').then(r => r.data),
  subscribe: (sub: PushSubscriptionJson) => api.post<{ id: string; createdAt: string }>('/notifications/push/subscriptions', sub).then(r => r.data),
  unsubscribe: (id: string) => api.delete(`/notifications/push/subscriptions/${encodeURIComponent(id)}`),
}
