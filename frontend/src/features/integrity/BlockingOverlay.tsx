import { useRef, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { Timer } from 'lucide-react'
import { useModalFocus } from '../../components/useModalFocus'
import { formatDuration } from '../../lib/format'

/** Opaque layer over the attempt: questions stay hidden and cannot be edited, the server timer keeps running. */
export default function BlockingOverlay({ title, remaining, children, actions }: { title: string; remaining: number; children: ReactNode; actions: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null)
  useModalFocus(ref, { trap: true, escape: false })
  return createPortal(
    <div className="take-block" role="alertdialog" aria-modal="true" aria-labelledby="take-block-title" ref={ref}>
      <section className="take-block-card stack">
        <span className="timer" role="timer" aria-label={`Thời gian còn lại ${formatDuration(remaining)}`}><Timer size={16} aria-hidden /> {formatDuration(remaining)}</span>
        <h1 id="take-block-title" style={{ fontSize: '1.4rem' }}>{title}</h1>
        {children}
        <div className="row">{actions}</div>
      </section>
    </div>,
    document.body,
  )
}
