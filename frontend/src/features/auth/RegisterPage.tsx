import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Eye, EyeOff, UserPlus } from 'lucide-react'
import { authApi } from './authApi'
import { errorInfo } from '../../lib/errors'
import { Field, Notice } from '../../components/ui'
import { AuthCard } from './AuthCard'

type Persona = 'STUDENT' | 'TEACHER'
const PERSONAS: { value: Persona; title: string; description: string }[] = [
  { value: 'STUDENT', title: 'Học sinh / Sinh viên', description: 'Tham gia kỳ thi được giao, kỳ thi công khai hoặc bằng mã tham gia; xem kết quả của mình.' },
  { value: 'TEACHER', title: 'Giáo viên', description: 'Soạn câu hỏi, tạo và tổ chức kỳ thi, chấm bài cho kỳ thi mình quản lý.' },
]

export default function RegisterPage() {
  const navigate = useNavigate()
  const [form, setForm] = useState({ fullName: '', email: '', password: '', confirm: '', persona: 'STUDENT' as Persona })
  const [showPwd, setShowPwd] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [fields, setFields] = useState<Record<string, string>>({})
  const mismatch = form.confirm.length > 0 && form.password !== form.confirm

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (form.password !== form.confirm) { setError('Mật khẩu xác nhận không khớp.'); return }
    setLoading(true)
    setError('')
    setFields({})
    try {
      await authApi.register({ email: form.email, password: form.password, fullName: form.fullName, accountType: 'PERSONAL', persona: form.persona })
      navigate('/dashboard', { replace: true })
    } catch (err) {
      const info = errorInfo(err)
      setError(info.status === 409 ? 'Email này đã được đăng ký. Hãy đăng nhập hoặc dùng email khác.' : info.message)
      setFields(info.fields ?? {})
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthCard title="Tạo tài khoản" subtitle="Tài khoản cá nhân cho học sinh, sinh viên hoặc giáo viên."
      footer={<>Đã có tài khoản? <Link to="/login" style={{ fontWeight: 600 }}>Đăng nhập</Link></>}>
      <form onSubmit={handleSubmit} className="stack">
        <fieldset className="stack-sm">
          <legend className="field-label" style={{ marginBottom: 6 }}>Bạn sử dụng EXAMINI với vai trò</legend>
          {PERSONAS.map(p => (
            <label key={p.value} className="choice">
              <input type="radio" name="persona" id={`reg-persona-${p.value.toLowerCase()}`} value={p.value}
                checked={form.persona === p.value} onChange={() => setForm(f => ({ ...f, persona: p.value }))} />
              <span className="stack-sm" style={{ gap: 2 }}>
                <strong style={{ color: 'var(--color-ink)' }}>{p.title}</strong>
                <span className="xsmall muted">{p.description}</span>
              </span>
            </label>
          ))}
          <p className="xsmall muted">Tài khoản quản trị hệ thống không đăng ký công khai; quyền quản trị do hệ thống cấp riêng.</p>
        </fieldset>
        <Field label="Họ và tên" htmlFor="reg-name" error={fields.fullName}>
          <input id="reg-name" className="input" required autoComplete="name" maxLength={255}
            value={form.fullName} onChange={e => setForm(f => ({ ...f, fullName: e.target.value }))} />
        </Field>
        <Field label="Email" htmlFor="reg-email" error={fields.email}>
          <input id="reg-email" type="email" className="input" required autoComplete="email"
            value={form.email} onChange={e => setForm(f => ({ ...f, email: e.target.value }))} />
        </Field>
        <Field label="Mật khẩu" htmlFor="reg-pwd" hint="Tối thiểu 8 ký tự." error={fields.password}>
          <div style={{ position: 'relative' }}>
            <input id="reg-pwd" type={showPwd ? 'text' : 'password'} className="input" required minLength={8} autoComplete="new-password"
              style={{ paddingRight: 44 }} value={form.password} onChange={e => setForm(f => ({ ...f, password: e.target.value }))} />
            <button type="button" className="btn btn-ghost btn-icon" aria-label={showPwd ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'} aria-pressed={showPwd}
              onClick={() => setShowPwd(v => !v)} style={{ position: 'absolute', right: 2, top: 2 }}>
              {showPwd ? <EyeOff size={18} /> : <Eye size={18} />}
            </button>
          </div>
        </Field>
        <Field label="Xác nhận mật khẩu" htmlFor="reg-confirm" error={mismatch ? 'Mật khẩu xác nhận không khớp.' : undefined}>
          <input id="reg-confirm" type={showPwd ? 'text' : 'password'} className="input" required autoComplete="new-password" aria-invalid={mismatch}
            value={form.confirm} onChange={e => setForm(f => ({ ...f, confirm: e.target.value }))} />
        </Field>
        {error && <Notice tone="danger" live>{error}</Notice>}
        <button type="submit" className="btn btn-primary btn-lg btn-block" disabled={loading} aria-busy={loading}>
          <UserPlus size={18} aria-hidden /> {loading ? 'Đang tạo tài khoản…' : 'Tạo tài khoản mới'}
        </button>
      </form>
    </AuthCard>
  )
}
