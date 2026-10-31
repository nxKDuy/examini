import { useRef, useState } from 'react'
import { BookOpen, Headphones, ImageOff } from 'lucide-react'
import { questionBankApi, type MediaRef, type Presentation } from '../questionApi'
import { playsUsed, recordPlay } from './mediaModel'
import { useBlobUrl } from './useBlobUrl'
import { Spinner } from '../../../components/ui'
import { t, useT } from '../../../lib/i18n'

const useMediaUrl = (id: string) => useBlobUrl(['question-media', id], () => questionBankApi.mediaBlob(id), !!id)

export function MediaImage({ id, alt, maxHeight = 360 }: { id: string; alt: string; maxHeight?: number }) {
  useT()
  const m = useMediaUrl(id)
  if (m.loading) return <span className="media-frame row muted xsmall"><Spinner size={14} /> {t('Đang tải ảnh…')}</span>
  if (!m.url) return <span className="media-frame row muted xsmall"><ImageOff size={16} aria-hidden /> {t('Không tải được ảnh')}</span>
  return <img className="media-image" src={m.url} alt={alt} style={{ maxHeight }} />
}

/**
 * Audio player. With a play limit (listening exams) the controls are replaced by a single Play button and each
 * started play is counted in this tab; seeking is not offered so a "play" means listening from the start.
 */
export function MediaAudio({ id, name, maxPlays, sessionId }: { id: string; name?: string | null; maxPlays?: number | null; sessionId?: string }) {
  useT()
  const m = useMediaUrl(id)
  const ref = useRef<HTMLAudioElement>(null)
  const store = typeof window !== 'undefined' ? safeSession() : null
  const limited = !!maxPlays && !!sessionId
  const [used, setUsed] = useState(() => (limited ? playsUsed(store, sessionId!, id) : 0))
  const [playing, setPlaying] = useState(false)
  if (m.loading) return <span className="media-frame row muted xsmall"><Spinner size={14} /> {t('Đang tải âm thanh…')}</span>
  if (!m.url) return <span className="media-frame row muted xsmall"><Headphones size={16} aria-hidden /> {t('Không tải được âm thanh')}</span>
  if (!limited) return (
    <div className="stack-sm" style={{ gap: 4 }}>
      <audio controls preload="metadata" src={m.url} style={{ width: '100%' }} aria-label={name ?? t('Đoạn âm thanh')} />
      {maxPlays ? <span className="xsmall muted">{t('Thí sinh được nghe tối đa {0} lần.', { 0: maxPlays })}</span> : null}
    </div>
  )
  const left = Math.max(0, maxPlays! - used)
  function play() {
    const a = ref.current
    if (!a || playing || left <= 0) return
    a.currentTime = 0
    void a.play().then(() => { setPlaying(true); setUsed(recordPlay(store, sessionId!, id)) }, () => setPlaying(false))
  }
  return (
    <div className="media-frame row" style={{ gap: 12, flexWrap: 'wrap' }}>
      <audio ref={ref} preload="auto" src={m.url} onEnded={() => setPlaying(false)} onPause={() => setPlaying(false)} />
      <button type="button" className="btn btn-secondary btn-sm" disabled={playing || left <= 0} onClick={play}>
        <Headphones size={14} aria-hidden /> {playing ? t('Đang phát…') : t('Nghe')}</button>
      <span className="xsmall muted" role="status">{left > 0 ? t('Còn {0}/{1} lượt nghe', { 0: left, 1: maxPlays! }) : t('Đã hết lượt nghe')}</span>
    </div>
  )
}

function safeSession() { try { return window.sessionStorage } catch { return null } }

export function MediaList({ media, maxPlays, sessionId, label }: { media?: MediaRef[] | null; maxPlays?: number | null; sessionId?: string; label: string }) {
  if (!media?.length) return null
  return (
    <div className="stack-sm">
      {media.map((m, i) => m.kind === 'IMAGE'
        ? <MediaImage key={m.id} id={m.id} alt={m.name || `${label} – ${t('hình')} ${i + 1}`} />
        : <MediaAudio key={m.id} id={m.id} name={m.name} maxPlays={maxPlays} sessionId={sessionId} />)}
    </div>
  )
}

/** Reading/listening passage shared by several questions. */
export function PassageView({ presentation, sessionId, range }: { presentation?: Presentation | null; sessionId?: string; range?: string }) {
  useT()
  const p = presentation?.passage
  if (!p) return null
  return (
    <section className="passage" aria-label={p.title || t('Bài đọc')}>
      <div className="spread" style={{ gap: 8 }}>
        <strong className="row" style={{ gap: 6 }}><BookOpen size={16} aria-hidden /> {p.title || t('Bài đọc')}</strong>
        {range && <span className="xsmall muted">{range}</span>}
      </div>
      <MediaList media={p.media} maxPlays={presentation?.maxPlays} sessionId={sessionId} label={p.title || t('Bài đọc')} />
      <div className="prewrap passage-text">{p.content}</div>
    </section>
  )
}
