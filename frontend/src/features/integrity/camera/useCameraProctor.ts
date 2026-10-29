import { useCallback, useEffect, useRef, useState } from 'react'
import { cameraApi, type CameraReport } from './cameraApi'
import { CameraRules, RECORDING, distance, recorderMime, type CameraEventType, type Violation } from './cameraModel'
import { analyse, loadFaceEngine, snapshot } from './faceEngine'
import { errorText } from '../../../lib/errors'

export type CameraStatus = 'off' | 'idle' | 'starting' | 'denied' | 'unsupported' | 'loading' | 'enroll' | 'active' | 'lost' | 'error'
const ANALYSE_MS = 1500

/**
 * Camera proctoring for one attempt: permission → enrollment photo → recording + face checks until unmount.
 * Answering never waits on uploads; failed uploads retry in the background.
 */
export function useCameraProctor(sessionId: string | undefined, required: boolean) {
  const [status, setStatus] = useState<CameraStatus>(required ? 'idle' : 'off')
  const [error, setError] = useState('')
  const [warning, setWarning] = useState<Violation | null>(null)
  const [stream, setStream] = useState<MediaStream | null>(null)
  const [uploaded, setUploaded] = useState(0)
  const video = useRef<HTMLVideoElement | null>(null)
  const streamRef = useRef<MediaStream | null>(null)
  const reference = useRef<{ descriptor: Float32Array; yaw: number; pitch: number } | null>(null)
  const recorder = useRef<MediaRecorder | null>(null)
  const seq = useRef(0)
  const queue = useRef<Promise<unknown>>(Promise.resolve())
  const loop = useRef<number | null>(null)
  const rules = useRef(new CameraRules())
  const busy = useRef(false)
  const stopped = useRef(false)
  const lostReported = useRef(false)

  // The policy arrives after mount: switching to "required" must leave "off", or the questions would not be gated.
  useEffect(() => { setStatus(s => (!required ? 'off' : s === 'off' ? 'idle' : s)) }, [required])

  const report = useCallback(async (type: CameraEventType, detail?: Record<string, number>, withImage = true) => {
    if (!sessionId) return
    const event: CameraReport = { clientEventId: crypto.randomUUID(), type, occurredAt: new Date().toISOString(), detail }
    const image = withImage && video.current ? await snapshot(video.current) : null
    for (let attempt = 0; attempt < 3; attempt++) {
      try { await cameraApi.report(sessionId, event, image); return }
      catch { await new Promise(r => setTimeout(r, 2000 * (attempt + 1))) } // same clientEventId: the server dedupes
    }
  }, [sessionId])

  /** Stops analysis and recording (the recorder flushes its last chunk) and releases the camera. Refs only: safe at unmount. */
  const stopAll = useCallback(() => {
    if (loop.current !== null) { window.clearInterval(loop.current); loop.current = null }
    if (recorder.current && recorder.current.state !== 'inactive') recorder.current.stop()
    recorder.current = null
    streamRef.current?.getTracks().forEach(t => t.stop())
  }, [])

  /** Ask for the camera and load the face models. */
  const start = useCallback(async () => {
    setError('')
    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') { setStatus('unsupported'); return }
    setStatus('starting')
    let media: MediaStream
    try {
      media = await navigator.mediaDevices.getUserMedia({ audio: false, video: { width: { ideal: RECORDING.width }, height: { ideal: RECORDING.height }, frameRate: { ideal: RECORDING.frameRate }, facingMode: 'user' } })
    } catch (e) {
      const name = (e as DOMException)?.name
      setStatus(name === 'NotAllowedError' || name === 'SecurityError' ? 'denied' : 'error')
      setError(name === 'NotFoundError' ? 'NOT_FOUND' : name ?? 'UNKNOWN')
      return
    }
    const el = document.createElement('video')
    el.muted = true; el.playsInline = true; el.srcObject = media
    await el.play().catch(() => undefined)
    video.current = el
    streamRef.current = media
    setStream(media)
    setStatus('loading')
    try { await loadFaceEngine() } catch { setStatus('error'); setError('MODELS'); media.getTracks().forEach(t => t.stop()); return }
    setStatus(reference.current ? 'active' : 'enroll')
  }, [])

  const record = useCallback((media: MediaStream) => {
    const mimeType = recorderMime(t => MediaRecorder.isTypeSupported(t))
    if (!mimeType || !sessionId) return
    const r = new MediaRecorder(media, { mimeType, videoBitsPerSecond: RECORDING.videoBitsPerSecond })
    r.ondataavailable = e => {
      if (!e.data.size) return
      const n = seq.current++
      const blob = e.data.type ? e.data : new Blob([e.data], { type: mimeType })
      // Strictly in order, each chunk retried until stored (the server ignores a seq it already has).
      queue.current = queue.current.then(async () => {
        for (let attempt = 0; attempt < 6; attempt++) {
          try { const res = await cameraApi.chunk(sessionId, n, blob); setUploaded(res.bytes); return }
          catch { await new Promise(res => setTimeout(res, Math.min(30_000, 1000 * 2 ** attempt))) }
        }
      })
    }
    r.start(RECORDING.chunkMs)
    recorder.current = r
  }, [sessionId])

  const watch = useCallback((media: MediaStream) => {
    media.getVideoTracks().forEach(track => track.addEventListener('ended', () => {
      if (stopped.current || lostReported.current) return
      lostReported.current = true
      if (loop.current !== null) { window.clearInterval(loop.current); loop.current = null }
      if (recorder.current && recorder.current.state !== 'inactive') recorder.current.stop()
      recorder.current = null
      setStatus('lost'); setWarning(null)
      void report('CAMERA_OFF', undefined, false)
    }))
    loop.current = window.setInterval(async () => {
      if (busy.current || !video.current || !reference.current) return
      busy.current = true
      try {
        const faceapi = await loadFaceEngine()
        const a = await analyse(faceapi, video.current)
        const ref = reference.current
        const obs = { at: Date.now(), faces: a.faces, distance: a.descriptor ? distance(a.descriptor, ref.descriptor) : null,
          yaw: a.yaw === null ? null : a.yaw - ref.yaw, pitch: a.pitch === null ? null : a.pitch - ref.pitch }
        setWarning(rules.current.pending(obs))
        for (const d of rules.current.observe(obs)) void report(d.type, d.detail)
      } catch { /* a skipped frame is harmless */ } finally { busy.current = false }
    }, ANALYSE_MS)
  }, [report])

  /** Enrollment: exactly one face, then the photo becomes the reference for the rest of the attempt. */
  const enroll = useCallback(async (): Promise<string | null> => {
    if (!video.current || !stream || !sessionId) return 'NO_CAMERA'
    const faceapi = await loadFaceEngine()
    const a = await analyse(faceapi, video.current)
    if (a.faces === 0 || !a.descriptor) return 'NO_FACE'
    if (a.faces > 1) return 'MULTIPLE_FACES'
    try {
      const image = await snapshot(video.current)
      await cameraApi.report(sessionId, { clientEventId: crypto.randomUUID(), type: 'REFERENCE', occurredAt: new Date().toISOString(), detail: { faces: 1 } }, image)
    } catch (e) { return errorText(e) }
    reference.current = { descriptor: a.descriptor, yaw: a.yaw ?? 0, pitch: a.pitch ?? 0 }
    record(stream); watch(stream)
    setStatus('active')
    return null
  }, [stream, sessionId, record, watch])

  // Camera came back after being lost: keep the reference, record a new segment, report it.
  const resume = useCallback(async () => {
    await start()
    lostReported.current = false
  }, [start])
  useEffect(() => {
    if (status === 'active' && stream && reference.current && !recorder.current) { record(stream); watch(stream); void report('CAMERA_ON', undefined, false) }
  }, [status, stream, record, watch, report])

  useEffect(() => { stopped.current = false; return () => { stopped.current = true; stopAll() } }, [stopAll])

  return {
    status, error, warning, stream, uploaded,
    /** Questions stay hidden until the camera runs and the reference photo is taken. */
    blocking: required && status !== 'active',
    start, enroll, resume,
  }
}
