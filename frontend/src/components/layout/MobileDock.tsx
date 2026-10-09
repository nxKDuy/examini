import { useEffect, useRef, useState } from 'react'
import { NavLink, matchPath, useLocation } from 'react-router-dom'
import { Ellipsis } from 'lucide-react'
import { dockFor, type Audience } from './navigation'
import { useModalFocus } from '../useModalFocus'

/** Bottom dock for phones (<768px, hidden by CSS above that): up to 5 tiles, "Khác" opens a bottom sheet with the rest. */
export default function MobileDock({ audience }: { audience: Audience }) {
  const { dock, more } = dockFor(audience)
  const location = useLocation()
  const [open, setOpen] = useState(false)
  const sheetRef = useRef<HTMLDivElement>(null)
  const moreActive = more.some(item => !!matchPath({ path: item.to, end: false }, location.pathname))
  useEffect(() => { setOpen(false) }, [location.pathname])
  return (
    <>
      <nav className="dock" aria-label="Điều hướng chính">
        {dock.map(({ to, label, shortLabel, icon: Icon }) => (
          <NavLink key={to} to={to} className="dock-tile" aria-label={label}>
            <Icon size={20} aria-hidden /><span aria-hidden>{shortLabel ?? label}</span>
          </NavLink>
        ))}
        {more.length > 0 && (
          <button type="button" className="dock-tile" data-active={moreActive} aria-haspopup="dialog" aria-expanded={open} aria-label="Khác" onClick={() => setOpen(true)}>
            <Ellipsis size={20} aria-hidden /><span aria-hidden>Khác</span>
          </button>
        )}
      </nav>
      {open && <MoreSheet refEl={sheetRef} onClose={() => setOpen(false)} items={more} />}
    </>
  )
}

function MoreSheet({ refEl, onClose, items }: { refEl: React.RefObject<HTMLDivElement | null>; onClose: () => void; items: ReturnType<typeof dockFor>['more'] }) {
  useModalFocus(refEl, { trap: true, escape: true, onEscape: onClose })
  return (
    <>
      <button type="button" className="dock-backdrop" aria-label="Đóng" tabIndex={-1} onClick={onClose} />
      <div ref={refEl} className="dock-sheet" role="dialog" aria-modal="true" aria-label="Thêm mục điều hướng">
        <div className="dock-sheet-head"><strong>Khác</strong><button type="button" className="btn btn-ghost btn-sm" onClick={onClose}>Đóng</button></div>
        <nav aria-label="Các mục khác">
          {items.map(({ to, label, icon: Icon }) => <NavLink key={to} to={to} className="dock-sheet-link" onClick={onClose}><Icon size={20} aria-hidden /><span>{label}</span></NavLink>)}
        </nav>
      </div>
    </>
  )
}
