import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { authApi } from './authApi'
import { Brain, UserPlus } from 'lucide-react'

export default function RegisterPage() {
  const navigate = useNavigate()
  const [form, setForm] = useState({ fullName: '', email: '', password: '', confirm: '' })
  const [showPwd, setShowPwd] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    if (form.password !== form.confirm) { setError('Mật khẩu xác nhận không khớp.'); return }
    setLoading(true)
    setError('')
    try {
      await authApi.register({ email: form.email, password: form.password, fullName: form.fullName })
      navigate('/dashboard')
    } catch (err: any) {
      setError(err.response?.data?.detail ?? 'Đăng ký thất bại, vui lòng thử lại.')
    } finally {
      setLoading(false)
    }
  }

  const field = (id: string, label: string, props: React.InputHTMLAttributes<HTMLInputElement>) => (
    <div>
      <label htmlFor={id}
        style={{ display: 'block', fontSize: '0.875rem', fontWeight: 600,
          color: 'var(--color-surface-200)', marginBottom: '8px' }}>
        {label}
      </label>
      <input id={id} className="input-field" {...props} style={{ height: 44, fontSize: '0.95rem', ...props.style }} />
    </div>
  )

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
            Tạo tài khoản
          </h1>
          <p style={{ color: 'var(--color-surface-400)', fontSize: '0.9rem' }}>
            Đăng ký để bắt đầu học tập và thi cử
          </p>
        </div>

        <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
          {field('reg-name', 'Họ và tên', {
            type: 'text', placeholder: 'Nguyễn Văn A', required: true, autoComplete: 'name',
            value: form.fullName, onChange: e => setForm(f => ({ ...f, fullName: e.target.value }))
          })}
          
          {field('reg-email', 'Địa chỉ Email', {
            type: 'email', placeholder: 'you@example.com', required: true, autoComplete: 'email',
            value: form.email, onChange: e => setForm(f => ({ ...f, email: e.target.value }))
          })}

          <div>
            <label htmlFor="reg-pwd"
              style={{ display: 'block', fontSize: '0.875rem', fontWeight: 600,
                color: 'var(--color-surface-200)', marginBottom: '8px' }}>
              Mật khẩu <span style={{ color: 'var(--color-surface-400)', fontWeight: 400 }}>(tối thiểu 8 ký tự)</span>
            </label>
            <div style={{ position: 'relative' }}>
              <input id="reg-pwd" type={showPwd ? 'text' : 'password'} className="input-field"
                placeholder="••••••••" required minLength={8} autoComplete="new-password"
                style={{ paddingRight: '44px', height: 44, fontSize: '0.95rem' }}
                value={form.password}
                onChange={e => setForm(f => ({ ...f, password: e.target.value }))} />
              <button type="button" aria-label={showPwd ? 'Ẩn' : 'Hiện'}
                onClick={() => setShowPwd(v => !v)}
                style={{ position: 'absolute', right: '12px', top: '50%', transform: 'translateY(-50%)',
                  background: 'none', border: 'none', cursor: 'pointer',
                  color: 'var(--color-surface-400)', display: 'flex', padding: '4px' }}>
                {showPwd ? 'Ẩn' : 'Hiện'}
              </button>
            </div>
          </div>

          {field('reg-confirm', 'Xác nhận mật khẩu', {
            type: 'password', placeholder: '••••••••', required: true, autoComplete: 'new-password',
            value: form.confirm, onChange: e => setForm(f => ({ ...f, confirm: e.target.value }))
          })}

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
                Đang đăng ký...
              </span>
            ) : (
              <><UserPlus size={18} style={{ marginRight: 8 }} /> Tạo tài khoản mới</>
            )}
          </button>
        </form>

        <p style={{ textAlign: 'center', marginTop: '32px', fontSize: '0.9rem',
          color: 'var(--color-surface-400)' }}>
          Đã có tài khoản?{' '}
          <Link to="/login"
            style={{ color: 'var(--color-surface-50)', fontWeight: 600, textDecoration: 'underline', textUnderlineOffset: 4 }}>
            Đăng nhập
          </Link>
        </p>
      </div>
      <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  )
}
