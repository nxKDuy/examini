import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { KeyRound, Mail } from 'lucide-react'
import { AuthCard } from './AuthCard'
import { accountApi, passwordProblem } from '../account/accountApi'
import { Field, Notice } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { t, useT } from '../../lib/i18n'

/** Asks for the email; the answer is the same whether or not an account exists. */
export function ForgotPasswordPage() {
  useT()
  const [email, setEmail] = useState('')
  const [state, setState] = useState<'idle' | 'sending' | 'sent'>('idle')
  const [error, setError] = useState('')
  async function submit(e: FormEvent) {
    e.preventDefault(); setError(''); setState('sending')
    try { await accountApi.forgot(email); setState('sent') }
    catch (err) { setError(errorText(err)); setState('idle') }
  }
  return (
    <AuthCard title={t('Quên mật khẩu')} subtitle={t('Nhập email đăng ký, chúng tôi sẽ gửi liên kết đặt lại mật khẩu (hiệu lực 30 phút).')}
      footer={<Link to="/login">{t('Quay lại đăng nhập')}</Link>}>
      {state === 'sent'
        ? <Notice tone="success" live title={t('Đã gửi yêu cầu')}>{t('Nếu {0} là email của một tài khoản EXAMINI, bạn sẽ nhận được liên kết đặt lại mật khẩu trong vài phút. Kiểm tra cả thư mục Spam.', { 0: email.trim() })}</Notice>
        : <form className="stack" onSubmit={submit}>
            <Field label="Email" htmlFor="forgot-email">
              <input id="forgot-email" className="input" type="email" autoComplete="email" required autoFocus value={email} onChange={e => setEmail(e.target.value)} />
            </Field>
            {error && <Notice tone="danger" live>{error}</Notice>}
            <button type="submit" className="btn btn-primary btn-lg btn-block" disabled={state === 'sending'} aria-busy={state === 'sending'}>
              <Mail size={18} aria-hidden /> {state === 'sending' ? t('Đang gửi…') : t('Gửi liên kết')}</button>
          </form>}
    </AuthCard>
  )
}

export function ResetPasswordPage() {
  useT()
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const token = params.get('token') ?? ''
  const [form, setForm] = useState({ next: '', confirm: '' })
  const [touched, setTouched] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const problem = passwordProblem(form.next, form.confirm)
  async function submit(e: FormEvent) {
    e.preventDefault(); setTouched(true); setError('')
    if (problem) return
    setBusy(true)
    try { await accountApi.reset(token, form.next); navigate('/login', { replace: true, state: { notice: 'PASSWORD_RESET' } }) }
    catch (err) { setError(errorText(err)) }
    finally { setBusy(false) }
  }
  return (
    <AuthCard title={t('Đặt mật khẩu mới')} subtitle={t('Sau khi đặt, mọi thiết bị đang đăng nhập sẽ bị đăng xuất.')}
      footer={<Link to="/forgot-password">{t('Gửi lại liên kết')}</Link>}>
      {!token
        ? <Notice tone="danger">{t('Liên kết không hợp lệ. Hãy mở đúng liên kết trong email hoặc gửi lại yêu cầu.')}</Notice>
        : <form className="stack" onSubmit={submit}>
            <Field label={t('Mật khẩu mới')} htmlFor="reset-new" required hint={t('Tối thiểu 8 ký tự.')}>
              <input id="reset-new" className="input" type="password" autoComplete="new-password" autoFocus value={form.next} onChange={e => setForm(f => ({ ...f, next: e.target.value }))} />
            </Field>
            <Field label={t('Nhập lại mật khẩu mới')} htmlFor="reset-confirm" required
              error={touched && problem ? (problem === 'TOO_SHORT' ? t('Mật khẩu mới tối thiểu 8 ký tự.') : t('Mật khẩu nhập lại không khớp.')) : undefined}>
              <input id="reset-confirm" className="input" type="password" autoComplete="new-password" value={form.confirm} onChange={e => setForm(f => ({ ...f, confirm: e.target.value }))} />
            </Field>
            {error && <Notice tone="danger" live>{error}</Notice>}
            <button type="submit" className="btn btn-primary btn-lg btn-block" disabled={busy} aria-busy={busy}>
              <KeyRound size={18} aria-hidden /> {busy ? t('Đang lưu…') : t('Đặt mật khẩu')}</button>
          </form>}
    </AuthCard>
  )
}
