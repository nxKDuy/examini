import type { ReactNode } from 'react'

// Oversized faded vocabulary behind the form, echoing the product's own labels.
const BACKDROP = [
  { text: 'NGÂN HÀNG CÂU HỎI', top: '4%', left: '-3%' },
  { text: 'Kỳ thi / 2026', top: '22%', left: '58%' },
  { text: '[ ■ ] Đã duyệt', top: '44%', left: '-6%' },
  { text: 'CHẤM BÀI', top: '64%', left: '52%' },
  { text: 'Nộp bài / 03', top: '84%', left: '4%' },
]

export function AuthCard({ title, subtitle, children, footer }: { title: string; subtitle: string; children: ReactNode; footer: ReactNode }) {
  return (
    <main className="auth-shell">
      <div className="auth-backdrop" aria-hidden>
        {BACKDROP.map(w => <span key={w.text} style={{ top: w.top, left: w.left }}>{w.text}</span>)}
      </div>
      <div className="auth-card">
        <section className="card" style={{ background: '#fff' }}>
          <div className="spread" style={{ padding: '14px 24px', borderBottom: '1px solid #000' }}>
            <span style={{ fontSize: '1.45rem', letterSpacing: '0.02em' }}>EXAMINI</span>
            <span className="small muted">Khảo thí / 01</span>
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
