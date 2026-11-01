import { useEffect, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { authApi } from '../features/auth/authApi'
import { useAuthStore } from '../store/authStore'
import { reconcilePush } from '../features/notifications/pushClient'
import { watchPushCleanup } from '../features/notifications/pushAuthWatcher'
import { t, useT } from '../lib/i18n'
import { SWITCH_NOTICE_KEY, watchOtherTabs } from '../features/auth/crossTab'

export function FullPageLoader({ label = t('Đang xác thực phiên đăng nhập…') }: { label?: string }) {
  useT()
  return (
    <div className="auth-shell">
      <p role="status" className="row muted small"><span className="brand-mark" aria-hidden>E</span>{label}</p>
    </div>
  )
}

/** Reload into the account another tab signed in with (a full reload drops every screen of the old account). */
function switchTo() {
  try { sessionStorage.setItem(SWITCH_NOTICE_KEY, '1') } catch { /* the notice is optional */ }
  window.location.assign('/dashboard')
}

export default function AuthRuntime({ children }: { children: ReactNode }) {
  const ready = useAuthStore(s => s.authReady)
  const queryClient = useQueryClient()
  const userId = useAuthStore(s => s.user?.id)
  // A browser push subscription that this account did not create must not keep delivering after an account switch.
  useEffect(() => {
    if (!userId) return
    const version = useAuthStore.getState().sessionVersion
    const isCurrent = () => { const c = useAuthStore.getState(); return c.sessionVersion === version && c.user?.id === userId }
    void reconcilePush(userId, isCurrent)
  }, [userId])
  // Any end of session (logout, expiry, failed refresh, account switch) drops this machine's push subscription for the old account.
  useEffect(() => watchPushCleanup(), [])
  useEffect(() => {
    const unsubscribe = useAuthStore.subscribe((state, previous) => {
      if (state.sessionVersion !== previous.sessionVersion) {
        void queryClient.cancelQueries()
        queryClient.clear()
      }
    })
    void authApi.restoreSession()
    // One account per browser: follow sign-in, refresh and sign-out from other tabs.
    const stopTabs = watchOtherTabs({
      restore: otherAccount => {
        if (otherAccount) { switchTo(); return }
        void authApi.restoreSession().then(() => {
          if (useAuthStore.getState().user && /^\/(login|register)\/?$/.test(window.location.pathname)) window.location.assign('/dashboard')
        })
      },
      switched: switchTo,
    })
    return () => { unsubscribe(); stopTabs() }
  }, [queryClient])
  if (!ready) return <FullPageLoader />
  return children
}
