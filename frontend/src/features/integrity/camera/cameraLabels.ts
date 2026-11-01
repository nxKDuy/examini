import { t, i18nMap } from '../../../lib/i18n'
import type { CameraEvent } from './cameraApi'
import type { CameraEventType, Violation } from './cameraModel'

export const CAMERA_LABEL: Record<CameraEventType, string> = i18nMap({
  REFERENCE: 'Ảnh lúc bắt đầu', NO_FACE: 'Không thấy khuôn mặt', MULTIPLE_FACES: 'Nhiều người', FACE_MISMATCH: 'Khuôn mặt không khớp',
  LOOKING_AWAY: 'Nhìn ra ngoài', CAMERA_OFF: 'Camera tắt', CAMERA_ON: 'Camera bật lại', PROFILE_MISMATCH: 'Không khớp hồ sơ',
})
export const VIOLATIONS: CameraEventType[] = ['PROFILE_MISMATCH', 'FACE_MISMATCH', 'MULTIPLE_FACES', 'NO_FACE', 'LOOKING_AWAY', 'CAMERA_OFF']

export const bytesText = (n: number) => n >= 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(n / 1024))} KB`
export function detailText(e: Pick<CameraEvent, 'type' | 'detail'>) {
  const d = e.detail ?? {}
  const parts: string[] = []
  if (d.faces !== undefined && e.type === 'MULTIPLE_FACES') parts.push(t('{n} khuôn mặt', { n: d.faces }))
  if (d.distance !== undefined) parts.push(t('độ lệch {d} (ngưỡng {th})', { d: d.distance, th: d.threshold ?? '–' }))
  if (d.yaw !== undefined || d.pitch !== undefined) parts.push(t('quay {y}°, cúi/ngửa {p}°', { y: Math.round(d.yaw ?? 0), p: Math.round(d.pitch ?? 0) }))
  if (d.seconds !== undefined) parts.push(t('kéo dài {s} giây', { s: d.seconds }))
  return parts.join(' · ')
}

export const LIVE_WARNING: Record<Violation, string> = i18nMap({
  NO_FACE: 'Không thấy khuôn mặt. Hãy ngồi trước camera.',
  MULTIPLE_FACES: 'Có nhiều người trong khung hình.',
  FACE_MISMATCH: 'Khuôn mặt không khớp với ảnh lúc bắt đầu.',
  LOOKING_AWAY: 'Hãy nhìn vào màn hình làm bài.',
})
