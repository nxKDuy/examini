import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { integrityApi, type IntegrityPolicy, type Receipt, type SignalInput, type SignalMetadata, type SignalType } from './integrityApi'
import { monitored, retryDelay } from './integrityModel'
import { errorStatus } from '../../lib/errors'

const SEEN_KEY = (sessionId: string) => `examini.integrity.seen.${sessionId}`
// Navigation evidence is sent once per document load, even if the effect re-runs (StrictMode, token refresh).
const navReported = new Set<string>()

/**
 * Reports browser signals for the frozen attempt policy. Reporting runs beside answering:
 * failures and 429 retry in the background and never block saving or submitting.
 */
export function useIntegrityMonitor(sessionId: string | undefined, active: boolean, onReceipt: (r: Receipt) => void) {
  const policy = useQuery({ queryKey: ['integrity-policy', sessionId], queryFn: () => integrityApi.sessionPolicy(sessionId!), enabled: !!sessionId, retry: 1, staleTime: Infinity })
  const [fullscreen, setFullscreen] = useState(() => typeof document !== 'undefined' && !!document.fullscreenElement)
  const receiptRef = useRef(onReceipt)
  receiptRef.current = onReceipt
  const p: IntegrityPolicy | undefined = policy.data
  const on = !!sessionId && active && monitored(p)

  useEffect(() => {
    if (!on || !p || !sessionId) return
    let stopped = false
    const timers = new Set<number>()
    const send = (input: SignalInput, attempt = 0) => {
      if (stopped) return
      integrityApi.report(sessionId, input).then(r => { if (!stopped) receiptRef.current(r) }, e => {
        // Same clientEventId and payload on retry: the server dedupes exact retries.
        const wait = retryDelay(errorStatus(e), attempt)
        if (wait === null || stopped) return
        const t = window.setTimeout(() => { timers.delete(t); send(input, attempt + 1) }, wait)
        timers.add(t)
      })
    }
    const report = (type: SignalType, metadata?: SignalMetadata) =>
      send({ clientEventId: crypto.randomUUID(), type, occurredAt: new Date().toISOString(), metadata: metadata ?? null })

    // Navigation evidence once per page load: reload of this attempt, or reopening it later.
    if (!navReported.has(sessionId)) {
      navReported.add(sessionId)
      const nav = performance.getEntriesByType?.('navigation')[0] as PerformanceNavigationTiming | undefined
      let seen = false
      try { seen = sessionStorage.getItem(SEEN_KEY(sessionId)) === '1'; sessionStorage.setItem(SEEN_KEY(sessionId), '1') } catch { /* storage blocked */ }
      if (nav?.type === 'reload' && seen) report('PAGE_RELOAD')
      else if (seen) report('SESSION_RESUME')
    }

    let hiddenAt = 0, blurAt = 0, offlineAt = 0
    const onVisibility = () => {
      if (!p.monitorTabSwitch) return
      if (document.visibilityState === 'hidden') { hiddenAt = Date.now(); report('TAB_SWITCH', { visibilityState: 'HIDDEN' }) }
      else hiddenAt = 0
    }
    const onBlur = () => {
      if (!p.monitorWindowBlur) return
      // A tab switch also blurs the window; count that once, as TAB_SWITCH.
      window.setTimeout(() => {
        if (stopped || document.visibilityState === 'hidden' || hiddenAt) return
        blurAt = Date.now(); report('WINDOW_BLUR', { visibilityState: 'VISIBLE' })
      }, 150)
    }
    const onFocus = () => {
      if (!p.monitorWindowBlur || !blurAt) return
      report('WINDOW_FOCUS', { visibilityState: 'VISIBLE', durationMs: Math.min(86_400_000, Date.now() - blurAt) }); blurAt = 0
    }
    const onFullscreen = () => {
      const now = !!document.fullscreenElement
      setFullscreen(now)
      if (!p.requireFullscreen) return
      report(now ? 'FULLSCREEN_ENTER' : 'EXIT_FULLSCREEN', now ? { fromFullscreen: false, toFullscreen: true } : { fromFullscreen: true, toFullscreen: false })
    }
    const onCopy = () => { if (p.monitorCopy) report('COPY') }
    const onPaste = () => { if (p.monitorPaste) report('PASTE') }
    const onOffline = () => { if (p.monitorNetwork) offlineAt = Date.now() }
    const onOnline = () => {
      if (!p.monitorNetwork || !offlineAt) return
      // Disconnect is reported on reconnect, while the attempt is still in progress.
      const durationMs = Math.min(86_400_000, Date.now() - offlineAt); offlineAt = 0
      report('NETWORK_DISCONNECT', { networkState: 'OFFLINE', durationMs })
      report('NETWORK_RECONNECT', { networkState: 'ONLINE', durationMs })
    }
    document.addEventListener('visibilitychange', onVisibility)
    document.addEventListener('fullscreenchange', onFullscreen)
    document.addEventListener('copy', onCopy)
    document.addEventListener('paste', onPaste)
    window.addEventListener('blur', onBlur)
    window.addEventListener('focus', onFocus)
    window.addEventListener('offline', onOffline)
    window.addEventListener('online', onOnline)
    return () => {
      stopped = true
      timers.forEach(t => window.clearTimeout(t))
      document.removeEventListener('visibilitychange', onVisibility)
      document.removeEventListener('fullscreenchange', onFullscreen)
      document.removeEventListener('copy', onCopy)
      document.removeEventListener('paste', onPaste)
      window.removeEventListener('blur', onBlur)
      window.removeEventListener('focus', onFocus)
      window.removeEventListener('offline', onOffline)
      window.removeEventListener('online', onOnline)
    }
  }, [on, p, sessionId])

  return { policy: on ? p! : null, fullscreen, enterFullscreen: () => document.documentElement.requestFullscreen?.().catch(() => undefined) }
}
