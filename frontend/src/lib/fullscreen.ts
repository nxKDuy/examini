// Fullscreen API with the WebKit prefix some browsers still need. The page can ask for fullscreen; it can never stop the user leaving it.
type FsDocument = Document & { webkitFullscreenEnabled?: boolean; webkitFullscreenElement?: Element | null; webkitExitFullscreen?: () => Promise<void> | void }
type FsElement = HTMLElement & { webkitRequestFullscreen?: () => Promise<void> | void }

export const FULLSCREEN_EVENTS = ['fullscreenchange', 'webkitfullscreenchange'] as const

export function fullscreenSupported() {
  if (typeof document === 'undefined') return false
  const d = document as FsDocument
  return !!(d.fullscreenEnabled ?? d.webkitFullscreenEnabled)
}

export function isFullscreen() {
  if (typeof document === 'undefined') return false
  const d = document as FsDocument
  return !!(d.fullscreenElement ?? d.webkitFullscreenElement)
}

/** Must be called synchronously inside a click handler (user gesture). Resolves false when the browser refuses. */
export function requestFullscreen(): Promise<boolean> {
  const el = document.documentElement as FsElement
  try {
    const call = el.requestFullscreen ? el.requestFullscreen() : el.webkitRequestFullscreen?.()
    return Promise.resolve(call).then(() => isFullscreen(), () => false)
  } catch {
    return Promise.resolve(false)
  }
}

export function exitFullscreen() {
  if (!isFullscreen()) return
  const d = document as FsDocument
  try { void Promise.resolve(d.exitFullscreen ? d.exitFullscreen() : d.webkitExitFullscreen?.()).catch(() => undefined) } catch { /* already left */ }
}
