import { useId, useRef, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { X } from 'lucide-react'
import { useModalFocus } from './useModalFocus'

/** Modal dialog: focus moves in, Tab is trapped, Escape closes, focus returns on close. */
export function Dialog({ title, description, children, footer, onClose, size, busy }: {
  title: ReactNode; description?: ReactNode; children?: ReactNode; footer?: ReactNode; onClose: () => void; size?: 'lg'; busy?: boolean
}) {
  const ref = useRef<HTMLDivElement>(null)
  const titleId = useId(), descId = useId()
  useModalFocus(ref, { trap: true, escape: true, onEscape: () => { if (!busy) onClose() } })
  return createPortal(
    <div className="dialog-backdrop" onMouseDown={e => { if (e.target === e.currentTarget && !busy) onClose() }}>
      <div ref={ref} className={`dialog ${size === 'lg' ? 'dialog-lg' : ''}`} role="dialog" aria-modal="true"
        aria-labelledby={titleId} aria-describedby={description ? descId : undefined}>
        <div className="dialog-header">
          <div style={{ minWidth: 0 }}>
            <h2 id={titleId}>{title}</h2>
            {description && <p id={descId} className="muted small" style={{ marginTop: 4 }}>{description}</p>}
          </div>
          <button type="button" className="btn btn-ghost btn-icon" aria-label="Đóng" onClick={onClose} disabled={busy}><X size={18} /></button>
        </div>
        {children && <div className="dialog-body">{children}</div>}
        {footer && <div className="dialog-footer">{footer}</div>}
      </div>
    </div>,
    document.body,
  )
}

export function ConfirmDialog({ title, description, children, confirmLabel, cancelLabel = 'Hủy', tone = 'primary', busy, onConfirm, onCancel, disabled }: {
  title: ReactNode; description?: ReactNode; children?: ReactNode; confirmLabel: string; cancelLabel?: string
  tone?: 'primary' | 'danger'; busy?: boolean; disabled?: boolean; onConfirm: () => void; onCancel: () => void
}) {
  return (
    <Dialog title={title} description={description} onClose={onCancel} busy={busy}
      footer={<>
        <button type="button" className="btn btn-secondary" onClick={onCancel} disabled={busy} data-autofocus>{cancelLabel}</button>
        <button type="button" className={`btn ${tone === 'danger' ? 'btn-danger' : 'btn-primary'}`} onClick={onConfirm} disabled={busy || disabled} aria-busy={busy}>
          {busy ? 'Đang xử lý…' : confirmLabel}
        </button>
      </>}>
      {children}
    </Dialog>
  )
}
