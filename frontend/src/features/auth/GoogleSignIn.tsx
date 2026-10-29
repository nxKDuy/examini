import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { authApi } from './authApi'
import { errorInfo } from '../../lib/errors'
import { Notice, Spinner } from '../../components/ui'
import { t, useT, useLangStore } from '../../lib/i18n'

type Persona = 'STUDENT' | 'TEACHER'
interface GoogleId {
  initialize: (o: { client_id: string; callback: (r: { credential: string }) => void; ux_mode?: 'popup'; auto_select?: boolean; cancel_on_tap_outside?: boolean }) => void
  renderButton: (el: HTMLElement, o: Record<string, unknown>) => void
}
declare global { interface Window { google?: { accounts: { id: GoogleId } } } }

let script: Promise<GoogleId> | null = null
/** Loads Google Identity Services once (https://accounts.google.com/gsi/client). */
function loadGoogle(): Promise<GoogleId> {
  script ??= new Promise<GoogleId>((resolve, reject) => {
    if (window.google?.accounts?.id) return resolve(window.google.accounts.id)
    const s = document.createElement('script')
    s.src = 'https://accounts.google.com/gsi/client'; s.async = true; s.defer = true
    s.onload = () => (window.google?.accounts?.id ? resolve(window.google.accounts.id) : reject(new Error('GSI missing')))
    s.onerror = () => { script = null; reject(new Error('GSI blocked')) }
    document.head.appendChild(s)
  })
  return script
}

/**
 * "Continue with Google". Hidden unless the server has a Google client id. A first-time Google user picks
 * Student or Teacher (the register page passes its choice directly).
 */
export default function GoogleSignIn({ persona, onDone, mode = 'signin' }: { persona?: Persona; onDone: () => void; mode?: 'signin' | 'signup' }) {
  useT()
  const lang = useLangStore(s => s.lang)
  const providers = useQuery({ queryKey: ['auth-providers'], queryFn: authApi.providers, staleTime: 300_000, retry: false })
  const box = useRef<HTMLDivElement>(null)
  const personaRef = useRef(persona)
  personaRef.current = persona
  const [pending, setPending] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const clientId = providers.data?.google ? providers.data.googleClientId : null

  async function finish(credential: string, chosen?: Persona) {
    setBusy(true); setError('')
    try { await authApi.google(credential, chosen); onDone() }
    catch (e) {
      const info = errorInfo(e)
      if (info.status === 409 && info.detail === 'PERSONA_REQUIRED') setPending(credential)
      else setError(info.message)
    } finally { setBusy(false) }
  }

  useEffect(() => {
    if (!clientId || !box.current) return
    let cancelled = false
    loadGoogle().then(g => {
      if (cancelled || !box.current) return
      g.initialize({ client_id: clientId, ux_mode: 'popup', cancel_on_tap_outside: true, callback: r => void finish(r.credential, personaRef.current) })
      box.current.innerHTML = ''
      g.renderButton(box.current, { type: 'standard', theme: 'outline', size: 'large', shape: 'rectangular', text: mode === 'signup' ? 'signup_with' : 'continue_with',
        locale: lang === 'en' ? 'en' : 'vi', width: Math.min(400, box.current.clientWidth || 320) })
    }).catch(() => !cancelled && setError(t('Không tải được nút đăng nhập Google. Kiểm tra mạng hoặc trình chặn quảng cáo.')))
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- re-render the button only when the client id, mode or language change
  }, [clientId, mode, lang])

  if (!clientId) return null
  return (
    <div className="stack-sm">
      <div className="auth-divider" role="separator"><span>{t('hoặc')}</span></div>
      <div ref={box} className="google-button" aria-busy={busy} />
      {busy && <span className="row small muted" style={{ gap: 6 }}><Spinner size={14} /> {t('Đang đăng nhập bằng Google…')}</span>}
      {pending && <div className="stack-sm" role="group" aria-label={t('Chọn loại tài khoản')}>
        <Notice tone="info" title={t('Lần đầu đăng nhập bằng Google')}>{t('Bạn dùng EXAMINI với vai trò nào? Có thể chọn một lần duy nhất.')}</Notice>
        <div className="grid-2">
          <button type="button" className="btn btn-secondary" disabled={busy} onClick={() => void finish(pending, 'STUDENT')}>{t('Học sinh / Sinh viên')}</button>
          <button type="button" className="btn btn-secondary" disabled={busy} onClick={() => void finish(pending, 'TEACHER')}>{t('Giáo viên')}</button>
        </div>
      </div>}
      {error && <Notice tone="danger" live>{error}</Notice>}
    </div>
  )
}
