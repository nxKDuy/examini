import { useCallback, useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useAuthStore } from '../../store/authStore'
import { errorText } from '../../lib/errors'
import { notificationApi } from './notificationApi'
import { clearRecord, currentSubscription, getRecord, pushSupported, setRecord, urlBase64ToUint8Array } from './pushClient'

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
    setBusy(true); setError('')
    try {
      const result = await Notification.requestPermission() // user gesture: called from the button's click
      setPermission(result)
      if (result !== 'granted') { setError(result === 'denied' ? 'Trình duyệt đã chặn thông báo cho trang này.' : 'Bạn chưa cho phép thông báo.'); return }
      const reg = await navigator.serviceWorker.register('/sw.js')
      await navigator.serviceWorker.ready
      let sub = await reg.pushManager.getSubscription()
      if (!sub) sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: urlBase64ToUint8Array(key.data.publicKey) })
      const created = await notificationApi.subscribe(sub.toJSON())
      setRecord(userId, created.id)
      await refresh()
    } catch (e) {
      const name = (e as { name?: string } | undefined)?.name
      setError(name === 'AbortError' || name === 'NotSupportedError' ? 'Trình duyệt không đăng ký được dịch vụ đẩy (có thể bị chặn hoặc thiếu kết nối tới nhà cung cấp push).' : errorText(e))
    } finally { setBusy(false) }
  }

  async function disable() {
    if (!userId) return
    setBusy(true); setError('')
    try {
      const id = getRecord(userId)
      if (id) { try { await notificationApi.unsubscribe(id) } catch { /* already gone: idempotent */ } }
      try { await (await currentSubscription())?.unsubscribe() } catch { /* best effort */ }
      clearRecord(userId)
      await refresh()
    } finally { setBusy(false) }
  }

  return { status, busy, error, enable, disable }
}
