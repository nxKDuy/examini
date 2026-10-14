import LanguageSwitch from '../../components/LanguageSwitch'
import type { ReactNode } from 'react'
import { t, useT } from '../../lib/i18n'

export function AuthCard({ title, subtitle, children, footer }: { title: string; subtitle: string; children: ReactNode; footer: ReactNode }) {
  useT()
  return (
    <main className="auth-shell">
      <div className="auth-card">
        <section className="card" style={{ background: '#fff' }}>
          <div className="spread" style={{ padding: '14px 24px', borderBottom: '1px solid #000' }}>
            <span style={{ fontSize: '1.45rem', letterSpacing: '0.02em' }}>EXAMINI</span>
            <span className="row" style={{ gap: 10 }}><LanguageSwitch /><span className="small muted">{t('Khảo thí / 01')}</span></span>
          </div>
          <div className="stack-lg" style={{ padding: 24 }}>
            <div className="stack-sm" style={{ gap: 6 }}>
              <h1>{title}</h1>
              <p className="muted">{subtitle}</p>
            </div>
            {children}
          </div>
          <p className="small" style={{ padding: '14px 24px', borderTop: '1px solid #000' }}>{footer}</p>
        </section>
      </div>
    </main>
  )
}
