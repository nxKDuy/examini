import { useEffect, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { authApi } from '../features/auth/authApi'
import { useAuthStore } from '../store/authStore'

export function FullPageLoader({ label = 'Đang xác thực phiên đăng nhập…' }: { label?: string }) {
  return (
    <div className="auth-shell">
      <p role="status" className="row muted small"><span className="brand-mark" aria-hidden>E</span>{label}</p>
    </div>
  )
}

export default function AuthRuntime({ children }: { children: ReactNode }) {
  const ready = useAuthStore(s => s.authReady)
  const queryClient = useQueryClient()
  useEffect(() => {
    const unsubscribe = useAuthStore.subscribe((state, previous) => {
      if (state.sessionVersion !== previous.sessionVersion) {
        void queryClient.cancelQueries()
        queryClient.clear()
      }
    })
    void authApi.restoreSession()
    return unsubscribe
  }, [queryClient])
  if (!ready) return <FullPageLoader />
  return children
}
