import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { Eye, EyeOff, LogIn } from 'lucide-react'
import { authApi } from './authApi'
import { errorInfo } from '../../lib/errors'
import { Field, Notice } from '../../components/ui'
import { AuthCard } from './AuthCard'
import GoogleSignIn from './GoogleSignIn'
import { t, useT } from '../../lib/i18n'

export default function LoginPage() {
  useT()
  const navigate = useNavigate()
  const location = useLocation()
  const [form, setForm] = useState({ email: '', password: '' })
  const [showPwd, setShowPwd] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const goOn = () => {
    const from = location.state?.from
    navigate(typeof from === 'string' && from.startsWith('/') && !from.startsWith('//') ? from : '/dashboard', { replace: true })
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setLoading(true)
    setError('')
    try {
      await authApi.login(form)
      goOn()
    } catch (err) {
      const info = errorInfo(err)
      setError(info.status === 401 ? t('Email hoặc mật khẩu không đúng.') : info.message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthCard title={t('Đăng nhập')} subtitle={t('Dùng tài khoản EXAMINI của bạn để tiếp tục.')}
      footer={<>{t('Chưa có tài khoản?')} <Link to="/register">{t('Đăng ký')}</Link></>}>
      <form onSubmit={handleSubmit} className="stack" noValidate={false}>
        <Field label="Email" htmlFor="login-email">
          <input id="login-email" type="email" className="input" autoComplete="email" required autoFocus
            value={form.email} onChange={e => setForm(f => ({ ...f, email: e.target.value }))} />
        </Field>
        <Field label={t('Mật khẩu')} htmlFor="login-password">
          <div style={{ position: 'relative' }}>
            <input id="login-password" type={showPwd ? 'text' : 'password'} className="input" autoComplete="current-password" required
              style={{ paddingRight: 44 }} value={form.password} onChange={e => setForm(f => ({ ...f, password: e.target.value }))} />
            <button type="button" className="btn btn-ghost btn-icon" aria-label={showPwd ? t('Ẩn mật khẩu') : t('Hiện mật khẩu')} aria-pressed={showPwd}
              onClick={() => setShowPwd(v => !v)} style={{ position: 'absolute', right: 2, top: 2 }}>
              {showPwd ? <EyeOff size={18} /> : <Eye size={18} />}
            </button>
          </div>
        </Field>
        {error && <Notice tone="danger" live>{error}</Notice>}
        <button type="submit" className="btn btn-primary btn-lg btn-block" disabled={loading} aria-busy={loading}>
          <LogIn size={18} aria-hidden /> {loading ? t('Đang đăng nhập…') : t('Đăng nhập hệ thống')}
        </button>
      </form>
      <GoogleSignIn onDone={goOn} />
    </AuthCard>
  )
}
