import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { LogOut } from 'lucide-react'
import { useAuthStore } from '../../store/authStore'
import { authApi } from '../../features/auth/authApi'
import { AUDIENCE_LABEL, audienceOf, navigationItems } from './navigation'
import MobileDock from './MobileDock'
import { useDepthEffects } from '../../lib/depth'
import NotificationBell from '../../features/notifications/NotificationBell'
import LanguageSwitch from '../LanguageSwitch'
import { useEffect, useState } from 'react'
import { Notice } from '../ui'
import { Avatar } from '../../features/account/Avatar'
import { useAvatarUrl, useProfile } from '../../features/account/accountApi'
import { SWITCH_NOTICE_KEY } from '../../features/auth/crossTab'
import { useLangStore, useT } from '../../lib/i18n'

export function Brand() {
  const t = useT()
  return <NavLink to="/dashboard" className="brand" aria-label={t('EXAMINI, về trang tổng quan')}><span>EXAMINI</span></NavLink>
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
  // Set by the cross-tab watcher when another tab signed in with a different account.
  const profile = useProfile()
  const avatarUrl = useAvatarUrl(profile.data?.avatarUpdatedAt)
  const [switched, setSwitched] = useState(() => { try { const v = sessionStorage.getItem(SWITCH_NOTICE_KEY); sessionStorage.removeItem(SWITCH_NOTICE_KEY); return v === '1' } catch { return false } })

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
            <NavLink to="/profile" className="topbar-profile" aria-label={t('Hồ sơ cá nhân: {0}', { 0: user?.fullName ?? '' })} title={t('Hồ sơ cá nhân')}>
              <div className="topbar-who" style={{ textAlign: 'right', minWidth: 0, lineHeight: 1.3 }}>
                <div className="small" style={{ color: 'var(--color-ink)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: 220 }}>
                  {user?.fullName}
                </div>
                <div className="xsmall muted">{t(AUDIENCE_LABEL[audience])}</div>
              </div>
              <Avatar name={user?.fullName ?? '?'} url={avatarUrl} size={34} />
            </NavLink>
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
          {switched && <div style={{ marginBottom: 24 }}><Notice tone="warning" live title={t('Đã đổi tài khoản')}
            action={<button type="button" className="btn btn-ghost btn-sm" onClick={() => setSwitched(false)}>{t('Đã hiểu')}</button>}>
            {t('Một tab khác vừa đăng nhập bằng tài khoản khác. Mỗi trình duyệt chỉ dùng một tài khoản, nên tab này đã chuyển sang tài khoản {0}.', { 0: user?.email ?? '' })}</Notice></div>}
          <Outlet />
        </main>
        <MobileDock audience={audience} />
      </div>
    </div>
  )
}
