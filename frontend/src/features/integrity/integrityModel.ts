import type { IntegrityAudit, IntegrityPolicy, Receipt, SignalMetadata, SignalType } from './integrityApi'

export const DEFAULT_LIMIT = 5
export const MIN_LIMIT = 1
export const MAX_LIMIT = 100
export const DISABLED_POLICY: IntegrityPolicy = {
  monitorTabSwitch: false, monitorWindowBlur: false, requireFullscreen: false, monitorCopy: false, monitorPaste: false,
  monitorNetwork: false, warningPolicy: 'LOG_ONLY', warningEventLimit: DEFAULT_LIMIT,
}

/** Signals that count toward the per-attempt budget (contract); COPY/PASTE/recovery signals are recorded only. */
export const BUDGET_SIGNALS: SignalType[] = ['TAB_SWITCH', 'WINDOW_BLUR', 'EXIT_FULLSCREEN', 'PAGE_RELOAD', 'NETWORK_DISCONNECT']

export const SIGNAL_LABEL: Record<SignalType, string> = {
  TAB_SWITCH: 'Chuyển tab', WINDOW_BLUR: 'Rời cửa sổ', WINDOW_FOCUS: 'Quay lại cửa sổ', EXIT_FULLSCREEN: 'Thoát toàn màn hình',
  FULLSCREEN_ENTER: 'Vào toàn màn hình', COPY: 'Sao chép', PASTE: 'Dán', NETWORK_DISCONNECT: 'Mất mạng', NETWORK_RECONNECT: 'Có mạng lại',
  PAGE_RELOAD: 'Tải lại trang', SESSION_RESUME: 'Mở lại phiên',
}

export const MONITORS: { key: keyof IntegrityPolicy; label: string; hint: string }[] = [
  { key: 'monitorTabSwitch', label: 'Chuyển tab', hint: 'Ghi nhận khi trang bài thi bị ẩn. Có tính vào ngưỡng.' },
  { key: 'monitorWindowBlur', label: 'Rời cửa sổ', hint: 'Ghi nhận khi cửa sổ trình duyệt mất tiêu điểm. Có tính vào ngưỡng.' },
  { key: 'requireFullscreen', label: 'Yêu cầu toàn màn hình', hint: 'Nhắc thí sinh bật toàn màn hình; ghi nhận khi thoát. Có tính vào ngưỡng.' },
  { key: 'monitorNetwork', label: 'Mất kết nối mạng', hint: 'Ghi nhận khi mất mạng. Có tính vào ngưỡng.' },
  { key: 'monitorCopy', label: 'Sao chép', hint: 'Chỉ ghi nhận, không tính vào ngưỡng. Không lưu nội dung.' },
  { key: 'monitorPaste', label: 'Dán', hint: 'Chỉ ghi nhận, không tính vào ngưỡng. Không lưu nội dung.' },
]

export const monitored = (p: IntegrityPolicy | null | undefined) =>
  !!p && (p.monitorTabSwitch || p.monitorWindowBlur || p.requireFullscreen || p.monitorCopy || p.monitorPaste || p.monitorNetwork)

/** Budget is meaningful only when at least one counted signal is monitored. */
export const counts = (p: IntegrityPolicy | null | undefined) =>
  !!p && (p.monitorTabSwitch || p.monitorWindowBlur || p.requireFullscreen || p.monitorNetwork)

export function limitError(value: number) {
  return Number.isInteger(value) && value >= MIN_LIMIT && value <= MAX_LIMIT ? null : `Ngưỡng phải là số nguyên từ ${MIN_LIMIT} đến ${MAX_LIMIT}.`
}

export function policySummary(p: IntegrityPolicy | null | undefined) {
  if (!monitored(p)) return 'Tắt'
  const on = MONITORS.filter(m => p![m.key]).map(m => m.label.toLowerCase())
  return `${on.join(', ')}${counts(p) ? ` · tự nộp khi vượt ${p!.warningEventLimit} tín hiệu` : ''}`
}

/** What the Student sees after a receipt. Never says "gian lận". */
export type StudentSignalState = { count: number; limit: number; stage: 'none' | 'counting' | 'last' | 'submitted' }
export function signalState(r: Pick<Receipt, 'warningCount' | 'warningLimit' | 'warningRecommended' | 'autoSubmitted'>): StudentSignalState {
  const stage = r.autoSubmitted || r.warningCount > r.warningLimit ? 'submitted'
    : r.warningRecommended || r.warningCount === r.warningLimit ? 'last' : r.warningCount > 0 ? 'counting' : 'none'
  return { count: r.warningCount, limit: r.warningLimit, stage }
}

/** Retry plan for a rejected report: 429/5xx/network retry with backoff; other 4xx are final. */
export function retryDelay(status: number | undefined, attempt: number): number | null {
  const retryable = status === undefined || status === 0 || status === 429 || status >= 500
  if (!retryable || attempt >= 6) return null
  return Math.min(30_000, 1000 * 2 ** attempt)
}

export function metadataText(m: SignalMetadata | null | undefined) {
  if (!m) return ''
  const parts: string[] = []
  if (m.durationMs != null) parts.push(`${Math.round(m.durationMs / 1000)} giây`)
  if (m.visibilityState) parts.push(m.visibilityState === 'HIDDEN' ? 'trang bị ẩn' : 'trang hiển thị')
  if (m.networkState) parts.push(m.networkState === 'OFFLINE' ? 'ngoại tuyến' : 'trực tuyến')
  return parts.join(' · ')
}

export const AUDIT_LABEL: Record<string, string> = {
  INTEGRITY_BUDGET_AUTO_SUBMITTED: 'Tự nộp do vượt ngưỡng tín hiệu',
  EXAM_INTEGRITY_LOCK_AUTO_SUBMITTED: 'Tự nộp do khóa toàn kỳ thi',
}

/** An attempt ended by integrity (budget or whole-exam lock) — the case where staff may grant a new attempt. */
export const integrityEnded = (audit: IntegrityAudit[]) => audit.some(a => a.action in AUDIT_LABEL)
