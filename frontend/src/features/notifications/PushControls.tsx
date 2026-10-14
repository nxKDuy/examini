import { useState } from 'react'
import { Bell, BellOff } from 'lucide-react'
import { usePush } from './usePush'
import { Notice } from '../../components/ui'
import { useAuthStore } from '../../store/authStore'
import { t, useT } from '../../lib/i18n'

const WHAT = 'Nhận thông báo đặt/đổi lịch, mở/đóng kỳ thi và nhắc giờ ngay cả khi không mở EXAMINI. Trình duyệt chỉ hỏi quyền khi bạn bấm Bật.'

/** Opt-in card for browser push. Always explains, never prompts on its own. */
export function PushCard() {
  useT()
  const { status, busy, error, enable, disable } = usePush()
  return (
    <section className="card card-body stack-sm" aria-label={t('Thông báo trên trình duyệt')}>
      <div className="spread">
        <h3 className="row" style={{ gap: 8 }}>{status === 'on' ? <Bell size={18} aria-hidden /> : <BellOff size={18} aria-hidden />} {t('Thông báo trên trình duyệt')}</h3>
        {status === 'off' && <button type="button" className="btn btn-primary btn-sm" disabled={busy} onClick={() => void enable()}>{busy ? t('Đang bật…') : t('Bật thông báo trình duyệt')}</button>}
        {status === 'on' && <button type="button" className="btn btn-secondary btn-sm" disabled={busy} onClick={() => void disable()}>{busy ? t('Đang tắt…') : t('Tắt trên trình duyệt này')}</button>}
      </div>
      {status === 'checking' && <p className="small muted" role="status">{t('Đang kiểm tra…')}</p>}
      {status === 'off' && <p className="small">{WHAT}</p>}
      {status === 'on' && <p className="small" role="status">{t('Đã bật trên trình duyệt này. Thông báo vẫn luôn có trong chuông của EXAMINI.')}</p>}
      {status === 'blocked' && <p className="small">{t('Bạn đã chặn thông báo cho trang này. Mở cài đặt trang của trình duyệt (biểu tượng ổ khóa cạnh địa chỉ) để cho phép lại, rồi tải lại trang.')}</p>}
      {status === 'unsupported' && <p className="small muted">{t('Trình duyệt này không hỗ trợ thông báo đẩy (hoặc trang không chạy trên HTTPS/localhost). Thông báo vẫn có trong chuông của EXAMINI.')}</p>}
      {status === 'unconfigured' && <p className="small muted">{t('Máy chủ chưa cấu hình thông báo trình duyệt. Thông báo vẫn có trong chuông của EXAMINI.')}</p>}
      {error && <Notice tone="danger" live>{error}</Notice>}
    </section>
  )
}

const DISMISS = 'examini-push-dismissed'
/** One-time, dismissible hint on the calendar. Hidden unless push is available and still off. */
export function PushPrompt() {
  useT()
  const userId = useAuthStore(s => s.user?.id)
  const { status, busy, error, enable } = usePush()
  const [dismissed, setDismissed] = useState(() => { try { return localStorage.getItem(`${DISMISS}:${userId}`) === '1' } catch { return false } })
  if (status !== 'off' || dismissed) return null
  const close = () => { try { localStorage.setItem(`${DISMISS}:${userId}`, '1') } catch { /* optional */ } setDismissed(true) }
  return (
    <Notice tone="info" title={t('Nhận nhắc lịch thi ngay trên trình duyệt?')} action={
      <span className="row"><button type="button" className="btn btn-primary btn-sm" disabled={busy} onClick={() => void enable()}>{t('Bật thông báo')}</button>
        <button type="button" className="btn btn-ghost btn-sm" onClick={close}>{t('Để sau')}</button></span>}>
      {WHAT}{error && <><br /><span style={{ color: 'var(--color-danger)' }}>{error}</span></>}
    </Notice>
  )
}
