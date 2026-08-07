import { useNavigate, useParams } from 'react-router-dom'
import { CheckCircle, XCircle, MinusCircle, Home, RefreshCw, TrendingUp } from 'lucide-react'

// ─── Mock result data – sẽ fetch từ API sau ──────────────────────────────────
const MOCK_RESULT = {
  examName: 'Kiểm tra Giữa kỳ Môn Toán 12',
  subject: 'Toán học',
  score: 8.5,
  totalScore: 10,
  passingScore: 5.0,
  totalQuestions: 40,
  correct: 34,
  wrong: 4,
  skipped: 2,
  timeTaken: '38 phút 12 giây',
  submittedAt: '08:42 SA, 02/08/2026',
  published: true, // giáo viên đã công bố kết quả
  reviewAllowed: true,
  questions: [
    { no: 1, content: 'Cho hàm số f(x) = x² - 4x + 3. Tìm nghiệm của f(x) = 0.', chosenIdx: 1, correctIdx: 1, options: ['x = 1; x = 4', 'x = 1; x = 3', 'x = -1; x = -3', 'x = 2; x = 3'] },
    { no: 2, content: 'Giá trị của log₂(8) bằng bao nhiêu?', chosenIdx: 2, correctIdx: 2, options: ['2', '4', '3', '6'] },
    { no: 3, content: 'Tích phân ∫₀¹ x dx bằng?', chosenIdx: 0, correctIdx: 3, options: ['1', '2', '½', '¼'] },
    { no: 4, content: 'Số phức z = 3 + 4i có mô-đun bằng?', chosenIdx: 0, correctIdx: 0, options: ['5', '7', '6', '√7'] },
    { no: 5, content: 'Đạo hàm của f(x) = sin(x) là?', chosenIdx: -1, correctIdx: 1, options: ['-cos(x)', 'cos(x)', 'tan(x)', '-sin(x)'] },
  ],
}

// ─── Circular score progress ──────────────────────────────────────────────────
function CircularScore({ score, total }: { score: number; total: number }) {
  const pct = score / total
  const radius = 54
  const circ = 2 * Math.PI * radius
  const offset = circ - pct * circ
  const passed = score >= MOCK_RESULT.passingScore
  const color = passed ? 'var(--color-success)' : 'var(--color-error)'

  return (
    <div style={{ position: 'relative', width: 140, height: 140 }}>
      <svg width="140" height="140" style={{ transform: 'rotate(-90deg)' }}>
        <circle cx="70" cy="70" r={radius} fill="none" stroke="var(--color-surface-800)" strokeWidth="12" />
        <circle cx="70" cy="70" r={radius} fill="none" stroke={color} strokeWidth="12"
          strokeDasharray={circ} strokeDashoffset={offset}
          strokeLinecap="round"
          style={{ transition: 'stroke-dashoffset 1s ease-out' }} />
      </svg>
      <div style={{
        position: 'absolute', inset: 0,
        display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
      }}>
        <span style={{ fontSize: '1.75rem', fontWeight: 800, color, lineHeight: 1 }}>{score}</span>
        <span style={{ fontSize: '0.8rem', color: 'var(--color-surface-400)' }}>/ {total}</span>
      </div>
    </div>
  )
}

// ─── Main component ───────────────────────────────────────────────────────────
export default function ExamResultPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const r = MOCK_RESULT
  const passed = r.score >= r.passingScore

  return (
    <div style={{ minHeight: '100dvh', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '32px 16px' }}>
      <div style={{ width: '100%', maxWidth: 720 }} className="page-enter">

        {/* Score summary card */}
        <div className="clean-card" style={{ overflow: 'hidden', marginBottom: 24 }}>
          <div style={{
            padding: '28px 32px', textAlign: 'center',
            background: passed
              ? 'linear-gradient(135deg, rgba(74,222,128,0.08) 0%, rgba(34,197,94,0.04) 100%)'
              : 'linear-gradient(135deg, rgba(239,68,68,0.08) 0%, rgba(220,38,38,0.04) 100%)',
            borderBottom: '1px solid var(--color-surface-700)',
          }}>
            <div style={{ fontSize: '0.8rem', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.08em', color: 'var(--color-surface-400)', marginBottom: 6 }}>
              {r.subject}
            </div>
            <h1 style={{ fontSize: '1.4rem', fontWeight: 700, color: 'var(--color-surface-50)', marginBottom: 24 }}>
              {r.examName}
            </h1>

            <div style={{ display: 'flex', justifyContent: 'center', marginBottom: 16 }}>
              <CircularScore score={r.score} total={r.totalScore} />
            </div>

            <div style={{
              display: 'inline-flex', alignItems: 'center', gap: 8,
              padding: '6px 16px', borderRadius: 999,
              background: passed ? 'rgba(74,222,128,0.15)' : 'rgba(239,68,68,0.15)',
              color: passed ? 'var(--color-success)' : 'var(--color-error)',
              fontSize: '0.95rem', fontWeight: 700,
            }}>
              {passed ? <CheckCircle size={16} /> : <XCircle size={16} />}
              {passed ? 'ĐẠT' : 'CHƯA ĐẠT'}
            </div>
          </div>

          {/* Stats row */}
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', borderTop: '1px solid var(--color-surface-700)' }}>
            {[
              { label: 'Câu đúng', value: r.correct, color: 'var(--color-success)', icon: CheckCircle },
              { label: 'Câu sai', value: r.wrong, color: 'var(--color-error)', icon: XCircle },
              { label: 'Bỏ qua', value: r.skipped, color: 'var(--color-surface-400)', icon: MinusCircle },
              { label: 'Thời gian', value: r.timeTaken, color: 'var(--color-primary-400)', icon: TrendingUp },
            ].map(({ label, value, color, icon: Icon }, i) => (
              <div key={label} style={{
                padding: '16px 12px', textAlign: 'center',
                borderRight: i < 3 ? '1px solid var(--color-surface-700)' : 'none',
              }}>
                <Icon size={18} color={color} style={{ marginBottom: 6 }} />
                <div style={{ fontWeight: 700, color, fontSize: '1.2rem', marginBottom: 2 }}>{value}</div>
                <div style={{ fontSize: '0.75rem', color: 'var(--color-surface-400)' }}>{label}</div>
              </div>
            ))}
          </div>
        </div>

        {/* Review section */}
        {r.reviewAllowed && (
          <div className="clean-card" style={{ overflow: 'hidden', marginBottom: 24 }}>
            <div style={{ padding: '16px 24px', borderBottom: '1px solid var(--color-surface-700)' }}>
              <h2 style={{ margin: 0, fontSize: '1rem', fontWeight: 600, color: 'var(--color-surface-50)' }}>
                Xem lại bài làm
              </h2>
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 0 }}>
              {r.questions.map((q, qi) => {
                const isSkipped = q.chosenIdx === -1
                const isCorrect = !isSkipped && q.chosenIdx === q.correctIdx

                return (
                  <div key={qi} style={{
                    padding: '20px 24px',
                    borderBottom: qi < r.questions.length - 1 ? '1px solid var(--color-surface-800)' : 'none',
                    background: isCorrect ? 'rgba(74,222,128,0.03)' : isSkipped ? 'transparent' : 'rgba(239,68,68,0.03)',
                  }}>
                    <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start', marginBottom: 12 }}>
                      <div style={{
                        width: 28, height: 28, borderRadius: '50%', flexShrink: 0,
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        background: isCorrect ? 'rgba(74,222,128,0.2)' : isSkipped ? 'var(--color-surface-800)' : 'rgba(239,68,68,0.2)',
                        color: isCorrect ? 'var(--color-success)' : isSkipped ? 'var(--color-surface-400)' : 'var(--color-error)',
                        fontSize: '0.8rem', fontWeight: 700,
                      }}>
                        {q.no}
                      </div>
                      <p style={{ margin: 0, fontSize: '0.9rem', color: 'var(--color-surface-100)', lineHeight: 1.55, flex: 1 }}>
                        {q.content}
                      </p>
                    </div>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 6, paddingLeft: 40 }}>
                      {q.options.map((opt, oi) => {
                        const isChosen = oi === q.chosenIdx
                        const isAnswerCorrect = oi === q.correctIdx
                        let bg = 'transparent'
                        let border = 'var(--color-surface-700)'
                        let color = 'var(--color-surface-400)'
                        if (isAnswerCorrect) { bg = 'rgba(74,222,128,0.1)'; border = 'var(--color-success)'; color = 'var(--color-success)' }
                        if (isChosen && !isAnswerCorrect) { bg = 'rgba(239,68,68,0.1)'; border = 'var(--color-error)'; color = 'var(--color-error)' }

                        return (
                          <div key={oi} style={{
                            display: 'flex', gap: 10, alignItems: 'center',
                            padding: '8px 12px', borderRadius: 6,
                            border: `1px solid ${border}`, background: bg,
                            fontSize: '0.85rem', color,
                            fontWeight: isAnswerCorrect || isChosen ? 600 : 400,
                          }}>
                            <span style={{ flexShrink: 0 }}>{String.fromCharCode(65 + oi)}.</span>
                            <span>{opt}</span>
                            {isAnswerCorrect && <CheckCircle size={14} style={{ marginLeft: 'auto' }} />}
                            {isChosen && !isAnswerCorrect && <XCircle size={14} style={{ marginLeft: 'auto' }} />}
                          </div>
                        )
                      })}
                    </div>
                  </div>
                )
              })}
            </div>
          </div>
        )}

        {/* Actions */}
        <div style={{ display: 'flex', gap: 12 }}>
          <button className="btn-secondary" style={{ flex: 1, height: 46, display: 'flex', justifyContent: 'center', gap: 8 }}
            onClick={() => navigate('/dashboard')}>
            <Home size={16} /> Về trang chủ
          </button>
          <button className="btn-ghost" style={{ flex: 1, height: 46, display: 'flex', justifyContent: 'center', gap: 8 }}
            onClick={() => navigate(`/exam/lobby/${id}`)}>
            <RefreshCw size={16} /> Thi lại
          </button>
        </div>

        <p style={{ textAlign: 'center', color: 'var(--color-surface-500)', fontSize: '0.8rem', marginTop: 16 }}>
          Nộp bài lúc {r.submittedAt}
        </p>
      </div>
    </div>
  )
}
