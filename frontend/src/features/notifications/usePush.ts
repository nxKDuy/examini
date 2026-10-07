import { useCallback, useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useAuthStore } from '../../store/authStore'
import { errorText } from '../../lib/errors'
import { notificationApi } from './notificationApi'
import { currentSubscription, disablePush, enablePush, getRecord, pushSupported } from './pushClient'

export type PushStatus = 'checking' | 'unsupported' | 'unconfigured' | 'blocked' | 'off' | 'on'

/** Browser push opt-in state for the current account. Permission is requested only inside enable(), i.e. from a click. */
export function usePush() {
  const userId = useAuthStore(s => s.user?.id)
  const supported = pushSupported()
  const key = useQuery({ queryKey: ['push', 'key', userId], queryFn: notificationApi.pushKey, staleTime: 60_000, retry: false, enabled: supported && !!userId })
  const [permission, setPermission] = useState<NotificationPermission>(() => (supported ? Notification.permission : 'default'))
  const [subscribed, setSubscribed] = useState<boolean | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const refresh = useCallback(async () => {
    if (!supported) return
    setPermission(Notification.permission)
    setSubscribed(!!(await currentSubscription()) && !!getRecord(userId))
  }, [supported, userId])
  useEffect(() => { void refresh() }, [refresh])

  let status: PushStatus
  if (!supported) status = 'unsupported'
  else if (key.isLoading || subscribed === null) status = 'checking'
  else if (key.isError || !key.data?.enabled || !key.data.publicKey) status = 'unconfigured'
  else if (subscribed) status = 'on'
  else if (permission === 'denied') status = 'blocked'
  else status = 'off'

  async function enable() {
    if (!supported || !userId || !key.data?.publicKey) return
    const start = useAuthStore.getState(), session = start.sessionVersion
    // The same session and account must still be active when each step finishes, otherwise nothing is registered for whoever is signed in now.
    const isCurrent = () => { const now = useAuthStore.getState(); return now.sessionVersion === session && now.user?.id === userId }
    setBusy(true); setError('')
    try {
      const result = await enablePush({ userId, token: start.accessToken, publicKey: key.data.publicKey, isCurrent })
      if (result === 'stale') return
      setPermission(Notification.permission)
      if (result === 'denied') { setError(Notification.permission === 'denied' ? 'Trình duyệt đã chặn thông báo cho trang này.' : 'Bạn chưa cho phép thông báo.'); return }
      await refresh()
    } catch (e) {
      if (!isCurrent()) return
      const name = (e as { name?: string } | undefined)?.name
      setError(name === 'AbortError' || name === 'NotSupportedError' ? 'Trình duyệt không đăng ký được dịch vụ đẩy (có thể bị chặn hoặc thiếu kết nối tới nhà cung cấp push).' : errorText(e))
    } finally { setBusy(false) }
  }

  async function disable() {
    if (!userId) return
    const start = useAuthStore.getState(), session = start.sessionVersion
    const isCurrent = () => { const now = useAuthStore.getState(); return now.sessionVersion === session && now.user?.id === userId }
    setBusy(true); setError('')
    try {
      await disablePush({ userId, token: start.accessToken, isCurrent })
      if (isCurrent()) await refresh()
    } finally { setBusy(false) }
  }

  return { status, busy, error, enable, disable }
}
