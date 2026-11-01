import { Children, Fragment, isValidElement, useEffect, useId, useLayoutEffect, useMemo, useRef, useState,
  type CSSProperties, type KeyboardEvent, type ReactElement, type ReactNode } from 'react'
import { Check, ChevronDown, Search } from 'lucide-react'
import { t, useT } from '../lib/i18n'

/** Same shape as a native change event's target, so `<select onChange={e => f(e.target.value)}>` ports by renaming the tag. */
export interface SelectChange { target: { value: string } }
export interface SelectItem { value: string; label: string; disabled?: boolean }

interface Props {
  value: string | number | undefined
  onChange: (e: SelectChange) => void
  /** `<option value="…">label</option>` children, as with a native select (arrays and fragments allowed). */
  children: ReactNode
  id?: string
  className?: string
  style?: CSSProperties
  disabled?: boolean
  required?: boolean
  'aria-label'?: string
  'aria-invalid'?: boolean | 'true' | 'false'
  /** Label of the mobile sheet; defaults to aria-label or the selected text. */
  title?: string
}

const SEARCH_FROM = 8
const SHEET_QUERY = '(max-width: 640px)'
const fold = (s: string) => s.normalize('NFD').replace(/\p{M}/gu, '').replace(/đ/g, 'd').replace(/Đ/g, 'D').toLowerCase()
const text = (node: ReactNode): string =>
  Children.toArray(node).map(n => (typeof n === 'string' || typeof n === 'number' ? String(n) : isValidElement<{ children?: ReactNode }>(n) ? text(n.props.children) : '')).join('')

/** Flattens `<option>` children (inside arrays/fragments) into items. */
function readOptions(children: ReactNode): SelectItem[] {
  const out: SelectItem[] = []
  const walk = (nodes: ReactNode) => Children.forEach(nodes, n => {
    if (!isValidElement(n)) return
    const el = n as ReactElement<{ value?: string | number; children?: ReactNode; disabled?: boolean }>
    if (el.type === Fragment) { walk(el.props.children); return }
    if (el.type === 'option') {
      const label = text(el.props.children)
      out.push({ value: el.props.value === undefined ? label : String(el.props.value), label, disabled: el.props.disabled })
    }
  })
  walk(children)
  return out
}

/**
 * Styled, accessible replacement for a native <select>: a combobox button with a listbox popover on desktop and
 * a bottom sheet on phones. Search appears for long lists; arrows, Home/End, Enter, Escape and type-ahead work.
 * The panel stays inside the component's DOM (position: fixed), so dialogs keep their focus trap.
 */
export default function Select({ value, onChange, children, id, className = 'input', style, disabled, required, title, ...aria }: Props) {
  useT()
  const autoId = useId()
  const triggerId = id ?? `sel-${autoId}`
  const listId = `${triggerId}-list`
  const items = useMemo(() => readOptions(children), [children])
  const current = String(value ?? '')
  const selected = items.find(i => i.value === current)
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)
  const [place, setPlace] = useState<CSSProperties>({})
  const [sheet, setSheet] = useState(false)
  const root = useRef<HTMLDivElement>(null)
  const button = useRef<HTMLButtonElement>(null)
  const list = useRef<HTMLUListElement>(null)
  const search = useRef<HTMLInputElement>(null)
  const typed = useRef({ text: '', at: 0 })

  const searchable = items.length >= SEARCH_FROM
  const shown = useMemo(() => {
    const q = fold(query.trim())
    return q ? items.filter(i => fold(i.label).includes(q)) : items
  }, [items, query])

  const close = (refocus = true) => { setOpen(false); setQuery(''); if (refocus) button.current?.focus() }
  const choose = (item: SelectItem | undefined) => {
    if (!item || item.disabled) return
    if (item.value !== current) onChange({ target: { value: item.value } })
    close()
  }
  const openList = () => {
    if (disabled) return
    const at = Math.max(0, items.findIndex(i => i.value === current))
    setActive(at); setQuery(''); setOpen(true)
  }

  // Place the panel under (or above) the trigger; on phones it becomes a bottom sheet.
  useLayoutEffect(() => {
    if (!open) return
    const update = () => {
      const isSheet = window.matchMedia(SHEET_QUERY).matches
      setSheet(isSheet)
      if (isSheet || !button.current) { setPlace({}); return }
      const r = button.current.getBoundingClientRect()
      const below = window.innerHeight - r.bottom - 12, above = r.top - 12
      const up = below < 240 && above > below
      const maxHeight = Math.max(160, Math.min(360, up ? above : below))
      setPlace({ left: r.left, width: Math.max(r.width, 220), maxHeight, ...(up ? { bottom: window.innerHeight - r.top + 4 } : { top: r.bottom + 4 }) })
    }
    update()
    window.addEventListener('resize', update)
    window.addEventListener('scroll', update, true)
    return () => { window.removeEventListener('resize', update); window.removeEventListener('scroll', update, true) }
  }, [open])

  useEffect(() => {
    if (!open) return
    ;(searchable ? search.current : list.current)?.focus({ preventScroll: true })
    const outside = (e: PointerEvent) => { if (!root.current?.contains(e.target as Node)) close(false) }
    document.addEventListener('pointerdown', outside)
    return () => document.removeEventListener('pointerdown', outside)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- (re)bind only when opening
  }, [open])

  useEffect(() => { setActive(a => Math.min(a, Math.max(0, shown.length - 1))) }, [shown.length])
  useEffect(() => {
    if (open) list.current?.querySelector<HTMLElement>(`[data-index="${active}"]`)?.scrollIntoView({ block: 'nearest' })
  }, [active, open])

  const move = (from: number, step: number) => {
    if (!shown.length) return from
    let i = from
    for (let n = 0; n < shown.length; n++) {
      i = (i + step + shown.length) % shown.length
      if (!shown[i].disabled) return i
    }
    return from
  }

  const onListKey = (e: KeyboardEvent) => {
    switch (e.key) {
      case 'ArrowDown': e.preventDefault(); setActive(a => move(a, 1)); return
      case 'ArrowUp': e.preventDefault(); setActive(a => move(a, -1)); return
      case 'Home': if (!searchable || e.target !== search.current) { e.preventDefault(); setActive(move(-1, 1)) } return
      case 'End': if (!searchable || e.target !== search.current) { e.preventDefault(); setActive(move(shown.length, -1)) } return
      case 'Enter': e.preventDefault(); choose(shown[active]); return
      case ' ': if (e.target !== search.current) { e.preventDefault(); choose(shown[active]) } return
      case 'Escape': e.preventDefault(); e.stopPropagation(); close(); return
      case 'Tab': close(false); return
    }
    // Type-ahead when there is no search box.
    if (!searchable && e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) {
      const now = Date.now()
      typed.current = { text: (now - typed.current.at < 700 ? typed.current.text : '') + fold(e.key), at: now }
      const hit = shown.findIndex(i => !i.disabled && fold(i.label).startsWith(typed.current.text))
      if (hit >= 0) setActive(hit)
    }
  }

  const onButtonKey = (e: KeyboardEvent) => {
    if (['ArrowDown', 'ArrowUp', 'Enter', ' '].includes(e.key)) { e.preventDefault(); openList() }
  }

  const label = selected?.label ?? items[0]?.label ?? ''
  const placeholder = !selected || selected.value === ''
  return (
    <div className="select" ref={root} data-open={open || undefined}>
      <button ref={button} type="button" id={triggerId} className={`${className} select-trigger`} style={style} disabled={disabled}
        role="combobox" aria-haspopup="listbox" aria-expanded={open} aria-controls={open ? listId : undefined} aria-required={required || undefined}
        aria-label={aria['aria-label']} aria-invalid={aria['aria-invalid']} onClick={() => (open ? close() : openList())} onKeyDown={onButtonKey}>
        <span className={`select-value${placeholder ? ' is-placeholder' : ''}`}>{label}</span>
        <ChevronDown size={16} aria-hidden className="select-chevron" />
      </button>
      {open && <>
        {sheet && <div className="select-backdrop" aria-hidden onClick={() => close()} />}
        <div className={`select-panel${sheet ? ' is-sheet' : ''}`} style={place} onKeyDown={onListKey}>
          {sheet && <div className="select-sheet-head">{title ?? aria['aria-label'] ?? (document.querySelector(`label[for="${CSS.escape(triggerId)}"]`)?.textContent || label)}</div>}
          {searchable && <div className="select-search">
            <Search size={15} aria-hidden />
            <input ref={search} type="search" value={query} placeholder={t('Tìm…')} aria-label={t('Tìm trong danh sách')}
              aria-controls={listId} aria-activedescendant={shown[active] ? `${listId}-${active}` : undefined}
              onChange={e => { setQuery(e.target.value); setActive(0) }} />
          </div>}
          <ul ref={list} id={listId} role="listbox" tabIndex={-1} aria-labelledby={triggerId}
            aria-activedescendant={!searchable && shown[active] ? `${listId}-${active}` : undefined}>
            {shown.length === 0 && <li className="select-empty">{t('Không có lựa chọn phù hợp')}</li>}
            {shown.map((item, i) => (
              <li key={`${item.value}-${i}`} id={`${listId}-${i}`} data-index={i} role="option" aria-selected={item.value === current}
                aria-disabled={item.disabled || undefined} className="select-option" data-active={i === active || undefined}
                onPointerMove={() => !item.disabled && setActive(i)} onClick={() => choose(item)}>
                <span className="select-option-label">{item.label}</span>
                {item.value === current && <Check size={16} aria-hidden />}
              </li>
            ))}
          </ul>
        </div>
      </>}
    </div>
  )
}
