import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { LogOut } from 'lucide-react'
import { useAuthStore } from '../../store/authStore'
import { authApi } from '../../features/auth/authApi'
import { AUDIENCE_LABEL, audienceOf, navigationItems } from './navigation'
import MobileDock from './MobileDock'
import { useDepthEffects } from '../../lib/depth'
import NotificationBell from '../../features/notifications/NotificationBell'
import LanguageSwitch from '../LanguageSwitch'
import { useEffect } from 'react'
import { useLangStore, useT } from '../../lib/i18n'

export function Brand() {
  const t = useT()
  return <NavLink to="/dashboard" className="brand" aria-label={t('EXAMINI, về trang tổng quan')}><span>EXAMINI</span><small>Phase / 01</small></NavLink>
}

export default function AppLayout() {
  const user = useAuthStore(s => s.user)
  const navigate = useNavigate()
  const audience = audienceOf(user)
  useDepthEffects()
  const links = navigationItems(audience)
  const t = useT()
  const bindAccount = useLangStore(s => s.bindAccount)
  useEffect(() => { bindAccount(user?.id ?? null) }, [user?.id, bindAccount])

  async function logout() {
    await authApi.logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="shell">
      <a href="#main-content" className="skip-link">{t('Bỏ qua đến nội dung chính')}</a>
      <div className="main">
        <header className="topbar">
          <div className="topbar-main">
            <Brand />
            <div style={{ flex: 1 }} />
            <div className="row topbar-user" style={{ gap: 12, flexWrap: 'nowrap', minWidth: 0 }}>
            <LanguageSwitch />
            <NotificationBell />
            <div className="topbar-who" style={{ textAlign: 'right', minWidth: 0, lineHeight: 1.3 }}>
              <div className="small" style={{ color: 'var(--color-ink)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: 220 }}>
                {user?.fullName}
              </div>
              <div className="xsmall muted">{t(AUDIENCE_LABEL[audience])}</div>
            </div>
            <span aria-hidden className="topbar-avatar" style={{ width: 34, height: 34, border: '1px solid #000', background: '#fff', color: '#000',
              display: 'grid', placeItems: 'center', flexShrink: 0 }}>
              {user?.fullName?.trim().split(/\s+/).pop()?.[0]?.toUpperCase() ?? '?'}
            </span>
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => void logout()} title={t('Đăng xuất')}>
              <LogOut size={16} aria-hidden /> <span className="hide-sm">{t('Đăng xuất')}</span>
            </button>
            </div>
          </div>
          <nav className="quicklinks" aria-label={t('Điều hướng chính')}>
            {links.map(({to,label,icon:Icon}) => <NavLink key={to} to={to} className="quicklink">
              <Icon size={15} aria-hidden /> <span>{t(label)}</span>
            </NavLink>)}
          </nav>
        </header>
        <main id="main-content" tabIndex={-1} className="page page-enter">
          <Outlet />
        </main>
        <MobileDock audience={audience} />
      </div>
    </div>
  )
}
