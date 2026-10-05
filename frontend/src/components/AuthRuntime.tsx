import { useEffect, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { authApi } from '../features/auth/authApi'
import { useAuthStore } from '../store/authStore'

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
  if (!ready) return <p role="status">Đang xác thực phiên đăng nhập…</p>
  return children
}
