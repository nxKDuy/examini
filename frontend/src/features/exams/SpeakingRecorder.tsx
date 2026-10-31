import { useEffect, useRef, useState } from 'react'
import { Mic, RotateCcw, Square, Upload } from 'lucide-react'
import { examApi } from './examApi'
import { spokenAnswer } from '../question-bank/media/mediaModel'
import { useBlobUrl } from '../question-bank/media/useBlobUrl'
import { Notice, Spinner } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { formatDuration } from '../../lib/format'
import { t, useT } from '../../lib/i18n'

const PREFERRED = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus', 'audio/mp4']
function pickType() {
  if (typeof MediaRecorder === 'undefined') return null
  return PREFERRED.find(m => { try { return MediaRecorder.isTypeSupported(m) } catch { return false } }) ?? ''
}

/**
 * Records a voice answer in the browser and uploads it as soon as recording stops. A new recording replaces the old
 * one. The limit comes from the question; recording stops by itself when it is reached.
 */
export default function SpeakingRecorder({ sessionId, questionId, value, maxSeconds, disabled, onSaved }: {
  sessionId: string; questionId: string; value: string; maxSeconds: number; disabled: boolean; onSaved: (answerJson: string) => void
}) {
  useT()
  const saved = spokenAnswer(value)
  const [rev, setRev] = useState(0)
  const remote = useBlobUrl(['answer-audio', sessionId, questionId, rev, saved?.bytes], () => examApi.answerAudioBlob(sessionId, questionId), !!saved)
  const [state, setState] = useState<'idle' | 'asking' | 'recording' | 'uploading'>('idle')
  const [elapsed, setElapsed] = useState(0)
  const [error, setError] = useState('')
  const [pending, setPending] = useState<{ blob: Blob; seconds: number } | null>(null)
  const rec = useRef<MediaRecorder | null>(null)
  const stream = useRef<MediaStream | null>(null)
  const started = useRef(0)
  const tick = useRef<number | undefined>(undefined)
  const supported = typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia && pickType() !== null

  useEffect(() => () => { window.clearInterval(tick.current); stream.current?.getTracks().forEach(tr => tr.stop()) }, [])

  async function upload(blob: Blob, seconds: number) {
    setState('uploading'); setError('')
    try {
      const r = await examApi.answerAudio(sessionId, questionId, blob, seconds)
      setPending(null); setRev(n => n + 1)
      onSaved(JSON.stringify({ audio: true, seconds: r.seconds, bytes: r.bytes, type: r.type }))
    } catch (e) {
      setPending({ blob, seconds }); setError(errorText(e))
    } finally { setState('idle') }
  }

  async function start() {
    setError(''); setState('asking')
    try {
      const s = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } })
      stream.current = s
      const type = pickType() || undefined
      const r = new MediaRecorder(s, type ? { mimeType: type, audioBitsPerSecond: 48_000 } : undefined)
      const chunks: Blob[] = []
      r.ondataavailable = e => { if (e.data.size) chunks.push(e.data) }
      r.onstop = () => {
        window.clearInterval(tick.current)
        s.getTracks().forEach(tr => tr.stop())
        const seconds = Math.max(1, Math.min(maxSeconds, Math.round((Date.now() - started.current) / 1000)))
        const blob = new Blob(chunks, { type: (r.mimeType || 'audio/webm').split(';')[0] })
        if (blob.size) void upload(blob, seconds); else setState('idle')
      }
      rec.current = r
      started.current = Date.now(); setElapsed(0)
      r.start(1000)
      setState('recording')
      tick.current = window.setInterval(() => {
        const sec = Math.floor((Date.now() - started.current) / 1000)
        setElapsed(sec)
        if (sec >= maxSeconds) stop()
      }, 250)
    } catch (e) {
      setState('idle')
      const name = (e as { name?: string })?.name
      setError(name === 'NotAllowedError' ? t('Trình duyệt chưa được cấp quyền micro. Cho phép micro rồi bấm Ghi âm lại.')
        : name === 'NotFoundError' ? t('Không tìm thấy micro trên thiết bị.') : t('Không bật được micro.'))
    }
  }
  function stop() { if (rec.current?.state === 'recording') rec.current.stop() }

  if (!supported) return <Notice tone="warning">{t('Trình duyệt này không hỗ trợ ghi âm. Hãy dùng Chrome, Edge, Firefox hoặc Safari mới.')}</Notice>
  const recording = state === 'recording'
  return (
    <div className="recorder" aria-live="polite">
      <div className="spread" style={{ gap: 8, flexWrap: 'wrap' }}>
        <span className="small">{t('Trả lời bằng giọng nói, tối đa {0}.', { 0: formatDuration(maxSeconds) })}</span>
        {recording && <span className="row small" style={{ gap: 6 }}><span className="recorder-dot" aria-hidden /> {t('Đang ghi')} {formatDuration(elapsed)} / {formatDuration(maxSeconds)}</span>}
      </div>
      <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
        {recording
          ? <button type="button" className="btn btn-primary" onClick={stop}><Square size={16} aria-hidden /> {t('Dừng và lưu')}</button>
          : <button type="button" className="btn btn-secondary" disabled={disabled || state !== 'idle'} onClick={() => void start()}>
              {saved ? <RotateCcw size={16} aria-hidden /> : <Mic size={16} aria-hidden />} {state === 'asking' ? t('Đang bật micro…') : saved ? t('Ghi âm lại') : t('Bắt đầu ghi âm')}</button>}
        {state === 'uploading' && <span className="row small muted" style={{ gap: 6 }}><Spinner size={14} /> {t('Đang lưu bản ghi…')}</span>}
        {pending && state === 'idle' && <button type="button" className="btn btn-secondary" disabled={disabled} onClick={() => void upload(pending.blob, pending.seconds)}><Upload size={16} aria-hidden /> {t('Thử lưu lại')}</button>}
      </div>
      {saved && !recording && <div className="stack-sm" style={{ gap: 4 }}>
        <span className="xsmall muted">{t('Đã lưu bản ghi {0}. Ghi lại sẽ thay bản cũ.', { 0: formatDuration(saved.seconds) })}</span>
        {remote.url ? <audio controls preload="metadata" src={remote.url} style={{ width: '100%' }} aria-label={t('Bản ghi của bạn')} />
          : remote.loading ? <span className="xsmall muted">{t('Đang tải bản ghi…')}</span> : null}
      </div>}
      {error && <span className="field-error" role="alert">{error}</span>}
    </div>
  )
}
