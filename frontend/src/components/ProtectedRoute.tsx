import { Navigate, Outlet } from 'react-router-dom'
import { useAuthStore } from '../store/authStore'

interface Props {
  children?: React.ReactNode
  roles?: ('ADMIN' | 'TEACHER' | 'STUDENT')[]
}

/** Bảo vệ route: chưa đăng nhập → /login; sai role → /dashboard */
export default function ProtectedRoute({ children, roles }: Props) {
  const { user, accessToken } = useAuthStore()

  if (!accessToken || !user) return <Navigate to="/login" replace />
  if (roles && !roles.includes(user.role)) return <Navigate to="/dashboard" replace />

  return children ? <>{children}</> : <Outlet />
}
