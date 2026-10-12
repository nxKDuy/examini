import { useEffect } from 'react'

/**
 * One shared utility for interaction depth (CSS carries the visuals, see "Depth on interaction" in index.css):
 *  - tilt:   a single delegated pointer listener tilts the card under the mouse by at most 4 degrees (mouse + hover:hover only);
 *  - reveal: one IntersectionObserver fades each new card in once as it first enters the viewport (never again when scrolling back).
 * Both are off under prefers-reduced-motion. Keep LIFT_SELECTOR in sync with the card list in index.css.
 */
export const LIFT_SELECTOR = '.public-post, .org-card, .set-row, .grid-stats > .card'
const MAX_TILT_DEG = 4, STAGGER_MS = 40, MAX_STAGGER_STEPS = 10, REVEAL_DONE_MS = 700

function startTilt(): () => void {
  let current: HTMLElement | null = null, frame = 0, last: PointerEvent | null = null
  const reset = (el: HTMLElement) => { el.style.removeProperty('--rx'); el.style.removeProperty('--ry') }
  const apply = () => {
    frame = 0
    const event = last
    if (!event) return
    const el = (event.target as Element | null)?.closest?.(LIFT_SELECTOR) as HTMLElement | null
    if (current && current !== el) reset(current)
    current = el
    if (!el) return
    const box = el.getBoundingClientRect()
    if (!box.width || !box.height) return
    const px = (event.clientX - box.left) / box.width - 0.5, py = (event.clientY - box.top) / box.height - 0.5
    el.style.setProperty('--rx', `${(-py * 2 * MAX_TILT_DEG).toFixed(2)}deg`)
    el.style.setProperty('--ry', `${(px * 2 * MAX_TILT_DEG).toFixed(2)}deg`)
  }
  const onMove = (event: PointerEvent) => { if (event.pointerType !== 'mouse') return; last = event; if (!frame) frame = requestAnimationFrame(apply) }
  const onLeave = () => { last = null; if (current) { reset(current); current = null } }
  document.addEventListener('pointermove', onMove, { passive: true })
  document.documentElement.addEventListener('mouseleave', onLeave)
  window.addEventListener('blur', onLeave)
  return () => {
    document.removeEventListener('pointermove', onMove); document.documentElement.removeEventListener('mouseleave', onLeave); window.removeEventListener('blur', onLeave)
    if (frame) cancelAnimationFrame(frame)
    onLeave()
  }
}

function startReveal(): () => void {
  const seen = new WeakSet<Element>(), timers = new Set<number>()
  const io = new IntersectionObserver(entries => {
    entries.filter(e => e.isIntersecting).sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top || a.boundingClientRect.left - b.boundingClientRect.left).forEach((entry, index) => {
      const el = entry.target as HTMLElement
      io.unobserve(el)
      el.style.setProperty('--rd', `${Math.min(index, MAX_STAGGER_STEPS - 1) * STAGGER_MS}ms`)
      el.dataset.reveal = 'in'
      const timer = window.setTimeout(() => { timers.delete(timer); if (el.isConnected) { el.dataset.reveal = 'done'; el.style.removeProperty('--rd') } }, REVEAL_DONE_MS)
      timers.add(timer)
    })
  }, { threshold: 0.04 })
  const mark = (el: Element) => { if (seen.has(el)) return; seen.add(el); (el as HTMLElement).dataset.reveal = 'pending'; io.observe(el) }
  const scan = (node: Element) => { if (node.matches(LIFT_SELECTOR)) mark(node); node.querySelectorAll(LIFT_SELECTOR).forEach(mark) }
  // Observer callbacks run before the next paint, so a new card is hidden before it can flash.
  const mo = new MutationObserver(records => records.forEach(record => record.addedNodes.forEach(n => { if (n.nodeType === 1) scan(n as Element) })))
  mo.observe(document.body, { childList: true, subtree: true })
  scan(document.body)
  return () => {
    mo.disconnect(); io.disconnect(); timers.forEach(t => clearTimeout(t))
    document.querySelectorAll<HTMLElement>('[data-reveal]').forEach(el => { delete el.dataset.reveal; el.style.removeProperty('--rd') })
  }
}

/** Mount once (AppLayout). Re-evaluates when the user's motion/pointer preferences change. */
export function useDepthEffects() {
  useEffect(() => {
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)'), fine = window.matchMedia('(hover: hover) and (pointer: fine)')
    let stops: (() => void)[] = []
    const stop = () => { stops.forEach(s => s()); stops = [] }
    const start = () => { stop(); if (reduce.matches) return; stops.push(startReveal()); if (fine.matches) stops.push(startTilt()) }
    start()
    reduce.addEventListener('change', start); fine.addEventListener('change', start)
    return () => { reduce.removeEventListener('change', start); fine.removeEventListener('change', start); stop() }
  }, [])
}
