import LanguageSwitch from '../../components/LanguageSwitch'
import type { ReactNode } from 'react'
import { CalendarClock, ClipboardCheck, ShieldCheck, Sparkles } from 'lucide-react'
import { t, useT } from '../../lib/i18n'

const FEATURES = [
  { icon: Sparkles, title: 'Soạn đề bằng AI', text: 'Từ chủ đề, tài liệu Word/PDF/ảnh hoặc link; AI tự giải lại để kiểm tra đáp án.' },
  { icon: ClipboardCheck, title: 'Chấm nhanh, minh bạch', text: 'Trắc nghiệm chấm tự động; tự luận có AI gợi ý điểm, giáo viên quyết định.' },
  { icon: CalendarClock, title: 'Lịch thi tự động', text: 'Tự mở, tự đóng, nhắc qua email, trình duyệt và lịch điện thoại.' },
  { icon: ShieldCheck, title: 'Thi nghiêm túc', text: 'Toàn màn hình, ghi nhận chuyển tab, camera; học sinh xem lại bài với gia sư AI.' },
] as const

/** Sign-in / sign-up frame. On a laptop the left half introduces the product; on a phone only the form shows. */
export function AuthCard({ title, subtitle, children, footer }: { title: string; subtitle: string; children: ReactNode; footer: ReactNode }) {
  useT()
  return (
    <main className="auth-shell">
      <div className="auth-split">
        <aside className="auth-intro" aria-label={t('Giới thiệu EXAMINI')}>
          <p className="auth-intro-kicker">{t('Nền tảng khảo thí trực tuyến ứng dụng AI')}</p>
          <h2 className="auth-intro-title">{t('Ra đề, tổ chức thi và chấm bài trong một nơi.')}</h2>
          <ul className="auth-features">
            {FEATURES.map(f => (
              <li key={f.title}><f.icon size={22} aria-hidden /><span><strong>{t(f.title)}</strong><span>{t(f.text)}</span></span></li>
            ))}
          </ul>
        </aside>
        <div className="auth-card">
          <section className="card" style={{ background: '#fff' }}>
            <div className="spread" style={{ padding: '14px 24px', borderBottom: '1px solid #000' }}>
              <span style={{ fontSize: '1.45rem', letterSpacing: '0.02em' }}>EXAMINI</span>
              <LanguageSwitch />
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
      </div>
    </main>
  )
}
