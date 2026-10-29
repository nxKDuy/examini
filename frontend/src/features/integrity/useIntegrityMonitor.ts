import { useCallback, useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { integrityApi, type IntegrityPolicy, type Receipt, type SignalInput, type SignalMetadata, type SignalType } from './integrityApi'
import { LEAVE_WINDOW_MS, LeaveTracker, leaveCounted, monitored, retryDelay } from './integrityModel'
import { errorStatus } from '../../lib/errors'
import { FULLSCREEN_EVENTS, fullscreenSupported, isFullscreen, requestFullscreen } from '../../lib/fullscreen'

const SEEN_KEY = (sessionId: string) => `examini.integrity.seen.${sessionId}`
// Navigation evidence is sent once per document load, even if the effect re-runs (StrictMode, token refresh).
const navReported = new Set<string>()

/**
 * Reports browser signals for the frozen attempt policy and exposes the blocking states of contract A
 * (fullscreen required, leave acknowledgment). Reporting runs beside answering: failures retry in the
 * background, offline events wait for the connection and never block saving or submitting.
 */
export function useIntegrityMonitor(sessionId: string | undefined, active: boolean, onReceipt: (r: Receipt) => void) {
  const policy = useQuery({ queryKey: ['integrity-policy', sessionId], queryFn: () => integrityApi.sessionPolicy(sessionId!), enabled: !!sessionId, retry: 1, staleTime: Infinity })
  const [fullscreen, setFullscreen] = useState(isFullscreen)
  const [ack, setAck] = useState(false)
  // Another tab of this browser opened the same attempt; only the newest tab may answer.
  const [duplicate, setDuplicate] = useState(false)
  const channel = useRef<BroadcastChannel | null>(null)
  const tabId = useRef(Math.random().toString(36).slice(2))
  const receiptRef = useRef(onReceipt)
  receiptRef.current = onReceipt
  // Events that could not reach the server (offline / network error). Same clientEventId on resend: the server dedupes.
  const offlineQueue = useRef<SignalInput[]>([])
  // Verdict for the latest leave signal: the overlay asks for acknowledgment only when the server counted it.
  const leaveVerdict = useRef<{ id: string; counted?: boolean } | null>(null)
  const p: IntegrityPolicy | undefined = policy.data
  const on = !!sessionId && active && monitored(p)

  // Fullscreen state is tracked from mount, not only once the policy has loaded, so an early exit is never missed.
  useEffect(() => {
    const sync = () => setFullscreen(isFullscreen())
    sync()
    FULLSCREEN_EVENTS.forEach(e => document.addEventListener(e, sync))
    return () => FULLSCREEN_EVENTS.forEach(e => document.removeEventListener(e, sync))
  }, [])

  useEffect(() => {
    if (!on || !p || !sessionId) return
    let stopped = false
    const timers = new Set<number>()
    const later = (ms: number, fn: () => void) => { const t = window.setTimeout(() => { timers.delete(t); if (!stopped) fn() }, ms); timers.add(t); return t }
    const send = (input: SignalInput, attempt = 0) => {
      if (stopped) return
      if (!navigator.onLine) { offlineQueue.current.push(input); return }
      integrityApi.report(sessionId, input).then(r => {
        if (leaveVerdict.current?.id === input.clientEventId) {
          leaveVerdict.current.counted = leaveCounted(r)
          if (!leaveVerdict.current.counted) setAck(false)
        }
        if (!stopped) receiptRef.current(r)
      }, e => {
        const status = errorStatus(e)
        if (status === 0) { offlineQueue.current.push(input); return }
        const wait = retryDelay(status, attempt)
        if (wait !== null) later(wait, () => send(input, attempt + 1))
      })
    }
    const report = (type: SignalType, metadata?: SignalMetadata) => {
      const input: SignalInput = { clientEventId: crypto.randomUUID(), type, occurredAt: new Date().toISOString(), metadata: metadata ?? null }
      send(input)
      return input.clientEventId
    }
    const flush = () => offlineQueue.current.splice(0).forEach(i => send(i))
    flush()

    // Navigation evidence once per page load: reload of this attempt, or reopening it later.
    if (!navReported.has(sessionId)) {
      navReported.add(sessionId)
      const nav = performance.getEntriesByType?.('navigation')[0] as PerformanceNavigationTiming | undefined
      let seen = false
      try { seen = sessionStorage.getItem(SEEN_KEY(sessionId)) === '1'; sessionStorage.setItem(SEEN_KEY(sessionId), '1') } catch { /* storage blocked */ }
      if (nav?.type === 'reload' && seen) report('PAGE_RELOAD')
      else if (seen) report('SESSION_RESUME')
    }

    const leave = new LeaveTracker(p.monitorTabSwitch, p.monitorWindowBlur)
    let leaveTimer = 0, offlineAt = 0
    const emit = (s: ReturnType<LeaveTracker['onTimeout']>) => { if (s) leaveVerdict.current = { id: report(s.type, s.metadata) } }
    const back = () => {
      if (document.visibilityState !== 'visible' || !document.hasFocus()) return
      window.clearTimeout(leaveTimer)
      const r = leave.onReturn(Date.now())
      emit(r.signal)
      if (r.focusDurationMs !== null) report('WINDOW_FOCUS', { visibilityState: 'VISIBLE', durationMs: r.focusDurationMs })
      if (r.ack && leaveVerdict.current?.counted !== false) setAck(true)
    }
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') { window.clearTimeout(leaveTimer); emit(leave.onHidden(Date.now())) }
      else back()
    }
    const onBlur = () => { if (leave.onBlur(Date.now())) leaveTimer = later(LEAVE_WINDOW_MS, () => emit(leave.onTimeout())) }
    const onFullscreen = () => {
      const now = isFullscreen()
      if (!now) leave.onFullscreenExit(Date.now())
      if (!p.requireFullscreen) return
      report(now ? 'FULLSCREEN_ENTER' : 'EXIT_FULLSCREEN', now ? { fromFullscreen: false, toFullscreen: true } : { fromFullscreen: true, toFullscreen: false })
    }
    // Copy/cut/paste/drop are blocked (not only recorded) while monitored; the right-click menu goes with copy.
    const onCopy = (e: Event) => { if (p.monitorCopy) { e.preventDefault(); report('COPY') } }
    const onPaste = (e: Event) => { if (p.monitorPaste) { e.preventDefault(); report('PASTE') } }
    const onDrop = (e: DragEvent) => { if (p.monitorPaste && e.dataTransfer?.types.some(t => t === 'text/plain' || t === 'text/html' || t === 'Files')) { e.preventDefault(); report('PASTE') } }
    const onContext = (e: Event) => { if (p.monitorCopy) e.preventDefault() }
    const onOffline = () => { if (p.monitorNetwork && !offlineAt) offlineAt = Date.now() }
    const onOnline = () => {
      if (p.monitorNetwork && offlineAt) {
        // Audit only (not a violation); disconnect is reported on reconnect while the attempt is still in progress.
        const durationMs = Math.min(86_400_000, Date.now() - offlineAt); offlineAt = 0
        report('NETWORK_DISCONNECT', { networkState: 'OFFLINE', durationMs })
        report('NETWORK_RECONNECT', { networkState: 'ONLINE', durationMs })
      }
      flush()
    }
    if (!navigator.onLine) onOffline()
    document.addEventListener('visibilitychange', onVisibility)
    FULLSCREEN_EVENTS.forEach(e => document.addEventListener(e, onFullscreen))
    document.addEventListener('copy', onCopy)
    document.addEventListener('cut', onCopy)
    document.addEventListener('paste', onPaste)
    document.addEventListener('drop', onDrop)
    document.addEventListener('contextmenu', onContext)
    window.addEventListener('blur', onBlur)
    window.addEventListener('focus', back)
    window.addEventListener('offline', onOffline)
    window.addEventListener('online', onOnline)
    return () => {
      stopped = true
      timers.forEach(t => window.clearTimeout(t))
      document.removeEventListener('visibilitychange', onVisibility)
      FULLSCREEN_EVENTS.forEach(e => document.removeEventListener(e, onFullscreen))
      document.removeEventListener('copy', onCopy)
      document.removeEventListener('cut', onCopy)
      document.removeEventListener('paste', onPaste)
      document.removeEventListener('drop', onDrop)
      document.removeEventListener('contextmenu', onContext)
      window.removeEventListener('blur', onBlur)
      window.removeEventListener('focus', back)
      window.removeEventListener('offline', onOffline)
      window.removeEventListener('online', onOnline)
    }
  }, [on, p, sessionId])

  // Same attempt in two tabs of this browser: the newest claims it, older tabs are blocked until they take it back.
  useEffect(() => {
    if (!sessionId || !active || typeof BroadcastChannel === 'undefined') return
    const ch = new BroadcastChannel(`examini.attempt.${sessionId}`)
    channel.current = ch
    ch.onmessage = (e: MessageEvent<{ claim?: string }>) => { if (e.data?.claim && e.data.claim !== tabId.current) setDuplicate(true) }
    ch.postMessage({ claim: tabId.current })
    return () => { ch.close(); channel.current = null }
  }, [sessionId, active])
  const takeOver = useCallback(() => { channel.current?.postMessage({ claim: tabId.current }); setDuplicate(false) }, [])

  const enterFullscreen = useCallback(async () => { const ok = await requestFullscreen(); setFullscreen(isFullscreen()); return ok }, [])
  const current = on ? p! : null
  const supported = fullscreenSupported()
  return {
    policy: current,
    /** Policy still loading: the question UI waits so a fullscreen-required attempt never flashes its questions. */
    loading: !!sessionId && policy.isLoading,
    supported,
    fullscreen,
    /** Phones without the Fullscreen API (iPhone Safari) are never locked out; leaving the page is still recorded. */
    needsFullscreen: !!current?.requireFullscreen && !fullscreen && supported,
    fullscreenUnavailable: !!current?.requireFullscreen && !supported,
    duplicate: !!sessionId && active && duplicate,
    takeOver,
    /** Copying is blocked: the page also disables text selection on questions. */
    noCopy: !!current?.monitorCopy,
    ack: on && ack,
    acknowledge: () => setAck(false),
    enterFullscreen,
  }
}
