import { useEffect, useRef, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { LayoutGrid, LogOut } from 'lucide-react'
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
  const launcherRef = useRef<HTMLDivElement>(null), launcherButtonRef = useRef<HTMLButtonElement>(null)
  const groups = navigationFor(audience)
  const links = groups.flatMap(group => group.items)

  useEffect(() => { setOpen(false) }, [location.pathname])
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { setOpen(false); launcherButtonRef.current?.focus() }
    }
    const onPointer = (e: PointerEvent) => { if (!launcherRef.current?.contains(e.target as Node)) setOpen(false) }
    document.addEventListener('keydown', onKey)
    document.addEventListener('pointerdown', onPointer)
    return () => { document.removeEventListener('keydown', onKey); document.removeEventListener('pointerdown', onPointer) }
  }, [open])

  async function logout() {
    await authApi.logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="shell">
      <a href="#main-content" className="skip-link">Bỏ qua đến nội dung chính</a>
      <div className="main">
        <header className="topbar">
          <div className="topbar-main">
            <Brand />
            <div className="space-launcher" ref={launcherRef}>
              <button ref={launcherButtonRef} type="button" className="btn btn-secondary btn-sm" aria-expanded={open} aria-controls="space-launcher-panel" onClick={() => setOpen(value => !value)}>
                <LayoutGrid size={16} aria-hidden /> Không gian
              </button>
              <div id="space-launcher-panel" className="space-launcher-panel" hidden={!open}>
                {groups.map((group, i) => <section key={i} className="space-launcher-group" aria-label={group.label ?? 'Chung'}>
                  {group.label && <h2 className="xsmall muted">{group.label}</h2>}
                  <nav className="space-launcher-grid" aria-label={group.label ?? 'Không gian chính'}>
                    {group.items.map(({ to, label, icon: Icon }) => <NavLink key={to} to={to} onClick={() => setOpen(false)} className={({isActive}) => `space-launcher-link ${isActive ? 'active' : ''}`}>
                      <Icon size={18} aria-hidden /><span>{label}</span>
                    </NavLink>)}
                  </nav>
                </section>)}
              </div>
            </div>
            <div style={{ flex: 1 }} />
            <div className="row topbar-user" style={{ gap: 12, flexWrap: 'nowrap', minWidth: 0 }}>
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
          </div>
          <nav className="quicklinks" aria-label="Liên kết nhanh">
            {links.map(({to,label,icon:Icon}) => <NavLink key={to} to={to} className={({isActive}) => `quicklink ${isActive ? 'active' : ''}`}>
              <Icon size={15} aria-hidden /> <span>{label}</span>
            </NavLink>)}
          </nav>
        </header>
        <main id="main-content" tabIndex={-1} className="page page-enter">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
