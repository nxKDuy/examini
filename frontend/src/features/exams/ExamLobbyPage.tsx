import { useState, useEffect } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Clock, FileText, Shield, Wifi, AlertTriangle, Play, ArrowLeft, CheckCircle } from 'lucide-react'

// ─── Mock exam info ───────────────────────────────────────────────────────────
const MOCK_EXAM = {
  id: '1',
  name: 'Kiểm tra Giữa kỳ Môn Toán 12',
  subject: 'Toán học',
  teacher: 'Nguyễn Văn Hùng',
  durationMinutes: 45,
  totalQuestions: 40,
  passingScore: 5.0,
  totalScore: 10.0,
  instructions: [
    'Đọc kỹ từng câu hỏi trước khi chọn đáp án.',
    'Mỗi câu hỏi chỉ có duy nhất một đáp án đúng.',
    'Không thoát khỏi tab hoặc chuyển sang cửa sổ khác trong quá trình thi.',
    'Bài thi sẽ tự động nộp khi hết thời gian quy định.',
    'Bạn có thể quay lại và thay đổi đáp án trước khi nộp bài.',
  ],
}

// ─── Network check ────────────────────────────────────────────────────────────
function useNetworkStatus() {
  const [quality, setQuality] = useState<'good' | 'poor' | 'checking'>('checking')
  useEffect(() => {
    if (!navigator.onLine) { setQuality('poor'); return }
    const start = Date.now()
    fetch('/favicon.ico', { cache: 'no-store' })
      .then(() => setQuality(Date.now() - start < 1500 ? 'good' : 'poor'))
      .catch(() => setQuality('poor'))
  }, [])
  return quality
}

// ─── Main component ───────────────────────────────────────────────────────────
export default function ExamLobbyPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [agreed, setAgreed] = useState(false)
  const networkQuality = useNetworkStatus()

  const exam = MOCK_EXAM

  const netCfg = {
    good:     { label: 'Kết nối ổn định',        color: 'var(--color-success)', icon: Wifi },
    poor:     { label: 'Kết nối yếu – cẩn thận', color: '#f59e0b',              icon: AlertTriangle },
    checking: { label: 'Đang kiểm tra mạng…',    color: 'var(--color-surface-400)', icon: Wifi },
  }[networkQuality]
  const NetIcon = netCfg.icon

  const specs = [
    { icon: Clock,    label: 'Thời gian',          value: `${exam.durationMinutes} phút` },
    { icon: FileText, label: 'Số câu hỏi',         value: `${exam.totalQuestions} câu` },
    { icon: Shield,   label: 'Điểm đạt yêu cầu',  value: `${exam.passingScore}/${exam.totalScore}` },
  ]

  return (
    <div style={{ minHeight: '100dvh', background: '#F9F9F9', display: 'flex', flexDirection: 'column' }}>

      {/* ── Top bar ─────────────────────────────────────────────────────── */}
      <div style={{
        background: '#ffffff', borderBottom: '1px solid var(--color-surface-700)',
        padding: '12px 40px', display: 'flex', alignItems: 'center', gap: 16,
      }}>
        <button onClick={() => navigate('/dashboard')} className="btn-ghost" style={{ gap: 6, padding: '6px 10px' }}>
          <ArrowLeft size={16} /> Quay lại
        </button>
        <div style={{ width: 1, height: 20, background: 'var(--color-surface-700)' }} />
        <span style={{ fontSize: '0.85rem', color: 'var(--color-surface-400)' }}>
          Sảnh chờ thi &rsaquo; <strong style={{ color: 'var(--color-surface-200)' }}>{exam.name}</strong>
        </span>
      </div>

      {/* ── 2-column layout ─────────────────────────────────────────────── */}
      <div style={{
        flex: 1, display: 'grid',
        gridTemplateColumns: '1fr 420px',
        gap: 0, maxWidth: 1100, width: '100%',
        margin: '0 auto', padding: '48px 40px',
        alignItems: 'start',
        gap: 40,
      } as React.CSSProperties}>

        {/* ── LEFT: Exam info ──────────────────────────────────────────── */}
        <div>
          {/* Subject tag */}
          <div style={{
            display: 'inline-flex', alignItems: 'center', gap: 6,
            padding: '4px 12px', borderRadius: 999,
            background: 'rgba(240,78,56,0.08)', color: 'var(--color-primary-600)',
            fontSize: '0.8rem', fontWeight: 600, marginBottom: 16,
          }}>
            {exam.subject}
          </div>

          {/* Exam title */}
          <h1 style={{
            fontSize: '2rem', fontWeight: 800, color: 'var(--color-surface-50)',
            letterSpacing: '-0.03em', lineHeight: 1.25, marginBottom: 8,
          }}>
            {exam.name}
          </h1>

          <p style={{ color: 'var(--color-surface-400)', fontSize: '0.9rem', marginBottom: 36 }}>
            Giảng viên: <strong style={{ color: 'var(--color-surface-200)' }}>{exam.teacher}</strong>
          </p>

          {/* Specs — 3 horizontal cards */}
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 16, marginBottom: 40 }}>
            {specs.map(({ icon: Icon, label, value }) => (
              <div key={label} style={{
                padding: '20px 16px', borderRadius: 10, textAlign: 'center',
                background: '#ffffff', border: '1px solid var(--color-primary-500)',
                boxShadow: '0 2px 8px rgba(240,78,56,0.06)',
              }}>
                <Icon size={22} color="var(--color-primary-500)" style={{ marginBottom: 10 }} />
                <div style={{ fontWeight: 800, fontSize: '1.25rem', color: 'var(--color-surface-50)', marginBottom: 4 }}>
                  {value}
                </div>
                <div style={{ fontSize: '0.75rem', color: 'var(--color-surface-400)', textTransform: 'uppercase', letterSpacing: '0.06em' }}>
                  {label}
                </div>
              </div>
            ))}
          </div>

          {/* Instructions */}
          <div>
            <h2 style={{ fontSize: '1rem', fontWeight: 700, color: 'var(--color-surface-50)', marginBottom: 16 }}>
              Nội quy phòng thi
            </h2>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              {exam.instructions.map((rule, i) => (
                <div key={i} style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
                  <div style={{
                    width: 24, height: 24, borderRadius: '50%', flexShrink: 0,
                    background: 'rgba(240,78,56,0.08)', color: 'var(--color-primary-500)',
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    fontSize: '0.75rem', fontWeight: 700,
                  }}>
                    {i + 1}
                  </div>
                  <span style={{ fontSize: '0.9rem', color: 'var(--color-surface-300)', lineHeight: 1.6 }}>
                    {rule}
                  </span>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* ── RIGHT: Checklist + Start button ─────────────────────────── */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>

          {/* Network status */}
          <div style={{
            display: 'flex', alignItems: 'center', gap: 10,
            padding: '12px 16px', borderRadius: 8, background: '#ffffff',
            border: `1px solid ${networkQuality === 'good' ? 'rgba(16,185,129,0.35)' : 'rgba(245,158,11,0.35)'}`,
          }}>
            <NetIcon size={16} color={netCfg.color} />
            <span style={{ fontSize: '0.875rem', color: netCfg.color, fontWeight: 600 }}>
              {netCfg.label}
            </span>
          </div>

          {/* Checklist card */}
          <div style={{
            background: '#ffffff', border: '1px solid var(--color-primary-500)',
            borderRadius: 10, padding: '24px',
            boxShadow: '0 2px 8px rgba(240,78,56,0.06)',
          }}>
            <h2 style={{ fontSize: '0.9rem', fontWeight: 700, color: 'var(--color-surface-50)', marginBottom: 16, textTransform: 'uppercase', letterSpacing: '0.05em' }}>
              Kiểm tra trước khi thi
            </h2>

            <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginBottom: 24 }}>
              {[
                { label: 'Tôi đã chuẩn bị đầy đủ tài liệu cần thiết' },
                { label: 'Tôi đang ở nơi yên tĩnh, không bị phân tâm' },
                { label: 'Tôi đã đọc và hiểu rõ nội quy phòng thi' },
              ].map((item, i) => (
                <label key={i} style={{ display: 'flex', gap: 12, alignItems: 'flex-start', cursor: 'pointer' }}>
                  <div style={{
                    width: 20, height: 20, borderRadius: 5, flexShrink: 0, marginTop: 1,
                    border: '1.5px solid var(--color-surface-700)',
                    background: 'transparent',
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                  }}>
                    <CheckCircle size={14} color="var(--color-surface-600)" />
                  </div>
                  <span style={{ fontSize: '0.875rem', color: 'var(--color-surface-300)', lineHeight: 1.5 }}>
                    {item.label}
                  </span>
                </label>
              ))}
            </div>

            {/* Main agreement */}
            <label style={{
              display: 'flex', gap: 12, alignItems: 'flex-start', cursor: 'pointer',
              padding: '14px', borderRadius: 8,
              background: agreed ? 'rgba(240,78,56,0.05)' : 'var(--color-surface-800)',
              border: `1.5px solid ${agreed ? 'var(--color-primary-400)' : 'var(--color-surface-700)'}`,
              transition: 'all 0.2s',
            }}>
              <input
                type="checkbox" checked={agreed} onChange={e => setAgreed(e.target.checked)}
                style={{ width: 18, height: 18, marginTop: 1, accentColor: 'var(--color-primary-500)', cursor: 'pointer', flexShrink: 0 }}
              />
              <span style={{ fontSize: '0.875rem', color: 'var(--color-surface-200)', lineHeight: 1.5 }}>
                Tôi xác nhận đã sẵn sàng và đồng ý tuân thủ toàn bộ nội quy phòng thi.
              </span>
            </label>
          </div>

          {/* Start button */}
          <button
            className="btn-primary"
            disabled={!agreed || networkQuality === 'checking'}
            onClick={() => navigate(`/exam/take/${id}`)}
            style={{ height: 52, fontSize: '1rem', fontWeight: 700, display: 'flex', justifyContent: 'center', gap: 10 }}>
            <Play size={18} />
            Bắt đầu làm bài
          </button>

          {!agreed && (
            <p style={{ textAlign: 'center', fontSize: '0.8rem', color: 'var(--color-surface-500)', marginTop: -8 }}>
              Xác nhận checkbox ở trên để kích hoạt nút bắt đầu.
            </p>
          )}
        </div>
      </div>
    </div>
  )
}
