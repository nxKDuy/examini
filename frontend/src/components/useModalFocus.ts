import { useEffect, useRef, type RefObject } from 'react'

const FOCUSABLE = 'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])'

// Modals are a stack: only the top-most one handles Escape/Tab, and background scroll stays locked until the last one closes.
const stack: symbol[] = []
let locks = 0
let savedOverflow = ''
const lock = () => { if (locks++ === 0) { savedOverflow = document.body.style.overflow; document.body.style.overflow = 'hidden' } }
const unlock = () => { if (--locks === 0) document.body.style.overflow = savedOverflow }

interface Options {
  /** Keep Tab inside the node and lock background scroll (modal behaviour). */
  trap?: boolean
  /** Handle Escape (only while this is the top-most modal, or no modal is open for a non-modal panel). */
  escape?: boolean
  onEscape?: () => void
  /** Element to focus on open; default is [data-autofocus] or the first focusable. */
  initialFocus?: () => HTMLElement | null
}

/** Focus management shared by Dialog and the calendar detail panel (bottom sheet on narrow screens). */
export function useModalFocus(ref: RefObject<HTMLElement | null>, { trap = true, escape = true, onEscape, initialFocus }: Options) {
  const latest = useRef({ onEscape, initialFocus })
  useEffect(() => { latest.current = { onEscape, initialFocus } })
  useEffect(() => {
    const node = ref.current
    if (!node) return
    const opener = document.activeElement as HTMLElement | null
    const token = Symbol('modal')
    if (trap) { stack.push(token); lock() }
    const first = latest.current.initialFocus?.() ?? node.querySelector<HTMLElement>('[data-autofocus]') ?? node.querySelector<HTMLElement>(FOCUSABLE)
    first?.focus()
    function onKey(e: KeyboardEvent) {
      const top = stack.length === 0 || stack[stack.length - 1] === token
      if (!top) return
      if (e.key === 'Escape' && escape) { e.stopPropagation(); latest.current.onEscape?.(); return }
      if (e.key !== 'Tab' || !trap || !node) return
      const items = Array.from(node.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(el => el.offsetParent !== null)
      if (items.length === 0) { e.preventDefault(); node.focus(); return }
      const head = items[0], tail = items[items.length - 1], active = document.activeElement
      if (!node.contains(active)) { e.preventDefault(); (e.shiftKey ? tail : head).focus() }
      else if (e.shiftKey && (active === head || active === node)) { e.preventDefault(); tail.focus() }
      else if (!e.shiftKey && active === tail) { e.preventDefault(); head.focus() }
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      const at = stack.indexOf(token)
      if (at >= 0) { stack.splice(at, 1); unlock() }
      if (opener && opener.isConnected) opener.focus()
    }
  }, [ref, trap, escape])
}
