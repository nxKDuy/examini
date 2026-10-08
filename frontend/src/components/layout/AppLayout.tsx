import { useEffect, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { LogOut, Menu } from 'lucide-react'
import { useAuthStore } from '../../store/authStore'
import { authApi } from '../../features/auth/authApi'
import { AUDIENCE_LABEL, audienceOf, navigationFor } from './navigation'
import NotificationBell from '../../features/notifications/NotificationBell'

export function Brand() {
  return <NavLink to="/dashboard" className="brand" aria-label="EXAMINI, về trang tổng quan"><span>EXAMINI</span><small>Phase / 01</small></NavLink>
}

export default function AppLayout() {
  const user = useAuthStore(s => s.user)
  const navigate = useNavigate()
  const location = useLocation()
  const [open, setOpen] = useState(false)
  const audience = audienceOf(user)

  useEffect(() => { setOpen(false) }, [location.pathname])
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open])

  async function logout() {
    await authApi.logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="shell">
      <a href="#main-content" className="skip-link">Bỏ qua đến nội dung chính</a>
      <div className="drawer-backdrop" data-open={open} onClick={() => setOpen(false)} aria-hidden />
      <aside className="sidebar" data-open={open} id="app-sidebar" aria-label="Điều hướng chính">
        <Brand />
        <nav className="nav" aria-label="Menu chính" style={{ flex: 1, overflowY: 'auto', alignContent: 'start' }}>
          {navigationFor(audience).map((group, i) => (
            <div key={i} className="nav">
              {group.label && <div className="nav-section">{group.label}</div>}
              {group.items.map(({ to, label, icon: Icon }) => (
                <NavLink key={to} to={to} className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                  <Icon size={18} aria-hidden /> {label}
                </NavLink>
              ))}
            </div>
          ))}
        </nav>
        <div className="xsmall muted" style={{ padding: '0 10px' }}>
          Quyền thao tác trên từng câu hỏi, đề thi và bài làm do máy chủ kiểm tra.
        </div>
      </aside>

      <div className="main">
        <header className="topbar">
          <button type="button" className="btn btn-ghost btn-icon menu-toggle" aria-label="Mở menu" aria-expanded={open}
            aria-controls="app-sidebar" onClick={() => setOpen(o => !o)}>
            <Menu size={20} />
          </button>
          <div style={{ flex: 1 }} />
          <div className="row" style={{ gap: 12, flexWrap: 'nowrap', minWidth: 0 }}>
            <NotificationBell />
            <div style={{ textAlign: 'right', minWidth: 0, lineHeight: 1.3 }}>
              <div className="small" style={{ color: 'var(--color-ink)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: 220 }}>
                {user?.fullName}
              </div>
              <div className="xsmall muted">{AUDIENCE_LABEL[audience]}</div>
            </div>
            <span aria-hidden style={{ width: 34, height: 34, border: '1px solid #000', background: '#fff', color: '#000',
              display: 'grid', placeItems: 'center', flexShrink: 0 }}>
              {user?.fullName?.trim().split(/\s+/).pop()?.[0]?.toUpperCase() ?? '?'}
            </span>
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => void logout()} title="Đăng xuất">
              <LogOut size={16} aria-hidden /> <span className="hide-sm">Đăng xuất</span>
            </button>
          </div>
        </header>
        <main id="main-content" tabIndex={-1} className="page page-enter">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
