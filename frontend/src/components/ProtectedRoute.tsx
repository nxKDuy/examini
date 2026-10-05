import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { hasPersona, isAuthenticated, isSystemAdmin, useAuthStore, type Persona } from '../store/authStore'

interface Props {
  children?: React.ReactNode
  persona?: Persona
  adminOnly?: boolean
}

// Navigation controls only. Resource authorization belongs to domain APIs.
export default function ProtectedRoute({ children, persona, adminOnly }: Props) {
  const state = useAuthStore()
  const location = useLocation()
  if (!state.authReady) return <p role="status">Đang xác thực…</p>
  if (!isAuthenticated(state)) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  if ((adminOnly && !isSystemAdmin(state.user)) ||
      (persona && !hasPersona(state.user, persona) && !(persona === 'TEACHER' && isSystemAdmin(state.user)))) {
    return <Navigate to="/dashboard" replace />
  }
  return children ? <>{children}</> : <Outlet />
}
