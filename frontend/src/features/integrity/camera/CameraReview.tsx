import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Download, Film, RefreshCw } from 'lucide-react'
import { cameraApi, type CameraEvent, type IdentityOutcome, type SessionCamera, type SessionCameraSummary } from './cameraApi'
import { Dialog } from '../../../components/Dialog'
import { Badge, EmptyState, ErrorState, LoadingState, Notice, Section, Spinner } from '../../../components/ui'
import { formatDateTime, shortId } from '../../../lib/format'
import { errorText } from '../../../lib/errors'
import { nameOf, useParticipantNames, type NameMap } from '../../participants/useParticipantNames'
import { PersonCell } from '../../participants/PersonCell'
import { t, useT } from '../../../lib/i18n'
import { CAMERA_LABEL, VIOLATIONS, bytesText, detailText } from './cameraLabels'

/** Proctor view of camera evidence for an exam: attempts ranked by violations, then one attempt in detail. */
export default function CameraReview({ examId, live }: { examId: string; live: boolean }) {
  useT()
  const [open, setOpen] = useState<SessionCameraSummary | null>(null)
  const list = useQuery({ queryKey: ['camera', 'exam', examId], queryFn: () => cameraApi.exam(examId), refetchInterval: live ? 15_000 : false, retry: false })
  const days = list.data?.find(s => s.retention)?.retention?.days
  const names = useParticipantNames(examId)
  return (
    <Section title={t('Giám sát bằng camera')} bodyClassName=""
      description={<>{t('Ảnh chụp và video là bằng chứng để giám thị xem xét, không phải kết luận gian lận. Hệ thống không tự xử lý theo camera.')}
        {days ? <> {t('Ảnh và video tự xóa sau {0} ngày kể từ khi hết giờ làm bài; nhật ký cảnh báo vẫn được giữ.', { 0: days })}</> : null}</>}
      actions={<button type="button" className="btn btn-ghost btn-sm" onClick={() => void list.refetch()} disabled={list.isFetching}><RefreshCw size={14} aria-hidden /> {t('Làm mới')}</button>}>
      {list.isLoading ? <LoadingState rows={2} /> : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} />
        : !list.data?.length ? <EmptyState title={t('Chưa có dữ liệu camera')} description={t('Dữ liệu xuất hiện khi thí sinh bắt đầu làm bài có bật camera.')} />
        : <div className="table-wrap"><table className="pt-table">
          <thead><tr><th scope="col">{t('Thí sinh')}</th><th scope="col">{t('Cảnh báo')}</th><th scope="col">{t('Chi tiết')}</th><th scope="col">{t('Video')}</th><th scope="col">{t('Gần nhất')}</th></tr></thead>
          <tbody>{list.data.map(s => <tr key={s.sessionId}>
            <td><button type="button" className="pt-row-btn small" onClick={() => setOpen(s)}><PersonCell names={names.data} userId={s.userId} /></button></td>
            <td>{s.violations > 0 ? <Badge tone="danger">{s.violations}</Badge> : <Badge tone="muted">0</Badge>}</td>
            <td className="xsmall">{s.identity && <><IdentityBadge outcome={s.identity} />{' '}</>}{VIOLATIONS.filter(v => s.counts[v]).map(v => `${CAMERA_LABEL[v]} ${s.counts[v]}`).join(' · ') || '–'}</td>
            <td className="xsmall muted">{s.retention?.purgedAt ? t('Đã xóa') : s.recording ? bytesText(s.recording.bytes) : t('Chưa có')}</td>
            <td className="xsmall muted">{s.lastEventAt ? formatDateTime(s.lastEventAt) : '–'}</td>
          </tr>)}</tbody>
        </table></div>}
      {open && <SessionCameraDialog summary={open} names={names.data} onClose={() => setOpen(null)} />}
    </Section>
  )
}

/** Fetches binary evidence with the user's token and exposes it as an object URL. */
function useBlobUrl(key: unknown[], load: () => Promise<Blob>, enabled = true) {
  const q = useQuery({ queryKey: key, queryFn: load, enabled, staleTime: Infinity, retry: false })
  const [url, setUrl] = useState<string | null>(null)
  useEffect(() => {
    if (!q.data) return
    const u = URL.createObjectURL(q.data); setUrl(u)
    return () => URL.revokeObjectURL(u)
  }, [q.data])
  return { url, loading: q.isLoading && enabled, error: q.error }
}

function Snapshot({ sessionId, event, large, purged }: { sessionId: string; event: CameraEvent; large?: boolean; purged?: boolean }) {
  const img = useBlobUrl(['camera', 'snapshot', event.id], () => cameraApi.snapshot(sessionId, event.id), event.hasSnapshot)
  if (!event.hasSnapshot) return <div className="camera-thumb camera-thumb-empty">{purged ? t('Đã xóa') : t('Không có ảnh')}</div>
  return img.url ? <img className={large ? 'camera-thumb camera-thumb-lg' : 'camera-thumb'} src={img.url} alt={`${CAMERA_LABEL[event.type]} · ${formatDateTime(event.receivedAt)}`} />
    : <div className="camera-thumb camera-thumb-empty">{img.loading ? <Spinner size={14} /> : t('Không tải được ảnh')}</div>
}

const IDENTITY: Record<IdentityOutcome, { label: string; tone: 'success' | 'danger' | 'info' }> = {
  get ENROLLED() { return { label: t('Hồ sơ mới'), tone: 'info' as const } },
  get MATCH() { return { label: t('Khớp hồ sơ'), tone: 'success' as const } },
  get MISMATCH() { return { label: t('Không khớp hồ sơ'), tone: 'danger' as const } },
}
function IdentityBadge({ outcome }: { outcome: IdentityOutcome }) { return <Badge tone={IDENTITY[outcome].tone}>{IDENTITY[outcome].label}</Badge> }

/** Profile photo next to the outcome of the entry check, and the owner's reset. */
function IdentityPanel({ camera }: { camera: SessionCamera }) {
  useT()
  const queryClient = useQueryClient()
  const [confirm, setConfirm] = useState(false)
  const photo = useBlobUrl(['camera', 'profile', camera.sessionId], () => cameraApi.profilePhoto(camera.sessionId), Boolean(camera.profile))
  const reset = useMutation({
    mutationFn: () => cameraApi.resetProfile(camera.sessionId),
    onSuccess: () => { setConfirm(false); void queryClient.invalidateQueries({ queryKey: ['camera'] }) },
  })
  const id = camera.identity
  const text = !id ? t('Lượt này chưa xác minh với hồ sơ khuôn mặt.')
    : id.outcome === 'ENROLLED' ? t('Kỳ thi có camera đầu tiên của thí sinh: ảnh lúc bắt đầu được lưu làm hồ sơ khuôn mặt.')
    : id.outcome === 'MATCH' ? t('Ảnh lúc bắt đầu khớp hồ sơ (độ lệch {0}, ngưỡng {1}).', { 0: id.distance ?? '–', 1: id.threshold })
    : t('Không khớp hồ sơ sau {0} lần thử (độ lệch {1}, ngưỡng {2}). Thí sinh vẫn được làm bài; hãy so ảnh để kiểm tra.', { 0: id.attempts, 1: id.distance ?? '–', 2: id.threshold })
  return (
    <section className="camera-identity" aria-label={t('Xác minh khuôn mặt')}>
      <figure className="stack-sm" style={{ margin: 0 }}>
        <figcaption className="field-label">{t('Hồ sơ khuôn mặt')}</figcaption>
        {!camera.profile ? <div className="camera-thumb camera-thumb-empty">{t('Chưa có hồ sơ')}</div>
          : photo.url ? <img className="camera-thumb camera-thumb-lg" src={photo.url} alt={t('Ảnh hồ sơ khuôn mặt của thí sinh')} />
          : <div className="camera-thumb camera-thumb-empty">{photo.loading ? <Spinner size={14} /> : t('Không tải được ảnh')}</div>}
      </figure>
      <div className="stack-sm" style={{ minWidth: 0 }}>
        <span className="field-label">{t('Xác minh lúc vào thi')}</span>
        <div>{id ? <IdentityBadge outcome={id.outcome} /> : <Badge tone="muted">{t('Chưa xác minh')}</Badge>}</div>
        <p className="small" style={{ margin: 0 }}>{text}</p>
        {id?.profileCreatedAt && <p className="xsmall muted" style={{ margin: 0 }}>{t('Hồ sơ tạo lúc {0}', { 0: formatDateTime(id.profileCreatedAt) })}</p>}
        {camera.profile && (confirm
          ? <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
              <button type="button" className="btn btn-danger btn-sm" disabled={reset.isPending} aria-busy={reset.isPending} onClick={() => reset.mutate()}>{t('Xác nhận đặt lại')}</button>
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => setConfirm(false)}>{t('Hủy')}</button>
              <span className="xsmall muted">{t('Kỳ thi có camera tiếp theo của thí sinh sẽ tạo hồ sơ mới.')}</span>
            </div>
          : <button type="button" className="btn btn-ghost btn-sm" style={{ alignSelf: 'flex-start', justifySelf: 'start' }} onClick={() => setConfirm(true)}>{t('Đặt lại hồ sơ khuôn mặt')}</button>)}
        {reset.isError && <p className="field-error">{errorText(reset.error)}</p>}
      </div>
    </section>
  )
}

function SessionCameraDialog({ summary, names, onClose }: { summary: SessionCameraSummary; names?: NameMap; onClose: () => void }) {
  useT()
  const [wantVideo, setWantVideo] = useState(false)
  const detail = useQuery({ queryKey: ['camera', 'session', summary.sessionId], queryFn: () => cameraApi.session(summary.sessionId), retry: false })
  const video = useBlobUrl(['camera', 'video', summary.sessionId, summary.recording?.chunks], () => cameraApi.video(summary.sessionId), wantVideo)
  const d = detail.data
  const purged = Boolean(d?.retention?.purgedAt)
  return (
    <Dialog size="lg" title={t('Camera · {0}', { 0: nameOf(names, summary.userId) })} onClose={onClose}
      description={t('Lượt {0} · {1} cảnh báo', { 0: shortId(summary.sessionId), 1: summary.violations })}
      footer={<button type="button" className="btn btn-secondary" onClick={onClose}>{t('Đóng')}</button>}>
      {detail.isLoading ? <LoadingState rows={3} /> : detail.isError ? <ErrorState error={detail.error} onRetry={() => void detail.refetch()} /> : d && <div className="stack-lg">
        {d.retention?.purgedAt
          ? <Notice tone="info" title={t('Đã xóa ảnh và video')}>{t('Đã xóa ngày {0} theo hạn lưu trữ {1} ngày. Nhật ký cảnh báo bên dưới vẫn được giữ.', { 0: formatDateTime(d.retention.purgedAt), 1: d.retention.days })}</Notice>
          : d.retention?.deleteAt ? <p className="xsmall muted">{t('Ảnh và video sẽ tự xóa vào {0}. Tải video về nếu cần lưu lâu hơn.', { 0: formatDateTime(d.retention.deleteAt) })}</p> : null}
        <div className="camera-review-top">
          <figure className="stack-sm" style={{ margin: 0 }}>
            <figcaption className="field-label">{t('Ảnh xác nhận lúc bắt đầu')}</figcaption>
            {d.reference ? <Snapshot sessionId={d.sessionId} event={d.reference} large purged={purged} /> : <p className="small muted">{t('Thí sinh chưa chụp ảnh xác nhận.')}</p>}
          </figure>
          <div className="stack-sm" style={{ minWidth: 0 }}>
            <span className="field-label">{t('Video quay lại')}</span>
            {purged ? <p className="small muted">{t('Video đã được xóa theo hạn lưu trữ.')}</p>
              : !d.recording ? <p className="small muted">{t('Chưa có video.')}</p>
              : video.url ? <>
                  <video className="camera-player" src={video.url} controls preload="metadata" />
                  <a className="btn btn-ghost btn-sm" style={{ alignSelf: 'flex-start', justifySelf: 'start' }} href={video.url} download={`camera-${shortId(summary.userId)}-${shortId(summary.sessionId)}.${d.recording.mimeType.includes('mp4') ? 'mp4' : 'webm'}`}>
                    <Download size={14} aria-hidden /> {t('Tải về máy')}</a></>
              : <button type="button" className="btn btn-secondary" style={{ alignSelf: 'flex-start', justifySelf: 'start' }} disabled={video.loading} aria-busy={video.loading} onClick={() => setWantVideo(true)}>
                  <Film size={16} aria-hidden /> {video.loading ? t('Đang tải video…') : t('Tải video ({0})', { 0: bytesText(d.recording.bytes) })}</button>}
            {d.recording && !purged && <p className="xsmall muted">{t('{0} đoạn · bắt đầu {1}', { 0: d.recording.chunks, 1: formatDateTime(d.recording.startedAt) })}</p>}
            {video.error ? <p className="field-error">{t('Không tải được video.')}</p> : null}
          </div>
        </div>
        <IdentityPanel camera={d} />
        <section className="stack-sm" aria-label={t('Cảnh báo camera')}>
          <span className="field-label">{t('Cảnh báo ({0})', { 0: d.events.length })}</span>
          {!d.events.length ? <p className="small muted">{t('Không có cảnh báo nào.')}</p>
            : <ol className="camera-events">{d.events.map(e => <li key={e.id} className="camera-event">
              <Snapshot sessionId={d.sessionId} event={e} purged={purged} />
              <div className="stack-sm" style={{ gap: 2, minWidth: 0 }}>
                <strong className="small">{e.reason}</strong>
                <span className="xsmall muted">{formatDateTime(e.occurredAt ?? e.receivedAt)}</span>
                {detailText(e) && <span className="xsmall">{detailText(e)}</span>}
              </div>
            </li>)}</ol>}
        </section>
      </div>}
    </Dialog>
  )
}
