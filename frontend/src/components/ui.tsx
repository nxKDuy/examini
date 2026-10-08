import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { AlertCircle, AlertTriangle, CheckCircle2, ChevronRight, Info, Lock, RefreshCw, SearchX, WifiOff } from 'lucide-react'
import { errorInfo } from '../lib/errors'

export function PageHeader({ title, description, actions, crumbs }: {
  title: ReactNode; description?: ReactNode; actions?: ReactNode; crumbs?: { label: string; to?: string }[]
}) {
  return (
    <header className="page-header">
      <div style={{ minWidth: 0 }}>
        {crumbs && crumbs.length > 0 && (
          <nav aria-label="Đường dẫn" className="breadcrumb">
            {crumbs.map((c, i) => (
              <span key={i} className="row" style={{ gap: 6 }}>
                {c.to ? <Link to={c.to}>{c.label}</Link> : <span aria-current="page">{c.label}</span>}
                {i < crumbs.length - 1 && <ChevronRight size={14} aria-hidden />}
              </span>
            ))}
          </nav>
        )}
        <h1>{title}</h1>
        {description && <p>{description}</p>}
      </div>
      {actions && <div className="row">{actions}</div>}
    </header>
  )
}

export type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger' | 'muted'

export function Badge({ tone = 'neutral', icon, children, title }: { tone?: Tone; icon?: ReactNode; children: ReactNode; title?: string }) {
  return <span className={`badge badge-${tone}`} title={title}>{icon}{children}</span>
}

const NOTICE_ICON = { info: Info, success: CheckCircle2, warning: AlertTriangle, danger: AlertCircle }
export function Notice({ tone = 'info', title, children, action, live }: {
  tone?: keyof typeof NOTICE_ICON; title?: ReactNode; children?: ReactNode; action?: ReactNode; live?: boolean
}) {
  const Icon = NOTICE_ICON[tone]
  return (
    <div className={`notice notice-${tone}`} role={live ? (tone === 'danger' ? 'alert' : 'status') : undefined}>
      <Icon size={18} aria-hidden />
      <div className="stack-sm" style={{ flex: 1, gap: 4 }}>
        {title && <strong>{title}</strong>}
        {children && <div>{children}</div>}
      </div>
      {action}
    </div>
  )
}

export function EmptyState({ icon, title, description, action }: { icon?: ReactNode; title: string; description?: ReactNode; action?: ReactNode }) {
  return (
    <div className="empty-state" style={{ padding: '40px 20px', textAlign: 'center', display: 'grid', justifyItems: 'center', gap: 8 }}>
      <div style={{ color: 'var(--color-muted)' }} aria-hidden>{icon ?? <SearchX size={32} />}</div>
      <h3>{title}</h3>
      {description && <p className="muted small" style={{ maxWidth: '52ch' }}>{description}</p>}
      {action && <div style={{ marginTop: 8 }}>{action}</div>}
    </div>
  )
}

export function ErrorState({ error, onRetry, title }: { error: unknown; onRetry?: () => void; title?: string }) {
  const info = errorInfo(error)
  const Icon = info.status === 0 ? WifiOff : info.status === 403 ? Lock : AlertCircle
  const heading = title ?? (info.status === 403 ? 'Không có quyền truy cập' : info.status === 404 ? 'Không tìm thấy' : info.status === 0 ? 'Mất kết nối' : 'Không tải được dữ liệu')
  return (
    <div role="alert" style={{ padding: '32px 20px', textAlign: 'center', display: 'grid', justifyItems: 'center', gap: 8 }}>
      <Icon size={30} aria-hidden style={{ color: info.status === 403 ? 'var(--color-muted)' : 'var(--color-danger)' }} />
      <h3>{heading}</h3>
      <p className="muted small" style={{ maxWidth: '56ch' }}>{info.message}</p>
      {onRetry && info.status !== 403 && info.status !== 404 && (
        <button type="button" className="btn btn-secondary btn-sm" onClick={onRetry} style={{ marginTop: 6 }}>
          <RefreshCw size={14} aria-hidden /> Thử lại
        </button>
      )}
    </div>
  )
}

export function LoadingState({ rows = 3, label = 'Đang tải…' }: { rows?: number; label?: string }) {
  return (
    <div role="status" aria-live="polite" style={{ padding: 20, display: 'grid', gap: 12 }}>
      <span className="sr-only">{label}</span>
      {Array.from({ length: rows }, (_, i) => <div key={i} className="skeleton" style={{ height: 44, width: `${100 - (i % 3) * 12}%` }} />)}
    </div>
  )
}

export function Spinner({ size = 16 }: { size?: number }) {
  return <RefreshCw size={size} className="spin" aria-hidden />
}

export function Field({ label, hint, error, children, required, htmlFor }: {
  label: ReactNode; hint?: ReactNode; error?: string; children: ReactNode; required?: boolean; htmlFor?: string
}) {
  return (
    <div className="field">
      <label className="field-label" htmlFor={htmlFor}>{label}{required && <span aria-hidden style={{ color: 'var(--color-danger)' }}> *</span>}</label>
      {children}
      {hint && !error && <span className="field-hint">{hint}</span>}
      {error && <span className="field-error" role="alert">{error}</span>}
    </div>
  )
}

export function Stat({ label, value, hint, to }: { label: string; value: ReactNode; hint?: ReactNode; to?: string }) {
  const body = (
    <>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
      {hint && <div className="xsmall muted" style={{ marginTop: 2 }}>{hint}</div>}
    </>
  )
  return to
    ? <Link to={to} className="card stat" style={{ textDecoration: 'none', color: 'inherit', display: 'block' }}>{body}</Link>
    : <div className="card stat">{body}</div>
}

export function Section({ title, description, actions, children, bodyClassName = 'card-body' }: {
  title: ReactNode; description?: ReactNode; actions?: ReactNode; children: ReactNode; bodyClassName?: string
}) {
  return (
    <section className="card">
      <div className="card-header">
        <div style={{ minWidth: 0 }}>
          <h2>{title}</h2>
          {description && <p className="muted small" style={{ marginTop: 2 }}>{description}</p>}
        </div>
        {actions && <div className="row">{actions}</div>}
      </div>
      <div className={bodyClassName}>{children}</div>
    </section>
  )
}

export function Pagination({ page, totalPages, onChange, label }: { page: number; totalPages: number; onChange: (page: number) => void; label: string }) {
  if (totalPages <= 1) return null
  return (
    <nav aria-label={label} className="spread" style={{ padding: '12px 16px', borderTop: '1px solid var(--color-border)' }}>
      <button type="button" className="btn btn-secondary btn-sm" disabled={page === 0} onClick={() => onChange(page - 1)}>Trang trước</button>
      <span className="small muted mono">Trang {page + 1} / {totalPages}</span>
      <button type="button" className="btn btn-secondary btn-sm" disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}>Trang sau</button>
    </nav>
  )
}
