import type { ReactNode } from 'react'

export function AuthCard({ title, subtitle, children, footer }: { title: string; subtitle: string; children: ReactNode; footer: ReactNode }) {
  return (
    <main className="auth-shell">
      <div className="auth-card stack-lg">
        <div className="row" style={{ justifyContent: 'center', gap: 10 }}>
          <span className="brand-mark" aria-hidden>E</span>
          <span style={{ fontWeight: 700, fontSize: '1.25rem', color: 'var(--color-ink)', letterSpacing: '-0.01em' }}>EXAMINI</span>
        </div>
        <section className="card card-pad stack-lg" style={{ padding: 28 }}>
          <div className="stack-sm" style={{ gap: 4 }}>
            <h1>{title}</h1>
            <p className="muted small">{subtitle}</p>
          </div>
          {children}
        </section>
        <p className="small muted" style={{ textAlign: 'center' }}>{footer}</p>
      </div>
    </main>
  )
}
