import { useEffect, useRef, useState } from 'react'
import { Camera, CameraOff, ScanFace } from 'lucide-react'
import BlockingOverlay from '../BlockingOverlay'
import { Notice, Spinner } from '../../../components/ui'
import { t, useT, i18nMap } from '../../../lib/i18n'
import type { useCameraProctor } from './useCameraProctor'
import { LIVE_WARNING } from './cameraLabels'

type Camera = ReturnType<typeof useCameraProctor>

/** Live video element bound to a MediaStream (mirrored, like a mirror). */
export function LiveVideo({ stream, className }: { stream: MediaStream | null; className?: string }) {
  const ref = useRef<HTMLVideoElement>(null)
  useEffect(() => { if (ref.current && ref.current.srcObject !== stream) { ref.current.srcObject = stream; void ref.current.play().catch(() => undefined) } }, [stream])
  return <video ref={ref} className={className} muted playsInline autoPlay aria-label={t('Hình ảnh camera của bạn')} />
}

const ENROLL_ERROR: Record<string, string> = i18nMap({
  NO_FACE: 'Chưa thấy khuôn mặt. Hãy ngồi thẳng, đủ sáng và nhìn vào camera rồi thử lại.',
  MULTIPLE_FACES: 'Có nhiều hơn một khuôn mặt. Chỉ thí sinh được ở trong khung hình.',
  NO_CAMERA: 'Camera chưa sẵn sàng.',
})

/** Blocks the questions until the camera runs and the reference photo is accepted. */
export function CameraGate({ camera, remaining }: { camera: Camera; remaining: number }) {
  useT()
  const [busy, setBusy] = useState(false)
  const [enrollError, setEnrollError] = useState('')
  const { status } = camera
  async function take() {
    setBusy(true); setEnrollError('')
    const problem = await camera.enroll()
    setBusy(false)
    if (problem === 'PROFILE_MISMATCH') setEnrollError(t('Khuôn mặt chưa khớp với hồ sơ khuôn mặt của bạn (lần {0}/{1}). Ngồi thẳng, đủ sáng, bỏ khẩu trang/kính râm rồi thử lại. Hết số lần thử bạn vẫn được làm bài nhưng giám thị sẽ được báo để kiểm tra.',
      { 0: camera.identity?.attempts ?? 1, 1: camera.identity?.maxAttempts ?? 3 }))
    else if (problem) setEnrollError(ENROLL_ERROR[problem] ?? problem)
  }
  const title = status === 'lost' ? t('Camera đã ngắt') : status === 'enroll' ? t('Chụp ảnh xác nhận') : t('Bật camera để làm bài')
  const actions = status === 'enroll'
    ? <button type="button" className="btn btn-primary" data-autofocus disabled={busy} aria-busy={busy} onClick={() => void take()}><ScanFace size={16} aria-hidden /> {busy ? t('Đang kiểm tra…') : t('Chụp ảnh và bắt đầu')}</button>
    : status === 'starting' || status === 'loading'
      ? <span className="row small muted" style={{ gap: 8 }}><Spinner size={14} /> {status === 'loading' ? t('Đang tải bộ nhận diện khuôn mặt…') : t('Đang mở camera…')}</span>
      : <button type="button" className="btn btn-primary" data-autofocus onClick={() => void (status === 'lost' ? camera.resume() : camera.start())}><Camera size={16} aria-hidden /> {status === 'lost' ? t('Bật lại camera') : t('Bật camera')}</button>
  return (
    <BlockingOverlay title={title} remaining={remaining} actions={actions}>
      {status === 'lost'
        ? <p>{t('Camera bị tắt hoặc mất kết nối nên câu hỏi tạm ẩn. Việc này đã được ghi nhận. Bật lại camera để tiếp tục; đồng hồ vẫn chạy.')}</p>
        : <p>{t('Kỳ thi này giám sát bằng camera: video được quay và nén trong suốt bài làm; hệ thống kiểm tra khuôn mặt có khớp với ảnh lúc bắt đầu, có người khác hoặc bạn rời khỏi màn hình hay không. Ảnh chụp lúc có cảnh báo được gửi cho giám thị kèm lý do. Ảnh và video được tự động xóa sau thời hạn lưu trữ.')}</p>}
      {(status === 'enroll' || status === 'loading') && camera.stream && <div className="camera-enroll"><LiveVideo stream={camera.stream} className="camera-video" /><span className="camera-frame" aria-hidden /></div>}
      {status === 'enroll' && <p className="small muted">{t('Ngồi thẳng, đủ sáng, chỉ một mình trong khung hình và nhìn vào camera.')}</p>}
      {status === 'enroll' && <p className="xsmall muted">{t('Ảnh được so với hồ sơ khuôn mặt của bạn. Ở kỳ thi có camera đầu tiên, ảnh này trở thành hồ sơ để xác minh các kỳ thi sau.')}</p>}
      {enrollError && <Notice tone="danger" live>{enrollError}</Notice>}
      {status === 'denied' && <Notice tone="danger" live title={t('Trình duyệt chặn camera')}>{t('Mở cài đặt quyền của trang (biểu tượng ổ khóa trên thanh địa chỉ), cho phép Camera rồi bấm “Bật camera”.')}</Notice>}
      {status === 'unsupported' && <Notice tone="danger" live>{t('Trình duyệt này không hỗ trợ quay camera. Hãy dùng Chrome, Edge, Firefox hoặc Safari bản mới.')}</Notice>}
      {status === 'error' && <Notice tone="danger" live>{camera.error === 'NOT_FOUND' ? t('Không tìm thấy camera trên thiết bị.') : camera.error === 'MODELS' ? t('Không tải được bộ nhận diện khuôn mặt. Kiểm tra mạng rồi thử lại.') : t('Không mở được camera. Đóng ứng dụng khác đang dùng camera rồi thử lại.')}</Notice>}
      <p className="small muted">{t('Thông tin giám sát là dữ liệu để giám thị xem xét, không phải kết luận gian lận.')}</p>
    </BlockingOverlay>
  )
}

/** Small self-view while answering, the recording indicator and live warnings. */
export function CameraDock({ camera }: { camera: Camera }) {
  useT()
  const [open, setOpen] = useState(true)
  if (camera.status !== 'active') return null
  return (
    <div className="camera-dock" data-warning={camera.warning ? 'true' : undefined}>
      {camera.warning && <div className="camera-warning" role="alert"><CameraOff size={14} aria-hidden /> {LIVE_WARNING[camera.warning]}</div>}
      <button type="button" className="camera-pip" aria-label={open ? t('Thu nhỏ camera') : t('Mở camera')} aria-pressed={open} onClick={() => setOpen(v => !v)}>
        {open && <LiveVideo stream={camera.stream} className="camera-video" />}
        <span className="camera-rec"><span className="camera-rec-dot" aria-hidden /> REC</span>
      </button>
    </div>
  )
}
