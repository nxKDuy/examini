import { useState } from 'react'
import { Laptop, Monitor, Smartphone } from 'lucide-react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CalendarSync as SyncIcon, Copy, ExternalLink, RefreshCw, Unlink } from 'lucide-react'
import { Notice } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { formatDateTime } from '../../lib/format'
import { t, useT } from '../../lib/i18n'
import { feedUrl, googleSubscribeUrl, isLocalAddress, syncApi, webcalUrl } from './calendarSync'

/**
 * "Sync to my phone/computer calendar": one secret subscription link per account. Calendar apps refresh it, so new,
 * moved or cancelled exams appear without opening EXAMINI. The link is shown once (only its hash is stored);
 * making a new one turns the old one off.
 */
export default function CalendarSync() {
  useT()
  const qc = useQueryClient()
  const [link, setLink] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const status = useQuery({ queryKey: ['calendar-feed'], queryFn: syncApi.status, staleTime: 60_000 })
  const create = useMutation({ mutationFn: syncApi.create, onSuccess: r => { setLink(feedUrl(r.token)); setCopied(false); void qc.invalidateQueries({ queryKey: ['calendar-feed'] }) } })
  const revoke = useMutation({ mutationFn: syncApi.revoke, onSuccess: () => { setLink(null); void qc.invalidateQueries({ queryKey: ['calendar-feed'] }) } })
  const active = status.data?.active
  const busy = create.isPending || revoke.isPending
  async function copy() { if (!link) return; try { await navigator.clipboard.writeText(link); setCopied(true) } catch { setCopied(false) } }

  return (
    <details className="card cal-sync">
      <summary><SyncIcon size={18} aria-hidden /> <strong>{t('Đồng bộ với lịch điện thoại / máy tính')}</strong>
        <span className="xsmall muted">{active ? t('Đang bật') : t('Chưa bật')}</span></summary>
      <div className="stack-sm" style={{ paddingTop: 12 }}>
        <p className="small muted" style={{ margin: 0 }}>{t('Thêm lịch thi của bạn vào Google Calendar, Lịch iPhone hoặc Outlook. Kỳ thi mới, đổi giờ hay huỷ lịch sẽ tự cập nhật (ứng dụng lịch thường làm mới vài giờ một lần).')}</p>
        {(create.isError || revoke.isError) && <Notice tone="danger" live>{errorText(create.error ?? revoke.error)}</Notice>}
        {link ? (
          <div className="stack-sm">
            <label className="field" htmlFor="cal-sync-link"><span className="small">{t('Link đăng ký lịch của bạn (chỉ hiện một lần — hãy sao chép ngay)')}</span>
              <span style={{ display: 'flex', gap: 8 }}>
                <input id="cal-sync-link" className="input mono" readOnly value={link} onFocus={e => e.currentTarget.select()} style={{ minWidth: 0, flex: 1 }} />
                <button type="button" className="btn btn-secondary" onClick={() => void copy()}><Copy size={16} aria-hidden /> {copied ? t('Đã chép') : t('Sao chép')}</button>
              </span>
            </label>
            {isLocalAddress(link) && <Notice tone="warning">{t('Hệ thống đang chạy ở địa chỉ nội bộ (localhost): Google Calendar không tải được link này. Lịch trên chính máy này vẫn dùng được; khi triển khai lên tên miền thật thì mọi thiết bị đều đồng bộ được.')}</Notice>}
            <DeviceSteps link={link} />
            <p className="xsmall muted" style={{ margin: 0 }}>{t('Ai có link này đều xem được lịch thi của bạn (chỉ xem). Lỡ chia sẻ nhầm thì tạo link mới.')}</p>
          </div>
        ) : active ? (
          <p className="small" style={{ margin: 0 }}>{t('Đồng bộ đang bật từ {0}.', { 0: formatDateTime(status.data?.createdAt) })} {status.data?.lastUsedAt ? t('Ứng dụng lịch tải gần nhất lúc {0}.', { 0: formatDateTime(status.data.lastUsedAt) }) : t('Chưa có ứng dụng lịch nào tải link.')} {t('Link chỉ hiện lúc tạo; cần lại thì tạo link mới (link cũ sẽ ngừng hoạt động).')}</p>
        ) : null}
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          {!link && <button type="button" className={`btn ${active ? 'btn-secondary' : 'btn-primary'}`} disabled={busy || status.isLoading} onClick={() => create.mutate()}>
            {active ? <><RefreshCw size={16} aria-hidden /> {t('Tạo link mới')}</> : <><SyncIcon size={16} aria-hidden /> {t('Bật đồng bộ lịch')}</>}</button>}
          {active && <button type="button" className="btn btn-ghost" disabled={busy} onClick={() => revoke.mutate()}><Unlink size={16} aria-hidden /> {t('Tắt đồng bộ')}</button>}
        </div>
      </div>
    </details>
  )
}

type Device = 'google' | 'iphone' | 'windows' | 'mac'
const DEVICES: { id: Device; label: () => string; icon: typeof Laptop }[] = [
  { id: 'google', label: () => t('Google / Android'), icon: Smartphone },
  { id: 'iphone', label: () => t('iPhone, iPad'), icon: Smartphone },
  { id: 'windows', label: () => t('Laptop Windows'), icon: Laptop },
  { id: 'mac', label: () => t('MacBook'), icon: Monitor },
]
/** Best guess of the viewer's device, so the right steps are open first. */
export function guessDevice(ua = typeof navigator === 'undefined' ? '' : navigator.userAgent): Device {
  if (/iPhone|iPad|iPod/i.test(ua)) return 'iphone'
  if (/Android/i.test(ua)) return 'google'
  if (/Macintosh|Mac OS X/i.test(ua)) return 'mac'
  if (/Windows/i.test(ua)) return 'windows'
  return 'google'
}

/** One button + short numbered steps per device; the subscribe link does the work where the OS supports webcal. */
function DeviceSteps({ link }: { link: string }) {
  useT()
  const [device, setDevice] = useState<Device>(() => guessDevice())
  const webcal = webcalUrl(link)
  const steps: Record<Device, { action: React.ReactNode; items: string[] }> = {
    google: { action: <a className="btn btn-primary" href={googleSubscribeUrl(link)} target="_blank" rel="noreferrer"><ExternalLink size={16} aria-hidden /> {t('Thêm vào Google Calendar')}</a>,
      items: [t('Bấm nút trên, đăng nhập Google nếu được hỏi, chọn “Thêm”.'), t('Lịch “EXAMINI – Lịch thi” hiện trong Google Calendar trên web và trên điện thoại Android cùng tài khoản.'),
        t('Không có nút? Vào calendar.google.com → “Lịch khác” (+) → “Từ URL”, dán link.')] },
    iphone: { action: <a className="btn btn-primary" href={webcal}>{t('Mở trong Lịch iPhone')}</a>,
      items: [t('Bấm nút trên rồi chọn “Đăng ký”.'), t('Hoặc: Cài đặt → Lịch → Tài khoản → Thêm tài khoản → Khác → Thêm lịch đã đăng ký, dán link.')] },
    windows: { action: <a className="btn btn-primary" href={webcal}>{t('Mở bằng Outlook')}</a>,
      items: [t('Outlook cài trên máy: bấm nút trên rồi chọn “Có” để thêm lịch.'),
        t('Outlook trên web / ứng dụng Outlook mới: Lịch → Thêm lịch → Đăng ký từ web, dán link, đặt tên “EXAMINI”.'),
        t('Ứng dụng Lịch của Windows dùng chung tài khoản Outlook/Google: thêm lịch ở một trong hai nơi trên là tự hiện.')] },
    mac: { action: <a className="btn btn-primary" href={webcal}>{t('Mở trong ứng dụng Lịch')}</a>,
      items: [t('Bấm nút trên, ứng dụng Lịch mở ra → “Đăng ký”.'), t('Hoặc trong Lịch: Tệp → Đăng ký lịch mới…, dán link. Nên chọn “Tự động làm mới: Mỗi giờ”.')] },
  }
  return (
    <div className="cal-sync-devices">
      <div role="tablist" aria-label={t('Thiết bị')} className="cal-sync-tabs">
        {DEVICES.map(d => <button key={d.id} type="button" role="tab" aria-selected={device === d.id} className={device === d.id ? 'active' : ''} onClick={() => setDevice(d.id)}>
          <d.icon size={15} aria-hidden /> {d.label()}</button>)}
      </div>
      <div role="tabpanel" className="stack-sm" style={{ paddingTop: 10 }}>
        <div>{steps[device].action}</div>
        <ol className="small" style={{ margin: 0, paddingLeft: 20 }}>{steps[device].items.map(x => <li key={x}>{x}</li>)}</ol>
      </div>
    </div>
  )
}
