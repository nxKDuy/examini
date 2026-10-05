import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { authApi } from './authApi'
import { LogIn } from 'lucide-react'

export default function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const [form, setForm] = useState({ email: '', password: '' })
  const [showPwd, setShowPwd] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    setLoading(true)
    setError('')
    try {
      await authApi.login(form)
      const from = location.state?.from
      navigate(typeof from === 'string' && from.startsWith('/') && !from.startsWith('//') ? from : '/dashboard', { replace: true })
    } catch (err: any) {
      setError(err.response?.data?.detail ?? 'Đăng nhập thất bại, vui lòng thử lại.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center p-4">
         
      <div className="page-enter w-full max-w-md clean-card" style={{ padding: '48px 40px', background: 'var(--color-surface-950)' }}>
        
        {/* Brand / Logo */}
        <div className="flex items-center justify-center mb-10">
          <div style={{ fontFamily: "'Pacifico', cursive", fontSize: '2.5rem', color: 'var(--color-primary-500)', letterSpacing: '0.02em', lineHeight: 1 }}>
            examini
          </div>
        </div>

        <div style={{ textAlign: 'center', marginBottom: 32 }}>
          <h1 style={{ fontSize: '1.75rem', fontWeight: 700, color: 'var(--color-surface-50)', marginBottom: '8px', letterSpacing: '-0.02em' }}>
            Đăng nhập
          </h1>
          <p style={{ color: 'var(--color-surface-400)', fontSize: '0.9rem' }}>
            Vui lòng nhập thông tin để tiếp tục
          </p>
        </div>

        <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
          {/* Email */}
          <div>
            <label htmlFor="login-email"
              style={{ display: 'block', fontSize: '0.875rem', fontWeight: 600,
                color: 'var(--color-surface-200)', marginBottom: '8px' }}>
              Địa chỉ Email
            </label>
            <input
              id="login-email"
              type="email"
              className="input-field"
              placeholder="you@example.com"
              autoComplete="email"
              required
              style={{ height: 44, fontSize: '0.95rem' }}
              value={form.email}
              onChange={e => setForm(f => ({ ...f, email: e.target.value }))}
            />
          </div>

          {/* Password */}
          <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
              <label htmlFor="login-password"
                style={{ fontSize: '0.875rem', fontWeight: 600, color: 'var(--color-surface-200)' }}>
                Mật khẩu
              </label>
              <Link to="/forgot-password" style={{ fontSize: '0.8rem', color: 'var(--color-surface-400)', textDecoration: 'none' }}>
                Quên mật khẩu?
              </Link>
            </div>
            
            <div style={{ position: 'relative' }}>
              <input
                id="login-password"
                type={showPwd ? 'text' : 'password'}
                className="input-field"
                placeholder="••••••••"
                autoComplete="current-password"
                required
                style={{ paddingRight: '44px', height: 44, fontSize: '0.95rem' }}
                value={form.password}
                onChange={e => setForm(f => ({ ...f, password: e.target.value }))}
              />
              <button type="button"
                aria-label={showPwd ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'}
                onClick={() => setShowPwd(v => !v)}
                style={{ position: 'absolute', right: '12px', top: '50%', transform: 'translateY(-50%)',
                  background: 'none', border: 'none', cursor: 'pointer',
                  color: 'var(--color-surface-400)', display: 'flex', padding: '4px' }}>
                {showPwd ? 'Ẩn' : 'Hiện'}
              </button>
            </div>
          </div>

          {/* Error message */}
          {error && (
            <div role="alert"
              style={{ padding: '12px 16px', borderRadius: 'var(--radius-sm)',
                background: 'var(--color-primary-50)', border: '1px solid var(--color-primary-200)',
                color: 'var(--color-primary-700)', fontSize: '0.875rem', display: 'flex', gap: 8, alignItems: 'center' }}>
              <span style={{ fontWeight: 600 }}>Lỗi:</span> {error}
            </div>
          )}

          <button type="submit" className="btn-primary" disabled={loading} style={{ height: 48, fontSize: '1rem', marginTop: 8 }}>
            {loading ? (
              <span style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <span style={{ width: 18, height: 18, border: '2px solid rgba(255,255,255,0.3)',
                  borderTopColor: 'white', borderRadius: '50%',
                  animation: 'spin 0.7s linear infinite' }} />
                Đang xác thực...
              </span>
            ) : (
              <><LogIn size={18} style={{ marginRight: 8 }} /> Đăng nhập hệ thống</>
            )}
          </button>
        </form>

        <p style={{ textAlign: 'center', marginTop: '32px', fontSize: '0.9rem',
          color: 'var(--color-surface-400)' }}>
          Chưa có tài khoản?{' '}
          <Link to="/register"
            style={{ color: 'var(--color-surface-50)', fontWeight: 600, textDecoration: 'underline', textUnderlineOffset: 4 }}>
            Đăng ký ngay
          </Link>
        </p>
      </div>

      <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  )
}
